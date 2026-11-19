package com.podcastapp.data.feed

import android.content.Context
import com.podcastapp.data.remote.EpisodeDto
import com.podcastapp.data.remote.PodcastDto
import com.podcastapp.data.remote.SettingsDto
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
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
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

private const val LIBRARY_FILE = "local_podcast_library.json"
private const val STATE_FILE = "local_podcast_state.json"
private const val MAX_DOWNLOAD_BYTES = 4 * 1024 * 1024
private const val MAX_REDIRECTS = 5
private const val USER_AGENT = "PodcastApp-LocalFeed/1.5"

/** 平台页面无法直接解析时给用户看的固定提示；不会抓取任何网页。 */
const val PUBLIC_FEED_REQUIRED_MESSAGE = "需要节目公开 RSS 地址"

private val OPML_ROOT = Regex("""<\s*opml\b""", RegexOption.IGNORE_CASE)

/** 本节目公开的订阅与单集，全部存在应用私有目录，不经过自建服务器。 */
@Serializable
data class LocalFeed(
    val id: String,
    val sourceUrl: String,
    val feedUrl: String,
    val platform: String,
    val podcast: PodcastDto,
    val episodes: List<EpisodeDto>,
    val importedAt: Long = 0L,
)

/** 单集在本机的播放进度；progress 以 episodeId 为 key。 */
@Serializable
data class LocalPlaybackProgress(
    val episodeId: String,
    val positionMs: Long,
    val durationMs: Long,
    val completed: Boolean,
    val updatedAt: Long,
)

/** 播放器/收藏/待播与设置的纯本地状态，落盘到应用私有目录。 */
@Serializable
data class LocalLibraryState(
    val progress: Map<String, LocalPlaybackProgress> = emptyMap(),
    val starred: Set<String> = emptySet(),
    val queued: Set<String> = emptySet(),
    val settings: SettingsDto = SettingsDto(),
)

@Serializable
internal data class LibrarySnapshot(val feeds: List<LocalFeed> = emptyList())

@Serializable
internal data class AppleLookupResponse(
    val resultCount: Int = 0,
    val results: List<AppleLookupResult> = emptyList(),
)

@Serializable
internal data class AppleLookupResult(val feedUrl: String = "")

internal fun LocalFeed.platformType(): FeedPlatform =
    runCatching { FeedPlatform.valueOf(platform) }.getOrDefault(FeedPlatform.DIRECT)

fun LocalFeed.platformLabel(): String = when (platformType()) {
    FeedPlatform.DIRECT -> "公开 RSS / OPML"
    FeedPlatform.APPLE -> "Apple 播客"
    FeedPlatform.SPOTIFY -> "Spotify"
    FeedPlatform.XIAOYUZHOU -> "小宇宙"
    FeedPlatform.XIMALAYA -> "喜马拉雅"
}

/** 基于公开 feed 地址生成稳定本地 ID，重复导入只更新同一条订阅。 */
internal fun localFeedId(feedUrl: String): String = "local-feed-" + sha256Hex(feedUrl).take(16)

internal fun localEpisodeId(feedId: String, item: ParsedEpisode): String {
    val key = item.guid.ifBlank {
        item.audioUrl.ifBlank { item.title + "|" + item.pubDate + "|" + item.description }
    }
    return feedId + "-e-" + sha256Hex(key).take(16)
}

/** 兼容 HH:MM:SS / MM:SS / 纯秒数；无效值记为 0。 */
internal fun parseDurationSeconds(raw: String): Int {
    val parts = raw.trim().split(':')
    val seconds = when (parts.size) {
        1 -> parts[0].toLongOrNull() ?: 0L
        2 -> (parts[0].toLongOrNull() ?: 0L) * 60L + (parts[1].toLongOrNull() ?: 0L)
        3 -> (parts[0].toLongOrNull() ?: 0L) * 3600L +
            (parts[1].toLongOrNull() ?: 0L) * 60L + (parts[2].toLongOrNull() ?: 0L)
        else -> 0L
    }
    return seconds.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
}

/**
 * 只接受 http(s) 跳转；相对地址按当前地址解析。
 * 非 http(s)（file/ftp/javascript 等）一律拒绝，且不在这里发起任何请求。
 */
internal fun resolveRedirect(
    current: String,
    location: String,
    transport: SourceTransport = SourceTransport(),
): Result<String> = runCatching {
    val target = location.trim()
    require(target.isNotEmpty()) { "跳转地址为空" }
    val next = current.toHttpUrl().resolve(target)
        ?: throw IllegalArgumentException("拒绝非 http(s) 跳转")
    require(next.scheme.equals("http", ignoreCase = true) || next.scheme.equals("https", ignoreCase = true)) {
        "拒绝非 http(s) 跳转"
    }
    if (transport.requireHttps) {
        require(next.scheme.equals("https", ignoreCase = true)) { "requireHttps 已启用，拒绝 http 跳转" }
    }
    PlatformFeedAdapter.normalize(next.toString()).getOrThrow().url
}

/** 由凭据生成请求头。NONE 不产生任何认证头；API_KEY 使用凭据自带的头名。 */
internal fun credentialHeaders(credentials: SourceCredentials): Map<String, String> = when (credentials.kind) {
    CredentialKind.NONE -> emptyMap()
    CredentialKind.BASIC -> {
        val raw = credentials.username + ":" + credentials.secret
        val encoded = Base64.getEncoder().encodeToString(raw.toByteArray(Charsets.UTF_8))
        mapOf("Authorization" to "Basic " + encoded)
    }
    CredentialKind.BEARER -> mapOf("Authorization" to "Bearer " + credentials.secret)
    CredentialKind.API_KEY -> mapOf(
        (credentials.header.takeIf { it.isNotBlank() } ?: "X-API-Key") to credentials.secret,
    )
    // Digest 只在 401 挑战之后由 DigestAuthenticator 计算，绝不预置静态 Authorization。
    CredentialKind.DIGEST -> emptyMap()
}

/** 只在同 host 且两端都是 https 时沿跳转转发凭据；其余情况一律降级为 NONE。 */
internal fun credentialsForRedirect(
    currentUrl: String,
    redirectUrl: String,
    credentials: SourceCredentials,
): SourceCredentials {
    if (credentials.kind == CredentialKind.NONE) return SourceCredentials()
    val current = runCatching { currentUrl.toHttpUrl() }.getOrNull() ?: return SourceCredentials()
    val next = runCatching { redirectUrl.toHttpUrl() }.getOrNull() ?: return SourceCredentials()
    val sameHost = current.host.equals(next.host, ignoreCase = true)
    val https = current.scheme.equals("https", ignoreCase = true) &&
        next.scheme.equals("https", ignoreCase = true)
    return if (sameHost && https) credentials else SourceCredentials()
}

/** 跨 host / 端口变化 / https 降级时剥离自定义 CA、客户端证书私钥与 digest。 */
internal fun transportForRedirect(
    currentUrl: String,
    redirectUrl: String,
    transport: SourceTransport,
): SourceTransport = SourceTransportSecurity.transportForRedirect(currentUrl, redirectUrl, transport)

/** DIGEST 凭据或来源显式开启 digest 时，才在 401 挑战后计算 Authorization。 */
internal fun digestEnabled(credentials: SourceCredentials, transport: SourceTransport): Boolean =
    credentials.kind == CredentialKind.DIGEST || transport.digest

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

/**
 * 公开 RSS / Apple 分享链接的本地导入库。
 *
 * 网络访问只用独立 OkHttp 客户端：不访问自建后端、不带 cookie、不跟随非 http(s) 跳转；
 * 凭据仅按来源类型生成对应请求头，跨 host/非 https 跳转不转发，并单独加密落盘。
 * Apple 链接经官方 JSON 接口取 results[0].feedUrl 后抓 RSS，
 * WebMetadata 只给出「需要节目公开 RSS 地址」提示，绝不抓网页。
 */
class LocalPodcastLibrary private constructor(
    private val storageDir: File,
    private val fetchText: (String, SourceCredentials, SourceTransport) -> String,
    private val credentialStore: SourceCredentialStore,
) {

    constructor(context: Context) : this(
        context.applicationContext.filesDir,
        Companion::fetchHttpText,
        SourceCredentialStore(context.applicationContext),
    )

    /** 仅供 JVM 单测注入临时目录、假响应与假凭据存储（兼容两参数 fetcher）。 */
    internal constructor(
        storageDir: File,
        fetchText: (String, SourceCredentials) -> String,
        credentialStore: SourceCredentialStore,
        @Suppress("UNUSED_PARAMETER") testOnly: Boolean,
    ) : this(storageDir, { url, credentials, _ -> fetchText(url, credentials) }, credentialStore)

    /** 仅供 JVM 单测注入带传输参数的 fetcher；Int 标记用于区分 JVM 重载。 */
    internal constructor(
        storageDir: File,
        fetchText: (String, SourceCredentials, SourceTransport) -> String,
        credentialStore: SourceCredentialStore,
        @Suppress("UNUSED_PARAMETER") testOnlyTransport: Int,
    ) : this(storageDir, fetchText, credentialStore)

    /** 兼容只关心 URL 的单测 fetcher；凭据仍会被解析并写入凭据存储。 */
    internal constructor(
        storageDir: File,
        fetchText: (String) -> String,
        @Suppress("UNUSED_PARAMETER") testOnly: Boolean,
    ) : this(
        storageDir,
        { url, _, _ -> fetchText(url) },
        SourceCredentialStore(InMemoryCredentialStorage()),
        1,
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val snapshotFile = File(storageDir, LIBRARY_FILE)
    private val mutex = Mutex()
    private val _feeds = MutableStateFlow(readSnapshot())
    val feeds: StateFlow<List<LocalFeed>> = _feeds.asStateFlow()

    private val stateFile = File(storageDir, STATE_FILE)
    private val stateLock = Any()
    private val _state = MutableStateFlow(readState())
    val state: StateFlow<LocalLibraryState> = _state.asStateFlow()

    /** 导入单个 RSS/OPML 链接或 Apple 分享链接，返回本次新增/更新的订阅。 */
    suspend fun `import`(
        input: String,
        credentials: SourceCredentials = SourceCredentials(),
        transport: SourceTransport = SourceTransport(),
    ): Result<List<LocalFeed>> = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                val reference = PlatformFeedAdapter.normalize(input, credentials).getOrThrow()
                val imported = when (reference) {
                    is FeedReference.WebMetadata -> throw IllegalArgumentException(PUBLIC_FEED_REQUIRED_MESSAGE)
                    is FeedReference.AppleLookup -> {
                        // 官方 lookup 属于其它 host，绝不携带 feed 凭据。
                        // Apple lookup 属于其它 host，绝不携带 feed 凭据或传输材料。
                        val lookupJson = fetchText(reference.lookupUrl, SourceCredentials(), SourceTransport())
                        val feedUrl = parseAppleFeedUrl(lookupJson)
                        listOf(importRss(feedUrl, reference.url, FeedPlatform.APPLE, credentials, transport))
                    }
                    is FeedReference.DirectFeed -> importDirect(reference.url, reference.credentials, transport)
                }
                commit(imported)
            }.onFailure { if (it is CancellationException) throw it }
        }
    }

    suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val next = _feeds.value.filterNot { it.id == id }
            persist(next)
            _feeds.value = next
            credentialStore.remove(id)
        }
    }

    /** 记录单集播放进度；同一 episodeId 覆盖旧值。 */
    fun recordProgress(episodeId: String, positionMs: Long, durationMs: Long, completed: Boolean) {
        val progress = LocalPlaybackProgress(
            episodeId = episodeId,
            positionMs = positionMs,
            durationMs = durationMs,
            completed = completed,
            updatedAt = System.currentTimeMillis(),
        )
        updateState { it.copy(progress = it.progress + (episodeId to progress)) }
    }

    fun setStarred(episodeId: String, starred: Boolean) = updateState { current ->
        val next = if (starred) current.starred + episodeId else current.starred - episodeId
        current.copy(starred = next)
    }

    fun setQueued(episodeId: String, queued: Boolean) = updateState { current ->
        val next = if (queued) current.queued + episodeId else current.queued - episodeId
        current.copy(queued = next)
    }

    fun saveSettings(next: SettingsDto) = updateState { it.copy(settings = next) }

    /** 用保存的公开 feed 地址重新拉取，保留原 ID、来源与首次导入时间。 */
    suspend fun refresh(id: String): Result<LocalFeed> = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                val existing = _feeds.value.firstOrNull { it.id == id }
                    ?: throw IllegalArgumentException("订阅不存在")
                val updated = importRss(
                    feedUrl = existing.feedUrl,
                    sourceUrl = existing.sourceUrl,
                    platform = existing.platformType(),
                    credentials = credentialStore.load(id),
                    transport = credentialStore.loadTransport(id),
                ).copy(importedAt = existing.importedAt)
                val next = _feeds.value.map { if (it.id == id) updated else it }
                persist(next)
                _feeds.value = next
                updated
            }.onFailure { if (it is CancellationException) throw it }
        }
    }

    private fun importDirect(
        url: String,
        credentials: SourceCredentials,
        transport: SourceTransport,
    ): List<LocalFeed> {
        SourceTransportSecurity.assertSchemeAllowed(url, transport)
        val xml = fetchText(url, credentials, transport)
        if (OPML_ROOT.containsMatchIn(xml.take(2048))) {
            val outlines = PlatformFeedAdapter.parseOpml(xml).getOrThrow()
            if (outlines.isEmpty()) throw IllegalArgumentException("OPML 中没有可用的公开 feed 地址")
            return outlines.map { outline ->
                importRss(outline.xmlUrl, outline.xmlUrl, FeedPlatform.DIRECT, credentials, transport, outline.title)
            }
        }
        return listOf(importRss(url, url, FeedPlatform.DIRECT, credentials, transport, xml = xml))
    }

    private fun parseAppleFeedUrl(lookupJson: String): String {
        val response = json.decodeFromString<AppleLookupResponse>(lookupJson)
        val feedUrl = response.results.firstOrNull()?.feedUrl.orEmpty()
        require(feedUrl.isNotBlank()) { "Apple 官方接口未返回公开 RSS 地址" }
        return PlatformFeedAdapter.normalize(feedUrl).getOrThrow().url
    }

    private fun importRss(
        feedUrl: String,
        sourceUrl: String,
        platform: FeedPlatform,
        credentials: SourceCredentials = SourceCredentials(),
        transport: SourceTransport = SourceTransport(),
        titleHint: String = "",
        xml: String? = null,
    ): LocalFeed {
        val feedReference = PlatformFeedAdapter.normalize(feedUrl, credentials).getOrThrow()
        val direct = feedReference as? FeedReference.DirectFeed
            ?: throw IllegalArgumentException("feed 地址必须是公开 http(s) 直链")
        val safeUrl = direct.url
        val effectiveCredentials = direct.credentials
        // 来源地址同样只保存去掉 userinfo/token 的干净 URL。
        val safeSourceUrl = PlatformFeedAdapter.normalize(sourceUrl).getOrThrow().url
        SourceTransportSecurity.assertSchemeAllowed(safeUrl, transport)
        val parsed = PlatformFeedAdapter.parseFeedXml(
            xml ?: fetchText(safeUrl, effectiveCredentials, transport),
        ).getOrThrow()
        val id = localFeedId(safeUrl)
        val now = System.currentTimeMillis()
        val podcast = PodcastDto(
            id = id,
            feedUrl = safeUrl,
            title = titleHint.ifBlank { parsed.title }
                .ifBlank { safeUrl.substringAfterLast('/').ifBlank { safeUrl } },
            description = parsed.description,
            link = safeSourceUrl,
            subscribed = true,
            subscribedAt = now.toString(),
        )
        val episodes = parsed.items.map { item ->
            EpisodeDto(
                id = localEpisodeId(id, item),
                podcastId = id,
                guid = item.guid,
                title = item.title.ifBlank { "未命名单集" },
                description = item.description,
                audioUrl = item.audioUrl,
                durationSeconds = parseDurationSeconds(item.duration),
                publishedAt = item.pubDate,
            )
        }
        // 凭据单独加密保存，LocalFeed / 落盘 JSON 里只有干净 URL。
        credentialStore.save(id, effectiveCredentials, transport)
        return LocalFeed(
            id = id,
            sourceUrl = safeSourceUrl,
            feedUrl = safeUrl,
            platform = platform.name,
            podcast = podcast,
            episodes = episodes,
            importedAt = now,
        )
    }

    /** 相同 ID 覆盖，保持已有订阅顺序，然后一次性落盘并发布状态。 */
    private fun commit(imported: List<LocalFeed>): List<LocalFeed> {
        val byId = LinkedHashMap<String, LocalFeed>()
        val order = ArrayList<String>()
        _feeds.value.forEach { byId[it.id] = it; order.add(it.id) }
        imported.forEach { feed ->
            if (!byId.containsKey(feed.id)) order.add(feed.id)
            byId[feed.id] = feed
        }
        val merged = order.mapNotNull(byId::get)
        persist(merged)
        _feeds.value = merged
        return imported
    }

    private fun readSnapshot(): List<LocalFeed> = runCatching {
        if (!snapshotFile.exists()) emptyList()
        else json.decodeFromString<LibrarySnapshot>(snapshotFile.readText(Charsets.UTF_8)).feeds
    }.getOrElse { emptyList() }

    private fun persist(feeds: List<LocalFeed>) {
        val text = json.encodeToString(LibrarySnapshot(feeds))
        val temp = File(storageDir, LIBRARY_FILE + ".tmp")
        temp.writeText(text, Charsets.UTF_8)
        if (!temp.renameTo(snapshotFile)) {
            snapshotFile.writeText(text, Charsets.UTF_8)
            temp.delete()
        }
    }

    private fun readState(): LocalLibraryState = runCatching {
        if (!stateFile.exists()) LocalLibraryState()
        else json.decodeFromString<LocalLibraryState>(stateFile.readText(Charsets.UTF_8))
    }.getOrElse { LocalLibraryState() }

    private fun persistState(next: LocalLibraryState) {
        val text = json.encodeToString(next)
        val temp = File(storageDir, STATE_FILE + ".tmp")
        temp.writeText(text, Charsets.UTF_8)
        if (!temp.renameTo(stateFile)) {
            stateFile.writeText(text, Charsets.UTF_8)
            temp.delete()
        }
    }

    private fun updateState(transform: (LocalLibraryState) -> LocalLibraryState) = synchronized(stateLock) {
        val next = transform(_state.value)
        persistState(next)
        _state.value = next
    }

    companion object {
        @Volatile
        private var instance: LocalPodcastLibrary? = null

        /** 进程级单例，只持有 applicationContext，不访问网络或后端。 */
        fun get(context: Context): LocalPodcastLibrary {
            instance?.let { return it }
            val appContext = context.applicationContext
            return synchronized(this) {
                instance ?: LocalPodcastLibrary(
                    appContext.filesDir,
                    Companion::fetchHttpText,
                    SourceCredentialStore(appContext),
                ).also { instance = it }
            }
        }

        /** 仅供 JVM 单测注入临时目录与假响应，复用与 get(context) 相同的单例逻辑。 */
        internal fun getOrCreate(storageDir: File, fetchText: (String) -> String): LocalPodcastLibrary =
            getOrCreate(storageDir, { url, _, _ -> fetchText(url) })

        internal fun getOrCreate(
            storageDir: File,
            fetchText: (String, SourceCredentials) -> String,
            credentialStore: SourceCredentialStore = SourceCredentialStore(InMemoryCredentialStorage()),
        ): LocalPodcastLibrary {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: LocalPodcastLibrary(
                    storageDir,
                    { url, credentials, _ -> fetchText(url, credentials) },
                    credentialStore,
                ).also { instance = it }
            }
        }

        /** 注入带传输参数的假 fetcher，供传输安全测试使用。 */
        internal fun getOrCreate(
            storageDir: File,
            fetchText: (String, SourceCredentials, SourceTransport) -> String,
            credentialStore: SourceCredentialStore = SourceCredentialStore(InMemoryCredentialStorage()),
        ): LocalPodcastLibrary {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: LocalPodcastLibrary(storageDir, fetchText, credentialStore).also { instance = it }
            }
        }

        internal fun resetForTest() {
            synchronized(this) { instance = null }
        }

        /**
         * 手动跟随跳转：每跳都重新校验 URL 协议，响应限制 4 MiB。
         * OkHttp 客户端按来源凭据与传输材料创建；跨 host / https 降级跳转同时剥离
         * 认证与客户端证书，requireHttps 下 http 及 http 降级跳转一律拒绝。
         */
        fun fetchHttpText(
            url: String,
            credentials: SourceCredentials = SourceCredentials(),
            transport: SourceTransport = SourceTransport(),
        ): String {
            var current = url
            var hopCredentials = credentials
            var hopTransport = transport
            for (hop in 0..MAX_REDIRECTS) {
                val reference = PlatformFeedAdapter.normalize(current, hopCredentials)
                    .getOrElse { throw IOException(it.message ?: "链接无效") }
                val safeUrl = reference.fetchUrl
                    ?: throw IOException("链接没有可抓取的公开 feed 地址")
                SourceTransportSecurity.assertSchemeAllowed(safeUrl, hopTransport)
                val requestCredentials = (reference as? FeedReference.DirectFeed)?.credentials
                    ?: SourceCredentials()
                val request = Request.Builder().url(safeUrl).header("User-Agent", USER_AGENT).apply {
                    credentialHeaders(requestCredentials).forEach { (name, value) -> header(name, value) }
                }.build()
                var result: String? = null
                buildClient(requestCredentials, hopTransport).newCall(request).execute().use { response ->
                    when {
                        response.code in 300..399 -> {
                            if (hop == MAX_REDIRECTS) throw IOException("跳转次数过多")
                            val location = response.header("Location")
                            if (location.isNullOrBlank()) throw IOException("跳转地址缺失")
                            val nextUrl = resolveRedirect(safeUrl, location, hopTransport)
                                .getOrElse { throw IOException(it.message ?: "跳转地址无效") }
                            hopCredentials = credentialsForRedirect(safeUrl, nextUrl, requestCredentials)
                            hopTransport = transportForRedirect(safeUrl, nextUrl, hopTransport)
                            current = nextUrl
                        }
                        !response.isSuccessful -> throw IOException("HTTP " + response.code)
                        else -> result = readBounded(response.body?.byteStream() ?: throw IOException("响应为空"))
                    }
                }
                if (result != null) return result as String
            }
            throw IOException("跳转次数过多")
        }

        /** 系统证书 + 自定义 CA、mTLS 密钥与 Digest Authenticator 都只作用于当前来源。 */
        private fun buildClient(credentials: SourceCredentials, transport: SourceTransport): OkHttpClient {
            val builder = OkHttpClient.Builder()
                .followRedirects(false)
                .followSslRedirects(false)
                .cookieJar(CookieJar.NO_COOKIES)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .callTimeout(45, TimeUnit.SECONDS)
            val hasTlsMaterial = transport.customCaPem.isNotBlank() ||
                transport.clientCertPem.isNotBlank() || transport.clientKeyPem.isNotBlank()
            if (hasTlsMaterial) {
                // 系统证书与自定义 CA 合并，不关闭校验；mTLS 私钥按来源解析。
                val trustManager = SourceTransportSecurity.trustManager(transport.customCaPem)
                val keyManagers: Array<KeyManager>? =
                    if (transport.clientCertPem.isNotBlank() || transport.clientKeyPem.isNotBlank()) {
                        arrayOf(
                            SourceTransportSecurity.keyManager(
                                transport.clientCertPem,
                                transport.clientKeyPem,
                                transport.clientKeyPassword,
                            ),
                        )
                    } else {
                        null
                    }
                val socketFactory = SSLContext.getInstance("TLS").apply {
                    init(keyManagers, arrayOf(trustManager), null)
                }.socketFactory
                builder.sslSocketFactory(socketFactory, trustManager)
            }
            if (digestEnabled(credentials, transport)) {
                builder.authenticator(DigestAuthenticator(credentials))
            }
            return builder.build()
        }

        private fun readBounded(input: InputStream): String {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var total = 0
            input.use { stream ->
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_DOWNLOAD_BYTES) throw IOException("响应超过 4 MiB 上限")
                    output.write(buffer, 0, read)
                }
            }
            return String(output.toByteArray(), Charsets.UTF_8)
        }
    }
}
