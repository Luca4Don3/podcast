package com.podcastapp.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.podcastapp.data.ServiceLocator
import com.podcastapp.data.remote.EpisodeDto
import com.podcastapp.data.remote.PodcastDto
import com.podcastapp.data.remote.ProgressDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 继续收听条目：进度 + 单集详情。 */
data class RecentItem(val progress: ProgressDto, val episode: EpisodeDto)

data class LibraryUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val loaded: Boolean = false,
    val actionError: String? = null,
    val pendingQueueIds: Set<String> = emptySet(),
    val subscriptions: List<PodcastDto> = emptyList(),
    val recentlyPlayed: List<RecentItem> = emptyList(),
    val starred: List<EpisodeDto> = emptyList(),
    val queued: List<EpisodeDto> = emptyList(),
)

class LibraryViewModel : ViewModel() {

    private val repo = ServiceLocator.libraryRepository
    private val _state = MutableStateFlow(LibraryUiState())
    val state: StateFlow<LibraryUiState> = _state.asStateFlow()

    private val requests = Mutex()
    private var refreshing = false
    private var failedAction: (() -> Unit)? = null

    fun refresh() {
        if (refreshing) return
        refreshing = true
        viewModelScope.launch {
            try {
                requests.withLock { load() }
            } finally {
                refreshing = false
            }
        }
    }

    private suspend fun load() {
        _state.value = _state.value.copy(loading = true, error = null)
        try {
            coroutineScope {
                val subs = async { repo.subscriptions() }
                val recent = async { repo.recentlyPlayed() }
                val starred = async { repo.starred() }
                val queued = async { repo.queued() }
                val (progress, episodes) = recent.await()
                val byId = episodes.associateBy { it.id }
                val subscriptions = subs.await()
                val favorites = starred.await()
                val queue = queued.await()
                _state.value = _state.value.copy(
                    loaded = true,
                    subscriptions = subscriptions,
                    recentlyPlayed = progress.filter { !it.completed && it.positionMs > 0 }
                        .mapNotNull { p -> byId[p.episodeId]?.let { RecentItem(p, it) } },
                    starred = favorites,
                    queued = queue,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            _state.value = _state.value.copy(error = "无法更新资料库，请检查连接后重试。")
        } finally {
            _state.value = _state.value.copy(loading = false)
        }
    }

    fun retryAction() { failedAction?.invoke() }

    fun dismissActionError() {
        failedAction = null
        _state.value = _state.value.copy(actionError = null)
    }

    fun unsubscribe(podcastId: String): Unit = mutate("取消订阅失败", { unsubscribe(podcastId) }) {
        repo.unsubscribe(podcastId)
        _state.value = _state.value.copy(subscriptions = _state.value.subscriptions.filterNot { it.id == podcastId })
    }

    fun setQueued(episodeId: String, queued: Boolean) {
        if (episodeId in _state.value.pendingQueueIds) return
        _state.value = _state.value.copy(pendingQueueIds = _state.value.pendingQueueIds + episodeId)
        mutate("更新待播失败", { setQueued(episodeId, queued) }, episodeId) {
            repo.setQueued(episodeId, queued)
            if (!queued) _state.value = _state.value.copy(queued = _state.value.queued.filterNot { it.id == episodeId })
        }
    }

    fun setStarred(episodeId: String, starred: Boolean): Unit = mutate("更新收藏失败", { setStarred(episodeId, starred) }) {
        repo.setStarred(episodeId, starred)
        if (!starred) _state.value = _state.value.copy(starred = _state.value.starred.filterNot { it.id == episodeId })
    }

    private fun mutate(label: String, retry: () -> Unit, queueId: String? = null, change: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                requests.withLock {
                    dismissActionError()
                    try {
                        change()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        failedAction = retry
                        _state.value = _state.value.copy(actionError = "$label，请重试。")
                        return@withLock
                    }
                    load()
                }
            } finally {
                if (queueId != null) _state.value = _state.value.copy(pendingQueueIds = _state.value.pendingQueueIds - queueId)
            }
        }
    }
}
