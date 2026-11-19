package com.podcastapp.ui.discover

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.podcastapp.data.feed.FeedPlatform
import com.podcastapp.data.feed.LocalFeed
import com.podcastapp.data.feed.LocalPodcastLibrary
import com.podcastapp.data.feed.PlatformFeedAdapter
import com.podcastapp.data.remote.EpisodeDto
import com.podcastapp.playback.PlaybackHolder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PlatformImportUiState(
    val loading: Boolean = false,
    val input: String = "",
    val platformHint: String = "",
    val error: String? = null,
    val message: String? = null,
    val feeds: List<LocalFeed> = emptyList(),
)

/** 平台分享链接导入：只依赖本地公开 feed 库，不访问自建后端。 */
class PlatformImportViewModel(app: Application) : AndroidViewModel(app) {
    private val library = LocalPodcastLibrary.get(app.applicationContext)
    private val _state = MutableStateFlow(PlatformImportUiState())
    val state: StateFlow<PlatformImportUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            library.feeds.collect { feeds -> _state.value = _state.value.copy(feeds = feeds) }
        }
    }

    internal fun acceptShared(text: String) {
        val url = extractSharedUrl(text)
        if (url == null) {
            _state.value = _state.value.copy(error = "未在分享内容中找到有效的播客链接", message = null)
            return
        }
        setInput(url)
        `import`()
    }

    fun setInput(value: String) {
        _state.value = _state.value.copy(
            input = value,
            platformHint = platformHint(value),
            error = null,
            message = null,
        )
    }

    fun `import`() {
        if (_state.value.loading) return
        val input = _state.value.input
        _state.value = _state.value.copy(loading = true, error = null, message = null)
        viewModelScope.launch {
            try {
                val imported = library.`import`(input).getOrThrow()
                _state.value = _state.value.copy(
                    loading = false,
                    input = "",
                    platformHint = "",
                    message = "已导入并订阅 ${imported.size} 个节目",
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.value = _state.value.copy(
                    loading = false,
                    error = error.message?.takeIf { it.isNotBlank() } ?: "导入失败，请检查链接与网络后重试。",
                )
            }
        }
    }

    fun remove(id: String) {
        viewModelScope.launch {
            try {
                library.remove(id)
                _state.value = _state.value.copy(message = "已移除订阅", error = null)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                _state.value = _state.value.copy(error = "移除订阅失败，请重试。")
            }
        }
    }

    fun refresh(id: String) {
        viewModelScope.launch {
            try {
                val updated = library.refresh(id).getOrThrow()
                _state.value = _state.value.copy(message = "已刷新「${updated.podcast.title}」", error = null)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.value = _state.value.copy(
                    error = error.message?.takeIf { it.isNotBlank() } ?: "刷新失败，请稍后重试。",
                )
            }
        }
    }

    fun play(feed: LocalFeed, episode: EpisodeDto) {
        runCatching {
            PlaybackHolder.play(
                context = getApplication(),
                episode = episode,
                podcastTitle = feed.podcast.title,
                artworkUrl = feed.podcast.imageUrl,
                queue = feed.episodes,
            )
        }.onFailure { error ->
            _state.value = _state.value.copy(error = "播放失败：${error.message.orEmpty()}")
        }
    }

    private fun platformHint(input: String): String {
        if (input.isBlank()) return ""
        val platform = PlatformFeedAdapter.normalize(input).getOrNull()?.platform ?: return ""
        return when (platform) {
            FeedPlatform.APPLE -> "已识别 Apple 播客：通过官方接口查询公开 RSS"
            FeedPlatform.SPOTIFY -> "已识别 Spotify：需要节目公开 RSS 地址"
            FeedPlatform.XIAOYUZHOU -> "已识别小宇宙：需要节目公开 RSS 地址"
            FeedPlatform.XIMALAYA -> "已识别喜马拉雅：需要节目公开 RSS 地址"
            FeedPlatform.DIRECT -> "已识别公开 RSS / OPML 地址"
        }
    }
}

internal fun extractSharedUrl(text: String): String? {
    val value = text.trim()
    if (value.isEmpty() || value.any { it.isISOControl() }) return null
    val match = Regex("https?://\\S+").find(value)?.value ?: value
    return runCatching { PlatformFeedAdapter.normalize(match).getOrThrow().url }.getOrNull()
}
