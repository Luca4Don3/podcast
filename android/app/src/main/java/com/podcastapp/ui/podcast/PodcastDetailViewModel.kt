package com.podcastapp.ui.podcast

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.podcastapp.data.feed.LocalFeed
import com.podcastapp.data.feed.LocalPodcastLibrary
import com.podcastapp.data.remote.EpisodeDto
import com.podcastapp.data.remote.PodcastDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PodcastDetailUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val podcast: PodcastDto? = null,
    val episodes: List<EpisodeDto> = emptyList(),
    val subscribed: Boolean = false,
)

/** 节目详情只读本地公开 feed 库：不访问 ServiceLocator，也不访问后端仓库。 */
class PodcastDetailViewModel(app: Application) : AndroidViewModel(app) {

    private val library = LocalPodcastLibrary.get(app.applicationContext)

    private val _state = MutableStateFlow(PodcastDetailUiState())
    val state: StateFlow<PodcastDetailUiState> = _state.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** 保留已加载的本地订阅，取消订阅后仍可用其 sourceUrl 重新导入。 */
    private var loadedFeed: LocalFeed? = null

    fun consumeMessage() {
        _message.value = null
    }

    fun load(podcastId: String, forceRefresh: Boolean = false) {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            try {
                val cached = library.feeds.value.firstOrNull {
                    it.id == podcastId || it.podcast.id == podcastId
                }
                val feed = cached ?: loadedFeed?.takeIf {
                    it.id == podcastId || it.podcast.id == podcastId
                }
                if (feed == null) {
                    _state.value = PodcastDetailUiState(loading = false, error = "本地没有该节目的公开 feed 订阅")
                    return@launch
                }
                val resolved = if (forceRefresh) library.refresh(feed.id).getOrElse { feed } else feed
                loadedFeed = resolved
                _state.value = PodcastDetailUiState(
                    loading = false,
                    podcast = resolved.podcast,
                    episodes = resolved.episodes,
                    subscribed = library.feeds.value.any { it.id == resolved.id },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(loading = false, error = e.message ?: "加载失败")
            }
        }
    }

    /** 已订阅则移除；已移除则用保存的 sourceUrl 重新导入同一公开 feed。 */
    fun toggleSubscribe() {
        val feed = loadedFeed ?: return
        viewModelScope.launch {
            val currentlySubscribed = library.feeds.value.any { it.id == feed.id }
            runCatching {
                if (currentlySubscribed) library.remove(feed.id) else library.`import`(feed.sourceUrl)
            }.onSuccess {
                val next = library.feeds.value.any { it.id == feed.id }
                _state.value = _state.value.copy(subscribed = next)
                _message.value = if (next) "已重新订阅" else "已取消订阅"
            }.onFailure { error ->
                _message.value = error.message ?: "操作失败"
            }
        }
    }

    fun toggleQueued(episode: EpisodeDto) {
        viewModelScope.launch {
            val next = episode.id !in library.state.value.queued
            runCatching { library.setQueued(episode.id, next) }
                .onSuccess { _message.value = if (next) "已加入待播" else "已移出待播" }
                .onFailure { error -> _message.value = error.message ?: "操作失败" }
        }
    }

    fun toggleStarred(episode: EpisodeDto) {
        viewModelScope.launch {
            val next = episode.id !in library.state.value.starred
            runCatching { library.setStarred(episode.id, next) }
                .onSuccess { _message.value = if (next) "已收藏" else "已取消收藏" }
                .onFailure { error -> _message.value = error.message ?: "操作失败" }
        }
    }
}
