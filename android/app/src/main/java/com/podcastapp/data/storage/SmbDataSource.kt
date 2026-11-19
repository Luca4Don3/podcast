package com.podcastapp.data.storage

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import java.io.EOFException
import java.io.IOException

@OptIn(UnstableApi::class)
internal class SmbPlaybackFactory(
    val sourceId: String,
    private val mediaUri: Uri,
    private val location: SmbLocation,
    private val openFile: (SmbSocket) -> SmbReadHandle,
) : StoragePlaybackFactory {
    private var canceled = false
    private val sockets = mutableSetOf<SmbSocket>()

    override fun createDataSource(): DataSource = SmbDataSource(mediaUri, ::newSocket, openFile, ::forget)

    @Synchronized
    private fun newSocket(): SmbSocket {
        if (canceled) throw IOException("SMB 播放已结束。")
        return SmbSocket(location).also { sockets += it }
    }

    @Synchronized
    private fun forget(socket: SmbSocket) { sockets -= socket }

    override fun cancel() {
        val active = synchronized(this) { canceled = true; sockets.toList().also { sockets.clear() } }
        // This may be called on the UI thread; closing a raw socket is local and stops in-flight SMB reads.
        active.forEach(SmbSocket::close)
    }
}

@OptIn(UnstableApi::class)
internal class SmbDataSource(
    private val mediaUri: Uri,
    private val newSocket: () -> SmbSocket,
    private val openFile: (SmbSocket) -> SmbReadHandle,
    private val forget: (SmbSocket) -> Unit,
) : BaseDataSource(true) {
    private var socket: SmbSocket? = null
    private var opened = false
    private var reader: SmbRangeReader? = null

    override fun open(dataSpec: DataSpec): Long {
        if (dataSpec.uri != mediaUri || dataSpec.httpMethod != DataSpec.HTTP_METHOD_GET) {
            throw IOException("无效的 SMB 播放请求。")
        }
        transferInitializing(dataSpec)
        val range = try {
            val connection = newSocket().also { socket = it }
            val file = openFile(connection)
            try {
                SmbRangeReader(file, dataSpec.position, dataSpec.length).also { reader = it }
            } catch (e: Exception) {
                connection.close()
                runCatching { file.close() }
                throw e
            }
        } catch (e: SmbPositionException) {
            close()
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        } catch (_: Exception) {
            close()
            throw IOException("无法读取 SMB 音源，请检查网络、账号权限和共享设置。")
        }
        opened = true
        transferStarted(dataSpec)
        // Media3 requires the requested length for bounded DataSpecs, even if the range reaches EOF sooner.
        return if (dataSpec.length == C.LENGTH_UNSET.toLong()) range.remaining else dataSpec.length
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val count = checkNotNull(reader).read(buffer, offset, length)
        if (count > 0) bytesTransferred(count)
        return count
    }

    override fun getUri(): Uri? = mediaUri.takeIf { opened }

    override fun close() {
        try {
            runCatching { reader?.close() }
        } finally {
            reader = null
            socket?.let { it.close(); forget(it) }
            socket = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }
}

internal class SmbPositionException : IOException()

/** A fresh handle per Media3 open; every read supplies the actual SMB file offset. */
internal class SmbRangeReader(private val file: SmbReadHandle, private var position: Long, length: Long) : java.io.Closeable {
    var remaining: Long
        private set

    init {
        if (position < 0 || position > file.size) throw SmbPositionException()
        remaining = (file.size - position).let { if (length < 0) it else minOf(it, length) }
    }

    fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val limit = minOf(length.toLong(), remaining, SMB_READ_CHUNK.toLong()).toInt()
        val count = try {
            file.read(buffer, position, offset, limit)
        } catch (_: Exception) {
            throw IOException("SMB 音源连接已中断。")
        }
        if (count <= 0) throw EOFException("SMB 音源提前结束。")
        position += count
        remaining -= count
        return count
    }

    override fun close() = file.close()
}
