package com.podcastapp.playback

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.podcastapp.data.filemedia.FileMediaPlayback
import com.podcastapp.data.filemedia.FileStreamFactory
import com.podcastapp.data.feed.LocalPodcastLibrary
import com.podcastapp.data.feed.OfflinePodcastCache
import com.podcastapp.data.feed.SourceCredentials
import com.podcastapp.data.feed.SourceTransport
import com.podcastapp.data.remote.EpisodeDto
import com.podcastapp.data.remote.StationDto
import com.podcastapp.data.storage.OneDriveDownloads
import com.podcastapp.data.storage.OneDrivePlaybackFactory
import com.podcastapp.data.storage.SmbClient
import com.podcastapp.data.storage.SmbPlaybackFactory
import com.podcastapp.data.storage.StoragePlaybackFactory
import com.podcastapp.data.storage.StorageMedia
import com.podcastapp.data.storage.StorageStreamFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class NowPlayingFile(val id: String, val title: String)

/** 当前正在播放的媒体信息，供 UI 展示。 */
data class NowPlaying(
    val episode: EpisodeDto? = null,
    val podcastTitle: String = "",
    val artworkUrl: String = "",
    val station: StationDto? = null,
    val file: NowPlayingFile? = null,
)

internal data class PlaybackSnapshot(
    val mediaId: String? = null,
    val playing: Boolean = false,
    val isLive: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val speed: Float = 1f,
    val buffering: Boolean = false,
    val willPause: Boolean = false,
    val canSeek: Boolean = false,
)

internal enum class PlaybackToggle { PAUSE, PLAY, PREPARE_AND_PLAY, RESTART_AND_PLAY }

internal fun playbackToggle(state: Int, playWhenReady: Boolean): PlaybackToggle = when {
    state == Player.STATE_IDLE -> PlaybackToggle.PREPARE_AND_PLAY
    state == Player.STATE_ENDED -> PlaybackToggle.RESTART_AND_PLAY
    playWhenReady -> PlaybackToggle.PAUSE
    else -> PlaybackToggle.PLAY
}

private const val FILE_MEDIA_PREFIX = "filemedia:"
private const val STORAGE_MEDIA_PREFIX = "storage:"
private fun String?.isFileId() = this?.startsWith(FILE_MEDIA_PREFIX) == true
private fun String?.isStorageId() = this?.startsWith(STORAGE_MEDIA_PREFIX) == true
private fun String?.isFileOrStorageId() = isFileId() || isStorageId()

private const val UI_TICK_MS = 1_000L
private const val PROGRESS_SYNC_MS = 5_000L

internal fun playbackTickDelayMs(playingPodcast: Boolean, hasUi: Boolean, untilSyncMs: Long): Long? =
    if (!playingPodcast) null else minOf(if (hasUi) UI_TICK_MS else PROGRESS_SYNC_MS, untilSyncMs.coerceAtLeast(0))

internal data class ProgressCheckpoint(val episodeId: String, val positionMs: Long, val durationMs: Long, val completed: Boolean)

internal fun progressCheckpoint(mediaId: String?, positionMs: Long, durationMs: Long, completed: Boolean): ProgressCheckpoint? =
    mediaId?.takeUnless { it.startsWith("station:") || it.isFileOrStorageId() || durationMs <= 0 }?.let {
        ProgressCheckpoint(it, if (completed) durationMs else positionMs.coerceIn(0, durationMs), durationMs, completed)
    }

internal fun shouldReplaceCheckpoint(previous: ProgressCheckpoint?, next: ProgressCheckpoint, explicitSeek: Boolean): Boolean =
    next != previous && (explicitSeek || previous?.completed != true ||
        previous.episodeId != next.episodeId || previous.positionMs != next.positionMs)

/**
 * 全局播放器持有者：Service 与 UI 共用同一个 ExoPlayer 实例。
 *
 * 兼容性说明：
 * - 不在 App 启动时就拉起前台服务（Android 8+ 会因未及时 startForeground 崩溃），
 *   改为「首次播放时启动」，且启动异常一律吞掉不影响播放。
 * - Android 12+ 后台启动前台服务受限，这里的调用都来自可见界面。
 */
object PlaybackHolder {

    private var player: ExoPlayer? = null
    private var appContext: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var podcastSpeed = 1f
    private var fileStreamFactory: FileStreamFactory? = null
    private var storageStreamFactory: StoragePlaybackFactory? = null
    private var ticker: Job? = null
    private var hasUi = false
    private var syncAtMs: Long? = null
    private var durationMediaId: String? = null
    private var knownDurationMs = 0L
    private var lastCheckpoint: ProgressCheckpoint? = null

    private val _playback = MutableStateFlow(PlaybackSnapshot())
    internal val playback: StateFlow<PlaybackSnapshot> = _playback.asStateFlow()

    init {
        scope.launch {
            _playback.subscriptionCount.collect { count ->
                val visible = count > 0
                if (visible != hasUi) {
                    hasUi = visible
                    if (visible) player?.let(::publishPlayback)
                    updateTicker(restart = true)
                }
            }
        }
    }

    private val _nowPlaying = MutableStateFlow(NowPlaying())
    val nowPlaying: StateFlow<NowPlaying> = _nowPlaying.asStateFlow()

    private val _queue = MutableStateFlow<List<EpisodeDto>>(emptyList())
    val queue: StateFlow<List<EpisodeDto>> = _queue.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _notificationWarning = MutableStateFlow(false)
    /** Android 13+ 未授予通知权限时为 true，UI 可提示「后台播放可能被系统中断」。 */
    val notificationWarning: StateFlow<Boolean> = _notificationWarning.asStateFlow()

    fun init(context: Context) {
        appContext = context.applicationContext
        if (player != null) return
        val exo = ExoPlayer.Builder(context.applicationContext).build().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true,
            )
            setHandleAudioBecomingNoisy(true)
            addListener(object : Player.Listener {
                override fun onPlayerErrorChanged(error: androidx.media3.common.PlaybackException?) {
                    _lastError.value = error?.let { it.message ?: "播放失败" }
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    val id = mediaItem?.mediaId
                    if (_nowPlaying.value.file?.let { it.id != id } == true) clearFilePlayback()
                    _lastError.value = null
                    _queue.value.firstOrNull { it.id == id }?.let { episode ->
                        _nowPlaying.value = _nowPlaying.value.copy(episode = episode, station = null)
                    }
                }

                override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                    val oldId = oldPosition.mediaItem?.mediaId
                    val newId = newPosition.mediaItem?.mediaId
                    if (oldId != newId || reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
                        // Getter 已指向新媒体；用回调提供的旧 ID/位置和此前记下的旧时长。
                        if (oldId == durationMediaId && oldPosition.positionMs > 0) {
                            save(progressCheckpoint(oldId, oldPosition.positionMs, knownDurationMs,
                                completed = reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION))
                        }
                    } else if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                        checkpoint(allowZero = true)
                    }
                }

                override fun onEvents(player: Player, events: Player.Events) {
                    if (!events.containsAny(Player.EVENT_IS_PLAYING_CHANGED, Player.EVENT_PLAYBACK_STATE_CHANGED,
                            Player.EVENT_PLAY_WHEN_READY_CHANGED, Player.EVENT_PLAYBACK_PARAMETERS_CHANGED,
                            Player.EVENT_POSITION_DISCONTINUITY, Player.EVENT_TIMELINE_CHANGED,
                            Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_PLAYER_ERROR,
                            Player.EVENT_AVAILABLE_COMMANDS_CHANGED)) return
                    publishPlayback(player)
                    if (player.playbackState == Player.STATE_ENDED ||
                        (!player.isPlaying && (events.contains(Player.EVENT_IS_PLAYING_CHANGED) ||
                            (!player.playWhenReady && events.contains(Player.EVENT_PLAY_WHEN_READY_CHANGED)) ||
                            (player.playbackState == Player.STATE_IDLE && events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED))))) {
                        checkpoint()
                    }
                    updateTicker()
                }
            })
        }
        player = exo
        publishPlayback(exo)
    }

    fun requirePlayer(context: Context): ExoPlayer {
        init(context)
        return player!!
    }

    val currentPlayer: ExoPlayer? get() = player

    /**
     * 播放网络电台直播流。
     * 直播没有总时长，进度不可 seek；与点播共用同一个 ExoPlayer。
     */
    fun playStation(context: Context, station: StationDto) {
        init(context)
        val exo = player ?: return
        ensureServiceRunning(context)
        val item = MediaItem.Builder()
            .setMediaId("station:" + station.id)
            .setUri(station.streamUrl)
            .setMimeType(if (station.isHls) androidx.media3.common.MimeTypes.APPLICATION_M3U8 else null)
            .setLiveConfiguration(
                androidx.media3.common.MediaItem.LiveConfiguration.Builder()
                    .setTargetOffsetMs(5_000)
                    .build(),
            )
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(station.name)
                    .setArtist(if (station.genre.isBlank()) "直播电台" else station.genre)
                    .setArtworkUri(
                        station.favicon.takeIf { it.isNotBlank() }?.let { android.net.Uri.parse(it) },
                    )
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .build(),
            )
            .build()
        checkpoint()
        clearStorageStream()
        OfflinePodcastCache.get(context).clearPlaybackFiles()
        _queue.value = emptyList()
        _isLive.value = true
        _lastError.value = null
        exo.setPlaybackSpeed(1f)
        exo.setMediaItem(item, 0L)
        exo.prepare()
        _nowPlaying.value = NowPlaying(
            episode = null,
            podcastTitle = station.name,
            artworkUrl = station.favicon,
            station = station,
        )
        exo.playWhenReady = true
    }

    /** 文件是点播，只复用 Media3 和媒体服务，不读取或写入播客进度。 */
    @OptIn(UnstableApi::class)
    internal suspend fun playFile(context: Context, file: FileMediaPlayback) {
        currentCoroutineContext().ensureActive()
        init(context)
        val exo = player ?: return
        val itemId = FILE_MEDIA_PREFIX + UUID.randomUUID().toString()
        val mediaUri = Uri.parse(itemId)
        val factory = FileStreamFactory(file.url, mediaUri)
        val title = file.title.ifBlank { "未命名文件" }
        val item = MediaItem.Builder()
            .setMediaId(itemId)
            .setUri(mediaUri)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist("文件音源")
                .setIsBrowsable(false).setIsPlayable(true).build())
            .build()
        val source = ProgressiveMediaSource.Factory(factory).createMediaSource(item)

        ensureServiceRunning(context)
        checkpoint()
        clearFilePlayback()
        OfflinePodcastCache.get(context).clearPlaybackFiles()
        _queue.value = emptyList()
        _isLive.value = false
        _lastError.value = null
        exo.setPlaybackSpeed(podcastSpeed)
        exo.setMediaSource(source, 0L)
        fileStreamFactory = factory
        _nowPlaying.value = NowPlaying(podcastTitle = "文件音源", file = NowPlayingFile(itemId, title))
        exo.prepare()
        exo.play()
    }

    /**
     * 播放网络存储中的音频。真实 URL、认证头与 TLS 材料只留在 [StorageStreamFactory] 内部，
     * MediaItem / MediaSession 只持有 storage: 占位 URI；与文件音源一样不写播客进度。
     */
    @OptIn(UnstableApi::class)
    internal fun playStorage(
        context: Context,
        media: StorageMedia,
        credentials: SourceCredentials,
        transport: SourceTransport,
    ) {
        playStorageStream(context, media.title, media.mime, "网络存储") { uri ->
            StorageStreamFactory(media.url, uri, credentials, transport)
        }
    }

    @OptIn(UnstableApi::class)
    internal fun playOneDrive(context: Context, name: String, mime: String?, downloads: OneDriveDownloads) {
        playStorageStream(context, name, mime, "OneDrive") { uri -> OneDrivePlaybackFactory(downloads, uri) }
    }

    @OptIn(UnstableApi::class)
    internal fun playSmb(context: Context, sourceId: String, name: String, mime: String?, client: SmbClient, path: String) {
        playStorageStream(context, name, mime, "SMB") { uri -> client.playbackFactory(sourceId, uri, path) }
    }

    internal fun stopSmb(sourceId: String) {
        if ((storageStreamFactory as? SmbPlaybackFactory)?.sourceId != sourceId) return
        clearFilePlayback()
        player?.clearMediaItems()
    }

    internal fun stopOneDrive() {
        if (storageStreamFactory !is OneDrivePlaybackFactory) return
        player?.clearMediaItems()
        clearFilePlayback()
    }

    @OptIn(UnstableApi::class)
    private fun playStorageStream(
        context: Context, name: String, mime: String?, sourceName: String,
        makeFactory: (Uri) -> StoragePlaybackFactory,
    ) {
        init(context)
        val exo = player ?: return
        val itemId = STORAGE_MEDIA_PREFIX + UUID.randomUUID().toString()
        val mediaUri = Uri.parse(itemId)
        val factory = makeFactory(mediaUri)
        val title = name.ifBlank { "未命名音源" }
        val item = MediaItem.Builder()
            .setMediaId(itemId)
            .setUri(mediaUri)
            .setMimeType(mime?.takeIf { it.isNotBlank() && it.startsWith("audio/", ignoreCase = true) })
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist(sourceName)
                .setIsBrowsable(false).setIsPlayable(true).build())
            .build()
        val source = ProgressiveMediaSource.Factory(factory).createMediaSource(item)

        ensureServiceRunning(context)
        checkpoint()
        clearFilePlayback()
        OfflinePodcastCache.get(context).clearPlaybackFiles()
        _queue.value = emptyList()
        _isLive.value = false
        _lastError.value = null
        exo.setPlaybackSpeed(podcastSpeed)
        exo.setMediaSource(source, 0L)
        storageStreamFactory = factory
        _nowPlaying.value = NowPlaying(podcastTitle = sourceName, file = NowPlayingFile(itemId, title))
        exo.prepare()
        exo.play()
    }

    private fun clearFilePlayback() {
        fileStreamFactory?.cancel()
        fileStreamFactory = null
        clearStorageStream()
        if (_nowPlaying.value.file != null) _nowPlaying.value = NowPlaying()
    }

    /** 只取消网络存储流，不改动当前 UI 状态；切换来源时由新的播放流程重建信息。 */
    private fun clearStorageStream() {
        storageStreamFactory?.cancel()
        storageStreamFactory = null
    }

    /** 当前播放的是否为直播流（直播不显示进度条）。 */
    private val _isLive = MutableStateFlow(false)
    val isLive: StateFlow<Boolean> = _isLive.asStateFlow()

    /** 播放单集；同一集已在播放则保持进度。 */
    fun play(context: Context, episode: EpisodeDto, podcastTitle: String, artworkUrl: String, queue: List<EpisodeDto> = emptyList()) {
        init(context)
        val exo = player ?: return
        ensureServiceRunning(context)
        _isLive.value = false
        _lastError.value = null
        clearStorageStream()
        val items = queue.ifEmpty { listOf(episode) }
        if (exo.currentMediaItem?.mediaId != episode.id ||
            _nowPlaying.value.episode?.audioUrl != episode.audioUrl) {
            checkpoint()
            _queue.value = if (items.any { it.id == episode.id }) items else listOf(episode)
            val index = _queue.value.indexOfFirst { it.id == episode.id }
            val feedById = LocalPodcastLibrary.get(context).feeds.value.associateBy { it.id }
            val local = OfflinePodcastCache.get(context).selectPlaybackFiles(_queue.value) { feedById[it.podcastId] }
            val mediaItems = _queue.value.mapIndexed { i, entry ->
                entry.toMediaItem(local[i]?.let { Uri.fromFile(it).toString() } ?: entry.audioUrl)
            }
            exo.setMediaItems(mediaItems, index, 0L)
        }
        _nowPlaying.value = NowPlaying(episode = episode, podcastTitle = podcastTitle, artworkUrl = artworkUrl)
        exo.setPlaybackSpeed(podcastSpeed)
        startCurrentItem(exo)
    }

    /**
     * 只在真正开始播放时启动前台服务，避免 Android 8+ 的
     * "did not then call Service.startForeground()" 崩溃，也避免 Android 12+ 的后台启动限制。
     */
    fun ensureServiceRunning(context: Context) {
        val ctx = appContext ?: context.applicationContext
        _notificationWarning.value = !NotificationPermission.isGranted(ctx)
        runCatching { PlaybackService.start(ctx) }
    }

    fun setSpeed(speed: Float) {
        podcastSpeed = speed.coerceIn(0.5f, 3.0f)
        if (!_isLive.value) player?.setPlaybackSpeed(podcastSpeed)
    }

    fun skipForward(ms: Long) {
        player?.let { seekTo(it.currentPosition + ms) }
    }

    fun skipBack(ms: Long) {
        player?.let { seekTo(it.currentPosition - ms) }
    }

    fun togglePlayPause() {
        val p = player ?: return
        if (p.mediaItemCount == 0) return
        if (playbackToggle(p.playbackState, p.playWhenReady) == PlaybackToggle.PAUSE) p.pause()
        else startCurrentItem(p)
    }

    private fun startCurrentItem(p: Player) {
        when (playbackToggle(p.playbackState, p.playWhenReady)) {
            PlaybackToggle.PREPARE_AND_PLAY -> {
                _lastError.value = null
                p.prepare() // Media3 stays idle after a fatal error; play() alone only sets intent.
            }
            PlaybackToggle.RESTART_AND_PLAY -> p.seekToDefaultPosition()
            else -> Unit
        }
        p.play()
    }

    fun seekTo(ms: Long) {
        val p = player ?: return
        if (!canSeek(p)) return
        p.seekTo(ms.coerceIn(0, p.duration))
    }

    private fun canSeek(p: Player): Boolean =
        !isLiveMedia(p) && p.duration > 0 && p.isCurrentMediaItemSeekable &&
            p.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)

    fun pause() {
        player?.pause()
    }

    fun stop() {
        checkpoint()
        player?.stop()
    }

    fun isPlaying(): Boolean = player?.isPlaying == true

    fun position(): Long = player?.currentPosition ?: 0L

    fun duration(): Long = player?.duration?.takeIf { it > 0 } ?: 0L

    fun clearError() {
        _lastError.value = null
    }

    private fun publishPlayback(p: Player) {
        val id = p.currentMediaItem?.mediaId
        val live = isLiveMedia(p)
        val duration = if (live) 0L else p.duration.coerceAtLeast(0)
        if (id != durationMediaId || duration > 0 || live) {
            durationMediaId = id
            knownDurationMs = duration
        }
        _playback.value = PlaybackSnapshot(
            mediaId = id,
            playing = p.isPlaying,
            isLive = live,
            positionMs = if (live) 0L else p.currentPosition.coerceAtLeast(0),
            durationMs = duration,
            speed = if (live) 1f else p.playbackParameters.speed,
            buffering = p.playbackState == Player.STATE_BUFFERING,
            willPause = id != null && playbackToggle(p.playbackState, p.playWhenReady) == PlaybackToggle.PAUSE,
            canSeek = canSeek(p),
        )
    }

    private fun isLiveMedia(p: Player): Boolean =
        p.currentMediaItem?.mediaId?.startsWith("station:") == true ||
            (!p.currentMediaItem?.mediaId.isFileOrStorageId() && p.isCurrentMediaItemLive)

    private fun isPlayingOnDemand(p: Player?): Boolean =
        p != null && p.isPlaying && p.currentMediaItem != null && !isLiveMedia(p)

    // 文件点播只在 UI 可见时刷新进度条；不为它安排本地检查点。
    private fun updateTicker(restart: Boolean = false) {
        val fileLike = player?.currentMediaItem?.mediaId.isFileOrStorageId() == true
        if (!isPlayingOnDemand(player) || (fileLike && !hasUi)) {
            ticker?.cancel()
            ticker = null
            syncAtMs = null
            return
        }
        if (!restart && ticker?.isActive == true && fileLike == (syncAtMs == null)) return
        ticker?.cancel()
        syncAtMs = if (fileLike) null else syncAtMs ?: SystemClock.elapsedRealtime() + PROGRESS_SYNC_MS
        ticker = scope.launch {
            while (true) {
                val p = player ?: break
                val waitMs = if (fileLike) UI_TICK_MS else {
                    val remaining = (syncAtMs ?: break) - SystemClock.elapsedRealtime()
                    playbackTickDelayMs(isPlayingOnDemand(p), hasUi, remaining) ?: break
                }
                delay(waitMs)
                val stillFileLike = p.currentMediaItem?.mediaId.isFileOrStorageId() == true
                if (!isPlayingOnDemand(p) || p !== player || stillFileLike != fileLike) break
                if (hasUi) publishPlayback(p)
                val syncAt = syncAtMs ?: continue
                val now = SystemClock.elapsedRealtime()
                if (now >= syncAt) {
                    syncAtMs = now + PROGRESS_SYNC_MS
                    checkpoint()
                }
            }
        }
    }

    /** Activity 离开、暂停/停止等机会保存；强杀时依靠最近一次周期检查点。 */
    fun checkpoint() = checkpoint(allowZero = false)

    private fun checkpoint(allowZero: Boolean) {
        val p = player ?: return
        if (isLiveMedia(p)) return
        val id = p.currentMediaItem?.mediaId
        val duration = p.duration.takeIf { it > 0 } ?: knownDurationMs.takeIf { id == durationMediaId } ?: return
        val position = p.currentPosition
        val completed = p.playbackState == Player.STATE_ENDED
        if (position <= 0 && !allowZero && !completed) return
        val snapshot = progressCheckpoint(id, position, duration, completed) ?: return
        save(snapshot, explicitSeek = allowZero)
    }

    /** 进度只写入本机 LocalPodcastLibrary，不经过任何自建后端或离线同步队列。 */
    private fun save(snapshot: ProgressCheckpoint?, explicitSeek: Boolean = false) {
        if (snapshot == null || !shouldReplaceCheckpoint(lastCheckpoint, snapshot, explicitSeek)) return
        val ctx = appContext ?: return
        lastCheckpoint = snapshot
        runCatching {
            LocalPodcastLibrary.get(ctx).recordProgress(
                snapshot.episodeId, snapshot.positionMs, snapshot.durationMs, snapshot.completed,
            )
        }
    }

    fun release() {
        checkpoint()
        clearFilePlayback()
        ticker?.cancel()
        ticker = null
        syncAtMs = null
        player?.release()
        player = null
        appContext?.let { OfflinePodcastCache.get(it).clearPlaybackFiles() }
        _playback.value = PlaybackSnapshot()
    }
}

private fun EpisodeDto.toMediaItem(playbackUrl: String): MediaItem {
    val metadata = MediaMetadata.Builder()
        .setTitle(title)
        .setArtist(podcastId)
        .setArtworkUri(if (imageUrl.isNotBlank()) android.net.Uri.parse(imageUrl) else null)
        .build()
    return MediaItem.Builder()
        .setMediaId(id)
        .setUri(playbackUrl)
        .setMediaMetadata(metadata)
        .build()
}
