package com.podcastapp.data.feed

import android.content.Context
import com.podcastapp.data.remote.EpisodeDto
import java.io.File
import java.io.IOException
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal enum class OfflinePolicy(val value: Int, val label: String) {
    NONE(0, "不缓存"), LATEST_5(5, "最新 5 集"), LATEST_10(10, "最新 10 集"), ALL(-1, "全部");
}

internal data class OfflineProgress(
    val completed: Int = 0,
    val total: Int = 0,
    val bytes: Long = 0,
    val expectedBytes: Long? = null,
    val failed: Int = 0,
    val skipped: Int = 0,
)

internal class OfflinePodcastCache(
    private val root: File,
    private val files: OfflineCacheFiles,
    private val downloader: OfflineMediaDownloader,
) {
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val playbackLock = Any()
    private var playbackFiles: Set<File> = emptySet()

    fun policy(feed: LocalFeed): OfflinePolicy = try {
        val value = policyFile(feed).readText().trim().toIntOrNull()
        OfflinePolicy.entries.firstOrNull { it.value == value } ?: OfflinePolicy.NONE
    } catch (_: IOException) {
        OfflinePolicy.NONE
    }

    fun readyFile(feed: LocalFeed, episode: EpisodeDto): File? {
        if (episode.podcastId != feed.id || policy(feed) == OfflinePolicy.NONE) return null
        val name = offlineName(episode) ?: return null
        val dir = directory(feed)
        return File(dir, name).takeIf { files.fileSize(dir, name) in 1..MAX_OFFLINE_EPISODE_BYTES }
    }

    // 在同一个锁内查找和标记，避免刷新在这两步之间裁掉已交给播放器的完整文件。
    fun selectPlaybackFiles(episodes: List<EpisodeDto>, feedFor: (EpisodeDto) -> LocalFeed?): List<File?> =
        synchronized(playbackLock) {
            episodes.map { episode -> feedFor(episode)?.let { readyFile(it, episode) } }
                .also { playbackFiles = it.filterNotNull().toSet() }
        }

    fun clearPlaybackFiles() = synchronized(playbackLock) { playbackFiles = emptySet() }

    private fun directory(feed: LocalFeed) = File(root, sha256Hex(feed.feedUrl))
    private fun policyFile(feed: LocalFeed) = File(root, directory(feed).name + ".policy")

    suspend fun update(
        feed: LocalFeed,
        newPolicy: OfflinePolicy? = null,
        access: () -> OfflineSourceAccess = { OfflineSourceAccess() },
        onProgress: (OfflineProgress) -> Unit = {},
    ): OfflineProgress = withContext(Dispatchers.IO) {
        val dir = directory(feed)
        locks.computeIfAbsent(dir.name) { Mutex() }.withLock {
            val chosen = newPolicy ?: policy(feed)
            if (chosen == OfflinePolicy.NONE) {
                if (dir.exists()) prune(dir, emptyList(), includePlayback = false)
                if (newPolicy != null) savePolicy(feed, chosen)
                return@withLock OfflineProgress()
            }
            if (newPolicy != null) savePolicy(feed, chosen)
            val candidates = feed.episodes.filter { it.podcastId == feed.id }
                .mapNotNull { episode -> offlineName(episode)?.let { it to episode } }
                .map { (name, episode) -> Triple(name, episode, publicationTime(episode.publishedAt)) }
                .sortedByDescending { it.third }
                .distinctBy { it.first }
            val targets = candidates.take(files.targetCount(chosen.value, candidates.size))
            if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) throw IOException("无法创建离线缓存目录")
            val keep = ArrayList<String>(targets.size)
            var progress = OfflineProgress(total = targets.size)
            onProgress(progress)
            val sourceAccess by lazy(access)
            for ((name, episode) in targets) {
                currentCoroutineContext().ensureActive()
                val existingSize = files.fileSize(dir, name)
                if (existingSize in 1..MAX_OFFLINE_EPISODE_BYTES) {
                    keep.add(name)
                } else {
                    val part = File(dir, name + ".part")
                    try {
                        part.delete()
                        val bytes = downloader.download(feed.feedUrl, episode.audioUrl, sourceAccess, part) { count, expected ->
                            onProgress(progress.copy(bytes = count, expectedBytes = expected))
                        }
                        currentCoroutineContext().ensureActive()
                        if (bytes in 1..MAX_OFFLINE_EPISODE_BYTES && files.commit(dir, part.name, name, bytes) == 0) {
                            keep.add(name)
                        } else {
                            progress = progress.copy(failed = progress.failed + 1)
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: UnsupportedOfflineMedia) {
                        progress = progress.copy(skipped = progress.skipped + 1)
                    } catch (_: Exception) {
                        // 不回显 URL、签名查询串、凭据或 TLS 材料。
                        progress = progress.copy(failed = progress.failed + 1)
                    } finally {
                        part.delete()
                    }
                }
                progress = progress.copy(completed = progress.completed + 1)
                onProgress(progress)
            }
            currentCoroutineContext().ensureActive()
            // 不因部分失败或取消而删除上一批还能播放的文件。
            if (progress.failed == 0) prune(dir, keep)
            progress
        }
    }

    private fun prune(dir: File, keep: Collection<String>, includePlayback: Boolean = true) = synchronized(playbackLock) {
        val protected = if (includePlayback) playbackFiles.filter { it.parentFile == dir }.map { it.name } else emptyList()
        if (files.prune(dir, (keep + protected).toSet()) < 0) throw IOException("无法裁剪离线缓存")
    }

    private fun savePolicy(feed: LocalFeed, chosen: OfflinePolicy) {
        val destination = policyFile(feed)
        if (chosen == OfflinePolicy.NONE) {
            if (destination.exists() && !destination.delete()) throw IOException("无法清除缓存策略")
            return
        }
        if (!root.isDirectory && !root.mkdirs() && !root.isDirectory) throw IOException("无法创建离线缓存目录")
        val tmp = File.createTempFile(destination.name, ".tmp", root)
        try {
            tmp.writeText(chosen.value.toString())
            if (!tmp.renameTo(destination)) throw IOException("无法保存缓存策略")
        } finally {
            tmp.delete()
        }
    }

    companion object {
        private var instance: OfflinePodcastCache? = null

        @Synchronized
        fun get(context: Context): OfflinePodcastCache = instance ?: OfflinePodcastCache(
            File(context.applicationContext.noBackupFilesDir, "offline-podcasts"),
            NativeOfflineCache,
            HttpOfflineMediaDownloader,
        ).also { instance = it }
    }
}

private val audioExtensions = setOf("mp3", "m4a", "mp4", "aac", "ogg", "opus", "wav", "flac", "aiff", "caf", "webm", "m4b", "wma", "audio")
private val excludedExtensions = setOf("m3u", "m3u8", "mpd", "pls", "asx", "html", "htm", "shtml", "xhtml", "php", "xml", "rss", "atom", "json")

internal fun offlineMediaUrl(raw: String, allowDocuments: Boolean = false) = raw.toHttpUrlOrNull()?.takeIf { url ->
    url.encodedUsername.isEmpty() && url.encodedPassword.isEmpty() &&
        (allowDocuments || url.pathSegments.last().substringAfterLast('.', "").lowercase(Locale.ROOT) !in excludedExtensions)
}

internal fun offlineName(episode: EpisodeDto): String? {
    val url = offlineMediaUrl(episode.audioUrl) ?: return null
    val extension = url.pathSegments.last().substringAfterLast('.', "").lowercase(Locale.ROOT)
    return sha256Hex(episode.id + "\n" + episode.audioUrl) + "." + extension.takeIf { it in audioExtensions }.orEmpty().ifEmpty { "audio" }
}

private val datePatterns = listOf("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ssXXX",
    "d MMM yyyy HH:mm:ss z", "d MMM yyyy HH:mm z", "yyyy-MM-dd")

private fun publicationTime(date: String): Long {
    val text = date.substringAfter(',').trim()
    for (pattern in datePatterns) {
        val position = ParsePosition(0)
        val time = SimpleDateFormat(pattern, Locale.US).apply {
            isLenient = false
            timeZone = TimeZone.getTimeZone("UTC")
        }.parse(text, position)
        if (time != null && position.index == text.length) return time.time
    }
    return Long.MIN_VALUE
}

internal suspend fun refreshFeedBeforeCache(
    refresh: suspend () -> Result<LocalFeed>,
    updateCache: suspend (LocalFeed) -> Unit,
): Result<LocalFeed> {
    val fresh = refresh().getOrElse {
        if (it is CancellationException) throw it
        return Result.failure(it)
    }
    currentCoroutineContext().ensureActive()
    updateCache(fresh)
    return Result.success(fresh)
}
