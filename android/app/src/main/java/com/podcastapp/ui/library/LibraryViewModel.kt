package com.podcastapp.ui.library

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.podcastapp.data.feed.LocalPodcastLibrary
import com.podcastapp.data.remote.EpisodeDto
import com.podcastapp.data.remote.PodcastDto
import com.podcastapp.data.remote.ProgressDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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

/** 只保留错误提示与待播操作的进行中状态；业务数据全部来自本地公开 feed 库。 */
private data class LibraryActions(
    val actionError: String? = null,
    val pendingQueueIds: Set<String> = emptySet(),
)

class LibraryViewModel(app: Application) : AndroidViewModel(app) {

    private val library = LocalPodcastLibrary.get(app.applicationContext)
    private val actions = MutableStateFlow(LibraryActions())
    private var failedAction: (() -> Unit)? = null

    val state: StateFlow<LibraryUiState> = combine(
        combine(library.feeds, library.state) { feeds, local -> feeds to local },
        actions,
    ) { snapshot, current ->
        val feeds = snapshot.first
        val local = snapshot.second
        val episodes = feeds.flatMap { it.episodes }
        val episodesById = episodes.associateBy { it.id }
        LibraryUiState(
            loading = false,
            loaded = true,
            subscriptions = feeds.map { it.podcast },
            starred = episodes.filter { it.id in local.starred },
            queued = episodes.filter { it.id in local.queued },
            recentlyPlayed = local.progress.values
                .asSequence()
                .filter { !it.completed && it.positionMs > 0 }
                .mapNotNull { p ->
                    episodesById[p.episodeId]?.let { episode ->
                        RecentItem(
                            ProgressDto(
                                episodeId = p.episodeId,
                                positionMs = p.positionMs,
                                durationMs = p.durationMs,
                                completed = p.completed,
                                updatedAt = p.updatedAt.toString(),
                            ),
                            episode,
                        )
                    }
                }
                .toList()
                .sortedByDescending { it.progress.updatedAt.toLongOrNull() ?: 0L },
            actionError = current.actionError,
            pendingQueueIds = current.pendingQueueIds,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, LibraryUiState())

    /** 订阅与进度由本地库的 Flow 实时驱动，无需主动刷新；保留以兼容既有调用。 */
    fun refresh() = Unit

    fun retryAction() {
        failedAction?.invoke()
    }

    fun dismissActionError() {
        failedAction = null
        actions.value = actions.value.copy(actionError = null)
    }

    fun unsubscribe(podcastId: String) {
        viewModelScope.launch {
            try {
                library.feeds.value
                    .firstOrNull { it.id == podcastId || it.podcast.id == podcastId }
                    ?.let { library.remove(it.id) }
                actions.value = actions.value.copy(actionError = null)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                failedAction = { unsubscribe(podcastId) }
                actions.value = actions.value.copy(actionError = "取消订阅失败，请重试。")
            }
        }
    }

    fun setQueued(episodeId: String, queued: Boolean) {
        if (episodeId in actions.value.pendingQueueIds) return
        actions.value = actions.value.copy(pendingQueueIds = actions.value.pendingQueueIds + episodeId)
        viewModelScope.launch {
            try {
                library.setQueued(episodeId, queued)
                actions.value = actions.value.copy(actionError = null)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                failedAction = { setQueued(episodeId, queued) }
                actions.value = actions.value.copy(actionError = "更新待播失败，请重试。")
            } finally {
                actions.value = actions.value.copy(pendingQueueIds = actions.value.pendingQueueIds - episodeId)
            }
        }
    }

    fun setStarred(episodeId: String, starred: Boolean) {
        viewModelScope.launch {
            try {
                library.setStarred(episodeId, starred)
                actions.value = actions.value.copy(actionError = null)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                failedAction = { setStarred(episodeId, starred) }
                actions.value = actions.value.copy(actionError = "更新收藏失败，请重试。")
            }
        }
    }
}
