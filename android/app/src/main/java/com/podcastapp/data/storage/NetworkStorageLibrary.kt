package com.podcastapp.data.storage

import android.content.Context
import com.podcastapp.data.feed.SourceCredentialStore
import com.podcastapp.data.feed.SourceCredentials
import com.podcastapp.data.feed.SourceTransport
import com.podcastapp.data.feed.SourceTransportSecurity
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl

private const val STORAGE_FILE = "network_storage.json"
private const val DEFAULT_JIANGUOYUN_URL = "https://dav.jianguoyun.com/dav/"
private val CREDENTIAL_QUERY_KEYS = setOf("token", "api_key", "key")

/** 网络存储来源类型。极空间只保存用户自己填写的已开启 WebDAV 的地址。 */
@Serializable
enum class StorageProvider { WEBDAV, JIANGUOYUN, ZSPACE, FEILIAN, SMB }

/**
 * 可持久化的来源元数据：这里只有非敏感字段，凭据/传输材料一律不进入本对象与落盘 JSON。
 */
@Serializable
data class StorageSource(
    val id: String,
    val provider: StorageProvider,
    val label: String,
    val baseUrl: String,
    val rootPath: String = "",
)

/** 来自网络存储的一个音频条目；[sourceId] 指向 [StorageSource.id]。 */
data class StorageMedia(
    val sourceId: String,
    val url: String,
    val title: String,
    val mime: String? = null,
)

@Serializable
internal data class NetworkStorageSnapshot(val sources: List<StorageSource> = emptyList())

/** provider + 归一化后的 baseUrl/rootPath 的稳定哈希，重复添加同一来源得到同一 ID。 */
internal fun storageSourceId(provider: StorageProvider, baseUrl: String, rootPath: String): String =
    "net-storage-" + sha256Hex(listOf(provider.name, baseUrl, rootPath).joinToString("|")).take(16)

/**
 * 本地网络存储来源库：元数据落在 filesDir/network_storage.json，
 * 凭据与传输材料按 source.id 通过 [SourceCredentialStore] 单独加密保存。
 *
 * 坚果云空地址使用官方 WebDAV 默认值；极空间不做任何发现或私有 API 调用。
 */
class NetworkStorageLibrary private constructor(
    private val storageDir: File,
    private val credentialStore: SourceCredentialStore,
) {

    constructor(context: Context) : this(
        context.applicationContext.filesDir,
        SourceCredentialStore(context.applicationContext),
    )

    /** 仅供 JVM 单测注入临时目录与内存凭据存储。 */
    internal constructor(
        storageDir: File,
        credentialStore: SourceCredentialStore,
        @Suppress("UNUSED_PARAMETER") testOnly: Boolean,
    ) : this(storageDir, credentialStore)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val snapshotFile = File(storageDir, STORAGE_FILE)
    private val mutex = Mutex()
    private val _sources = MutableStateFlow(readSnapshot())
    val sources: StateFlow<List<StorageSource>> = _sources.asStateFlow()

    /**
     * 新增或覆盖一个来源。baseUrl/rootPath 会在这里 trim 并规范化；凭据只写入加密存储。
     * 同一 provider + baseUrl + rootPath 再次添加会覆盖旧条目的 label 与凭据。
     */
    suspend fun add(
        provider: StorageProvider,
        label: String,
        baseUrl: String,
        rootPath: String = "",
        credentials: SourceCredentials,
        transport: SourceTransport = SourceTransport(),
    ): Result<StorageSource> = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                require(provider != StorageProvider.SMB)
                val safeBaseUrl = normalizeBaseUrl(baseUrl, provider, transport)
                val safeRootPath = rootPath.trim().trim('/')
                val source = StorageSource(
                    id = storageSourceId(provider, safeBaseUrl, safeRootPath),
                    provider = provider,
                    label = label.trim(),
                    baseUrl = safeBaseUrl,
                    rootPath = safeRootPath,
                )
                credentialStore.save(source.id, credentials, transport)
                putSource(source)
            }.onFailure { if (it is CancellationException) throw it }
        }
    }

    internal suspend fun addSmb(label: String, address: String, path: String, credentials: SmbCredentials): Result<StorageSource> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                runCatching {
                    val url = SmbLocation.parse(address).url
                    val root = normalizeSmbRoot(path)
                    credentials.validate()
                    val id = storageSourceId(StorageProvider.SMB, url, root)
                    credentialStore.saveSmb(id, credentials)
                    putSource(StorageSource(id, StorageProvider.SMB, label.trim(), url, root))
                }.onFailure { if (it is CancellationException) throw it }
            }
        }

    internal fun smbClient(source: StorageSource): SmbClient {
        require(source.provider == StorageProvider.SMB)
        return SmbClient(SmbLocation.parse(source.baseUrl), source.rootPath, credentialStore.loadSmb(source.id))
    }

    private fun putSource(source: StorageSource): StorageSource {
        val byId = _sources.value.associateByTo(LinkedHashMap()) { it.id }
        byId[source.id] = source
        val next = byId.values.toList()
        persist(next)
        _sources.value = next
        return source
    }

    /** 删除来源并清理同 key 的凭据与传输材料。 */
    suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val next = _sources.value.filterNot { it.id == id }
            persist(next)
            _sources.value = next
            credentialStore.remove(id)
        }
    }

    /** 不存在或已删除的 ID 返回默认空凭据与默认传输设置，不抛异常。 */
    fun credentialsFor(id: String): Pair<SourceCredentials, SourceTransport> =
        credentialStore.load(id) to credentialStore.loadTransport(id)

    private fun readSnapshot(): List<StorageSource> = runCatching {
        if (!snapshotFile.exists()) emptyList()
        else json.decodeFromString<NetworkStorageSnapshot>(snapshotFile.readText(Charsets.UTF_8)).sources
    }.getOrElse { emptyList() }

    private fun persist(sources: List<StorageSource>) {
        val text = json.encodeToString(NetworkStorageSnapshot(sources))
        val temp = File(storageDir, STORAGE_FILE + ".tmp")
        temp.writeText(text, Charsets.UTF_8)
        if (!temp.renameTo(snapshotFile)) {
            snapshotFile.writeText(text, Charsets.UTF_8)
            temp.delete()
        }
    }
}

/** 只接受 http(s)，拒绝 userinfo、控制字符与查询串凭据；坚果云空地址回落到官方 WebDAV 根。 */
private fun normalizeBaseUrl(value: String, provider: StorageProvider, transport: SourceTransport): String {
    val raw = value.trim()
    val candidate = if (raw.isEmpty() && provider == StorageProvider.JIANGUOYUN) DEFAULT_JIANGUOYUN_URL else raw
    require(candidate.isNotEmpty()) { "baseUrl 不能为空" }
    require(!candidate.any { it.isISOControl() || it.isWhitespace() || it == '\\' }) { "baseUrl 含非法字符" }
    val url = runCatching { candidate.toHttpUrl() }
        .getOrElse { throw IllegalArgumentException("baseUrl 必须是有效的 http(s) URL") }
    require(url.scheme.equals("http", ignoreCase = true) || url.scheme.equals("https", ignoreCase = true)) {
        "baseUrl 只接受 http(s)"
    }
    require(url.username.isEmpty() && url.password.isEmpty()) { "baseUrl 不得包含 userinfo" }
    require(url.queryParameterNames.none { it.lowercase(Locale.ROOT) in CREDENTIAL_QUERY_KEYS }) {
        "凭据不得出现在 URL 查询参数中"
    }
    SourceTransportSecurity.assertSchemeAllowed(url.toString(), transport)
    val normalized = url.toString()
    return if (normalized.endsWith("/")) normalized else normalized + "/"
}

private fun sha256Hex(value: String): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    return buildString(bytes.size * 2) {
        for (byte in bytes) {
            val v = byte.toInt() and 0xff
            append(Character.forDigit(v shr 4, 16))
            append(Character.forDigit(v and 0x0f, 16))
        }
    }
}
