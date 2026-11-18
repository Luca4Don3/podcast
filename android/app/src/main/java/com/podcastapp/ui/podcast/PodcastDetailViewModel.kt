package com.podcastapp.ui.podcast

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.podcastapp.data.ServiceLocator
import com.podcastapp.data.remote.EpisodeDto
import com.podcastapp.data.remote.PodcastDto
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

class PodcastDetailViewModel : ViewModel() {

    private val podcasts = ServiceLocator.podcastRepository
    private val library = ServiceLocator.libraryRepository

    private val _state = MutableStateFlow(PodcastDetailUiState())
    val state: StateFlow<PodcastDetailUiState> = _state.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun consumeMessage() {
        _message.value = null
    }

    fun load(podcastId: String, forceRefresh: Boolean = false) {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            try {
                var detail = podcasts.podcast(podcastId)
                if (forceRefresh) {
                    detail = runCatching { podcasts.refresh(podcastId) }.getOrDefault(detail)
                }
                val episodes = podcasts.episodes(podcastId)
                _state.value = PodcastDetailUiState(
                    loading = false,
                    podcast = detail,
                    episodes = episodes,
                    subscribed = detail.subscribed,
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(loading = false, error = e.message)
            }
        }
    }

    fun toggleSubscribe() {
        val podcast = _state.value.podcast ?: return
        viewModelScope.launch {
            val next = !_state.value.subscribed
            runCatching {
                if (next) library.subscribe(podcast.id) else library.unsubscribe(podcast.id)
            }.onSuccess {
                _state.value = _state.value.copy(subscribed = next)
                _message.value = if (next) "已订阅" else "已取消订阅"
            }.onFailure { _message.value = it.message }
        }
    }

    fun toggleQueued(episode: EpisodeDto) {
        viewModelScope.launch {
            runCatching { library.setQueued(episode.id, true) }
                .onSuccess { _message.value = "已加入待播" }
                .onFailure { _message.value = it.message }
        }
    }

    fun toggleStarred(episode: EpisodeDto) {
        viewModelScope.launch {
            runCatching { library.setStarred(episode.id, true) }
                .onSuccess { _message.value = "已收藏" }
                .onFailure { _message.value = it.message }
        }
    }
}
