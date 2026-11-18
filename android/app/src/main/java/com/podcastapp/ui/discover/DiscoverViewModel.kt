package com.podcastapp.ui.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.podcastapp.data.HttpSourceUrl
import com.podcastapp.data.ServiceLocator
import com.podcastapp.data.remote.PodcastDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class DiscoverUiState(
    val loading: Boolean = false,
    val inputError: String? = null,
    val importError: String? = null,
    val feedUrl: String = "",
    val addedPodcast: PodcastDto? = null,
)

/** The default discovery page is an RSS import space, not a global directory. */
class DiscoverViewModel : ViewModel() {
    private val podcasts = ServiceLocator.podcastRepository
    private val _state = MutableStateFlow(DiscoverUiState())
    val state: StateFlow<DiscoverUiState> = _state.asStateFlow()
    fun setFeedUrl(value: String) {
        _state.value = _state.value.copy(feedUrl = value, inputError = null, importError = null, addedPodcast = null)
    }

    fun addFeed() {
        if (_state.value.loading) return
        val url = try {
            HttpSourceUrl.parse(_state.value.feedUrl)
        } catch (error: IllegalArgumentException) {
            _state.value = _state.value.copy(inputError = error.message ?: "RSS 地址格式无效")
            return
        }
        _state.value = _state.value.copy(loading = true, inputError = null, importError = null, addedPodcast = null)
        viewModelScope.launch {
            try {
                val added = podcasts.addByFeedUrl(url)
                _state.value = _state.value.copy(addedPodcast = added, feedUrl = "")
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                _state.value = _state.value.copy(importError = "导入失败。地址已保留，请检查地址与网络后重试。")
            } finally {
                _state.value = _state.value.copy(loading = false)
            }
        }
    }
}
