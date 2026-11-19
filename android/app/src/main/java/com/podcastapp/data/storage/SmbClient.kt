package com.podcastapp.data.storage

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition.FILE_OPEN
import com.hierynomus.mssmb2.SMB2CreateOptions.FILE_OPEN_REPARSE_POINT
import com.hierynomus.mssmb2.SMB2Dialect
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.auth.NtlmAuthenticator
import com.hierynomus.smbj.share.Directory
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

internal const val SMB_READ_CHUNK = 64 * 1024
private const val SMB_CONNECT_TIMEOUT_MS = 10_000
private val reparse = FileAttributes.FILE_ATTRIBUTE_REPARSE_POINT.value
private val directory = FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value
private val readOnlyOptions = setOf(FILE_OPEN_REPARSE_POINT)

internal fun smbConfig(socketFactory: SocketFactory): SmbConfig = SmbConfig.builder()
    .withDialects(SMB2Dialect.SMB_3_1_1, SMB2Dialect.SMB_3_0_2, SMB2Dialect.SMB_3_0, SMB2Dialect.SMB_2_1, SMB2Dialect.SMB_2_0_2)
    .withMultiProtocolNegotiate(false) // This otherwise starts negotiation with an SMB1 frame.
    .withSigningEnabled(true)
    .withSigningRequired(true)
    .withDfsEnabled(false)
    .withDirectoryLeasingEnabled(false)
    .withAuthenticators(NtlmAuthenticator.Factory())
    .withTimeout(15, TimeUnit.SECONDS)
    .withReadBufferSize(SMB_READ_CHUNK)
    .withSocketFactory(socketFactory)
    .build()

/** One direct TCP socket per operation. Even if a resolver attempts another connection, no credentials leave this socket. */
internal class SmbSocket(private val location: SmbLocation) : SocketFactory(), Closeable {
    private var socket: Socket? = null
    private var closed = false

    override fun createSocket(host: String, port: Int): Socket {
        val connection = synchronized(this) {
            if (closed || socket != null || !host.equals(location.host, true) || port != location.port) {
                throw IOException("拒绝 SMB 重定向或重复连接。")
            }
            Socket(Proxy.NO_PROXY).also { socket = it }
        }
        try {
            connection.connect(InetSocketAddress(host, port), SMB_CONNECT_TIMEOUT_MS)
            return connection
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    override fun createSocket(): Socket = throw IOException("需要指定 SMB 目标。")
    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket = createSocket(host, port)
    override fun createSocket(host: InetAddress, port: Int): Socket = throw IOException("需要指定 SMB 主机名。")
    override fun createSocket(host: InetAddress, port: Int, localHost: InetAddress, localPort: Int): Socket = createSocket(host, port)

    override fun close() {
        val connection = synchronized(this) { closed = true; socket.also { socket = null } }
        runCatching { connection?.close() }
    }
}

internal data class SmbEntry(val name: String, val path: String, val directory: Boolean, val size: Long?, val mime: String?)

internal interface SmbReadHandle : Closeable {
    val size: Long
    fun read(buffer: ByteArray, position: Long, offset: Int, length: Int): Int
}

/** Uses existing-only SMB CREATE for read access; no write/delete access or file creation. */
internal class SmbClient(
    private val location: SmbLocation,
    rootPath: String,
    private val credentials: SmbCredentials,
) {
    private val root = smbPathSegments(rootPath)
    private fun pathWithinRoot(path: String): List<String> = smbPathSegments(path).also {
        require(it.take(root.size) == root) { "SMB 路径超出来源根目录。" }
    }

    fun list(path: String): List<SmbEntry> = SmbSocket(location).use { socket ->
        val segments = pathWithinRoot(path)
        SMBClient(smbConfig(socket)).use { client ->
            val share = connectShare(client)
            checkDirectories(share, segments.dropLast(1))
            openDirectory(share, segments.joinToString("\\")).use { dir ->
                dir.asSequence().mapNotNull { info ->
                    val name = info.fileName
                    val attributes = info.fileAttributes
                    if (!validSmbSegment(name) || attributes and reparse != 0L) return@mapNotNull null
                    val isDirectory = attributes and directory != 0L
                    val mime = if (isDirectory) null else smbAudioMime(name) ?: return@mapNotNull null
                    SmbEntry(name, (segments + name).joinToString("/"), isDirectory, info.endOfFile.takeUnless { isDirectory }, mime)
                }.sortedWith(compareBy<SmbEntry> { !it.directory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }).toList()
            }
        }
    }

    fun playbackFactory(sourceId: String, uri: android.net.Uri, path: String) =
        SmbPlaybackFactory(sourceId, uri, location) { socket -> open(path, socket) }

    fun open(path: String, socket: SmbSocket): SmbReadHandle {
        val segments = pathWithinRoot(path)
        require(segments.isNotEmpty() && smbAudioMime(segments.last()) != null) { "不是可播放的 SMB 文件。" }
        val client = SMBClient(smbConfig(socket))
        try {
            val share = connectShare(client)
            checkDirectories(share, segments.dropLast(1))
            val file = share.openFile(segments.joinToString("\\"),
                setOf(AccessMask.FILE_READ_DATA, AccessMask.FILE_READ_ATTRIBUTES), null, SMB2ShareAccess.ALL, FILE_OPEN, readOnlyOptions)
            val info = file.fileInformation
            rejectReparse(info.basicInformation.fileAttributes)
            return ReadHandle(client, file, info.standardInformation.endOfFile)
        } catch (failure: Exception) {
            socket.close()
            client.close()
            throw failure
        }
    }

    private fun connectShare(client: SMBClient): DiskShare {
        credentials.validate()
        val password = credentials.password.toCharArray()
        val auth = try {
            AuthenticationContext(credentials.username, password, credentials.domain)
        } finally {
            password.fill('\u0000')
        }
        val connection = client.connect(location.host, location.port)
        val session = connection.authenticate(auth)
        if (session.isGuest || !session.isSigningRequired) throw IOException("SMB 来源必须支持已认证的签名会话。")
        return session.connectShare(location.share) as? DiskShare ?: throw IOException("SMB 来源不是磁盘共享。")
    }

    private fun openDirectory(share: DiskShare, path: String): Directory {
        val handle = share.openDirectory(path, setOf(AccessMask.FILE_LIST_DIRECTORY, AccessMask.FILE_READ_ATTRIBUTES),
            null, SMB2ShareAccess.ALL, FILE_OPEN, readOnlyOptions)
        try {
            rejectReparse(handle.fileInformation.basicInformation.fileAttributes)
            return handle
        } catch (failure: Exception) {
            runCatching { handle.close() }
            throw failure
        }
    }

    private fun checkDirectories(share: DiskShare, parents: List<String>) {
        for (depth in 1..parents.size) openDirectory(share, parents.take(depth).joinToString("\\")).use { }
    }

    private fun rejectReparse(attributes: Long) {
        if (attributes and reparse != 0L) throw IOException("不支持 SMB 链接或重解析点。")
    }

    private class ReadHandle(private val client: SMBClient, private val file: File, override val size: Long) : SmbReadHandle {
        override fun read(buffer: ByteArray, position: Long, offset: Int, length: Int): Int =
            file.read(buffer, position, offset, minOf(length, SMB_READ_CHUNK))

        override fun close() {
            try { file.close() } finally { client.close() }
        }
    }
}
