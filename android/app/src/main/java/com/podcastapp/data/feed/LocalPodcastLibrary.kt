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
import java.util.concurrent.TimeUnit
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
internal fun resolveRedirect(current: String, location: String): Result<String> = runCatching {
    val target = location.trim()
    require(target.isNotEmpty()) { "跳转地址为空" }
    val next = current.toHttpUrl().resolve(target)
        ?: throw IllegalArgumentException("拒绝非 http(s) 跳转")
    require(next.scheme.equals("http", ignoreCase = true) || next.scheme.equals("https", ignoreCase = true)) {
        "拒绝非 http(s) 跳转"
    }
    PlatformFeedAdapter.normalize(next.toString()).getOrThrow().url
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

/**
 * 公开 RSS / Apple 分享链接的本地导入库。
 *
 * 网络访问只用独立 OkHttp 客户端：不携带 Authorization、不访问自建后端、
 * 不跟随非 http(s) 跳转；Apple 链接经官方 JSON 接口取 results[0].feedUrl 后抓 RSS，
 * WebMetadata 只给出「需要节目公开 RSS 地址」提示，绝不抓网页。
 */
class LocalPodcastLibrary private constructor(
    private val storageDir: File,
    private val fetchText: (String) -> String,
) {

    constructor(context: Context) : this(
        context.applicationContext.filesDir,
        Companion::fetchHttpText,
    )

    /** 仅供 JVM 单测注入临时目录与假响应。 */
    internal constructor(
        storageDir: File,
        fetchText: (String) -> String,
        @Suppress("UNUSED_PARAMETER") testOnly: Boolean,
    ) : this(storageDir, fetchText)

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
    suspend fun `import`(input: String): Result<List<LocalFeed>> = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                val reference = PlatformFeedAdapter.normalize(input).getOrThrow()
                val imported = when (reference) {
                    is FeedReference.WebMetadata -> throw IllegalArgumentException(PUBLIC_FEED_REQUIRED_MESSAGE)
                    is FeedReference.AppleLookup -> {
                        val lookupJson = fetchText(reference.lookupUrl)
                        val feedUrl = parseAppleFeedUrl(lookupJson)
                        listOf(importRss(feedUrl, reference.url, FeedPlatform.APPLE))
                    }
                    is FeedReference.DirectFeed -> importDirect(reference.url)
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
                ).copy(importedAt = existing.importedAt)
                val next = _feeds.value.map { if (it.id == id) updated else it }
                persist(next)
                _feeds.value = next
                updated
            }.onFailure { if (it is CancellationException) throw it }
        }
    }

    private fun importDirect(url: String): List<LocalFeed> {
        val xml = fetchText(url)
        if (OPML_ROOT.containsMatchIn(xml.take(2048))) {
            val outlines = PlatformFeedAdapter.parseOpml(xml).getOrThrow()
            if (outlines.isEmpty()) throw IllegalArgumentException("OPML 中没有可用的公开 feed 地址")
            return outlines.map { outline ->
                importRss(outline.xmlUrl, outline.xmlUrl, FeedPlatform.DIRECT, outline.title)
            }
        }
        return listOf(importRss(url, url, FeedPlatform.DIRECT))
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
        titleHint: String = "",
    ): LocalFeed {
        val safeUrl = PlatformFeedAdapter.normalize(feedUrl).getOrThrow().url
        val parsed = PlatformFeedAdapter.parseFeedXml(fetchText(safeUrl)).getOrThrow()
        val id = localFeedId(safeUrl)
        val now = System.currentTimeMillis()
        val podcast = PodcastDto(
            id = id,
            feedUrl = safeUrl,
            title = titleHint.ifBlank { parsed.title }
                .ifBlank { safeUrl.substringAfterLast('/').ifBlank { safeUrl } },
            description = parsed.description,
            link = sourceUrl,
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
        return LocalFeed(
            id = id,
            sourceUrl = sourceUrl,
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
                instance ?: LocalPodcastLibrary(appContext.filesDir, Companion::fetchHttpText).also { instance = it }
            }
        }

        /** 仅供 JVM 单测注入临时目录与假响应，复用与 get(context) 相同的单例逻辑。 */
        internal fun getOrCreate(storageDir: File, fetchText: (String) -> String): LocalPodcastLibrary {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: LocalPodcastLibrary(storageDir, fetchText).also { instance = it }
            }
        }

        internal fun resetForTest() {
            synchronized(this) { instance = null }
        }

        private val httpClient: OkHttpClient = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .build()

        /** 手动跟随跳转：每跳都重新校验 URL 协议，响应限制 4 MiB。 */
        fun fetchHttpText(url: String): String {
            var current = url
            for (hop in 0..MAX_REDIRECTS) {
                val safeUrl = PlatformFeedAdapter.normalize(current)
                    .getOrElse { throw IOException(it.message ?: "链接无效") }.url
                val request = Request.Builder().url(safeUrl).header("User-Agent", USER_AGENT).build()
                var result: String? = null
                httpClient.newCall(request).execute().use { response ->
                    when {
                        response.code in 300..399 -> {
                            if (hop == MAX_REDIRECTS) throw IOException("跳转次数过多")
                            val location = response.header("Location")
                            if (location.isNullOrBlank()) throw IOException("跳转地址缺失")
                            current = resolveRedirect(safeUrl, location)
                                .getOrElse { throw IOException(it.message ?: "跳转地址无效") }
                        }
                        !response.isSuccessful -> throw IOException("HTTP " + response.code)
                        else -> result = readBounded(response.body?.byteStream() ?: throw IOException("响应为空"))
                    }
                }
                if (result != null) return result as String
            }
            throw IOException("跳转次数过多")
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
