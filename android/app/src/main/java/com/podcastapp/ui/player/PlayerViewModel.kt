package com.podcastapp.ui.player

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.podcastapp.data.ServiceLocator
import com.podcastapp.data.remote.SettingsDto
import com.podcastapp.playback.PlaybackHolder
import com.podcastapp.playback.NowPlaying
import com.podcastapp.playback.SleepTimer
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PlayerUiState(
    val playing: Boolean = false,
    val isLive: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val speed: Float = 1f,
    val title: String = "",
    val podcastTitle: String = "",
    val artworkUrl: String = "",
    val error: String? = null,
    val sleepTimerSeconds: Long = 0,
    val notificationBlocked: Boolean = false,
    val mediaId: String? = null,
    val buffering: Boolean = false,
    val willPause: Boolean = playing,
    val canSeek: Boolean = false,
)

/** Pure mapping used by the UI and regression tests; stations have no podcast progress. */
fun PlayerUiState.withNowPlaying(now: NowPlaying, resetPosition: Boolean = true): PlayerUiState {
    val station = now.station
    val live = station != null
    return copy(
        mediaId = station?.let { "station:" + it.id } ?: now.file?.id ?: now.episode?.id,
        isLive = live,
        title = station?.name?.ifBlank { "我的音源" } ?: now.file?.title ?: now.episode?.title.orEmpty(),
        podcastTitle = if (live) "直播音源" else now.podcastTitle,
        artworkUrl = station?.favicon ?: now.artworkUrl.ifBlank { now.episode?.imageUrl.orEmpty() },
        positionMs = if (live || resetPosition) 0 else positionMs,
        durationMs = if (live || resetPosition) 0 else durationMs,
        speed = if (live) 1f else speed,
        error = null,
        buffering = false,
        willPause = false,
        canSeek = false,
    )
}

/** UI 只在有订阅时观察播放器；后台续播和进度保存由 PlaybackHolder 负责。 */
class PlayerViewModel(app: Application) : AndroidViewModel(app) {

    private val library = ServiceLocator.libraryRepository
    private val holder = PlaybackHolder
    private var settings: SettingsDto = SettingsDto()

    val state: StateFlow<PlayerUiState> = combine(
        holder.nowPlaying, holder.playback, holder.lastError,
        holder.notificationWarning, SleepTimer.remainingSeconds,
    ) { now, playback, error, notificationBlocked, remaining ->
        val presentation = PlayerUiState().withNowPlaying(now)
        val matches = presentation.mediaId != null && playback.mediaId == presentation.mediaId
        val live = presentation.isLive || (matches && playback.isLive)
        presentation.copy(
            playing = matches && playback.playing,
            isLive = live,
            positionMs = if (matches && !live) playback.positionMs else 0,
            durationMs = if (matches && !live) playback.durationMs else 0,
            speed = if (live) 1f else playback.speed,
            error = error.takeIf { matches },
            buffering = matches && playback.buffering,
            willPause = matches && playback.willPause,
            canSeek = matches && playback.canSeek && !live,
            sleepTimerSeconds = remaining,
            notificationBlocked = notificationBlocked,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), PlayerUiState())

    init {
        viewModelScope.launch {
            runCatching { library.settings() }.onSuccess {
                settings = it
                holder.setSpeed(it.playbackSpeed.toFloat())
            }
        }
        SleepTimer.onExpire = holder::pause
    }

    fun togglePlayPause() = holder.togglePlayPause()

    fun seek(ms: Long) = holder.seekTo(ms)

    fun skipForward() = holder.skipForward(settings.skipForward * 1000L)

    fun skipBack() = holder.skipBack(settings.skipBack * 1000L)

    fun setSpeed(speed: Float) {
        if (holder.isLive.value) return
        val clamped = speed.coerceIn(0.5f, 3.0f)
        holder.setSpeed(clamped)
        viewModelScope.launch {
            settings = settings.copy(playbackSpeed = clamped.toDouble())
            runCatching { library.saveSettings(settings) }
        }
    }

    /** 睡眠定时器：分钟数，0 表示取消。 */
    fun setSleepTimer(minutes: Int) {
        if (minutes <= 0) SleepTimer.cancel() else SleepTimer.start(minutes)
    }

    fun clearError() = holder.clearError()
}
