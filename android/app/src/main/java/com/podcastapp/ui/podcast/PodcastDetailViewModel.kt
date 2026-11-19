package com.podcastapp.ui.podcast

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.podcastapp.data.feed.LocalFeed
import com.podcastapp.data.feed.LocalPodcastLibrary
import com.podcastapp.data.feed.OfflinePodcastCache
import com.podcastapp.data.feed.OfflinePolicy
import com.podcastapp.data.feed.OfflineProgress
import com.podcastapp.data.feed.refreshFeedBeforeCache
import com.podcastapp.data.remote.EpisodeDto
import com.podcastapp.data.remote.PodcastDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class PodcastDetailUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val podcast: PodcastDto? = null,
    val episodes: List<EpisodeDto> = emptyList(),
    val subscribed: Boolean = false,
    val policy: OfflinePolicy = OfflinePolicy.NONE,
    val busy: Boolean = false,
    val cancellable: Boolean = false,
    val progress: OfflineProgress? = null,
    val offlineStatus: String? = null,
)

/** 节目详情只用本地 feed 库，不访问自建服务器仓库。 */
class PodcastDetailViewModel(app: Application) : AndroidViewModel(app) {
    private val library = LocalPodcastLibrary.get(app.applicationContext)
    private val offline = OfflinePodcastCache.get(app.applicationContext)
    private val _state = MutableStateFlow(PodcastDetailUiState())
    internal val state: StateFlow<PodcastDetailUiState> = _state.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    private var loadedFeed: LocalFeed? = null
    private var action: Job? = null

    fun consumeMessage() { _message.value = null }

    fun load(podcastId: String, forceRefresh: Boolean = false) {
        if (action?.isActive == true) return
        val cached = library.feeds.value.firstOrNull { it.id == podcastId || it.podcast.id == podcastId }
        val feed = cached ?: loadedFeed?.takeIf { it.id == podcastId || it.podcast.id == podcastId }
        if (feed == null) {
            _state.value = PodcastDetailUiState(loading = false, error = "本地没有该节目的公开 feed 订阅")
            return
        }
        showFeed(feed, subscribed = cached != null)
        if (forceRefresh && cached != null) runAction("正在刷新单集列表…", cancellable = true) {
            refreshFeedBeforeCache({ library.refresh(feed.id) }) { updated ->
                val subscribed = library.feeds.value.any { it.id == updated.id }
                showFeed(updated, subscribed = subscribed)
                if (subscribed) updateCache(updated)
            }.onFailure { _state.update { it.copy(offlineStatus = "刷新单集失败，已保留原有缓存") } }
        }
    }

    internal fun setPolicy(policy: OfflinePolicy) {
        val feed = loadedFeed ?: return
        if (library.feeds.value.none { it.id == feed.id }) return
        runAction("正在更新离线缓存…", cancellable = policy != OfflinePolicy.NONE) { updateCache(feed, policy) }
    }

    fun cancelOffline() {
        if (_state.value.cancellable) action?.cancel()
    }

    private fun showFeed(feed: LocalFeed, subscribed: Boolean) {
        loadedFeed = feed
        _state.update {
            it.copy(loading = false, error = null, podcast = feed.podcast, episodes = feed.episodes,
                subscribed = subscribed, policy = if (subscribed) offline.policy(feed) else OfflinePolicy.NONE)
        }
    }

    private suspend fun updateCache(feed: LocalFeed, policy: OfflinePolicy? = null) {
        val chosen = policy ?: offline.policy(feed)
        val result = offline.update(feed, policy, access = { library.offlineAccess(feed.id) }) { progress ->
            _state.update { it.copy(policy = chosen, progress = progress, offlineStatus = null) }
        }
        _state.update {
            it.copy(offlineStatus = when {
                result.failed > 0 -> "${result.failed} 集缓存失败，已保留原有缓存；可刷新重试"
                result.skipped > 0 -> "缓存已更新，跳过 ${result.skipped} 个非单集媒体"
                chosen == OfflinePolicy.NONE -> if (policy != null) "离线缓存已清除" else null
                result.total == 0 -> "暂无可缓存的单集"
                else -> "离线缓存已就绪：${result.total} 集"
            })
        }
    }

    private fun runAction(status: String, cancellable: Boolean = false, block: suspend () -> Unit) {
        if (action?.isCompleted == false) return
        _state.update { it.copy(busy = true, cancellable = cancellable, progress = null, offlineStatus = status) }
        action = viewModelScope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                if (cancellable) _state.update { it.copy(offlineStatus = "已取消，保留已完成的缓存") }
                throw cancelled
            } catch (_: Exception) {
                _state.update { it.copy(offlineStatus = "操作失败，请重试") }
            } finally {
                val policy = loadedFeed?.let { offline.policy(it) } ?: OfflinePolicy.NONE
                _state.update { it.copy(busy = false, cancellable = false, policy = policy, progress = null) }
            }
        }
    }

    fun toggleSubscribe() {
        val feed = loadedFeed ?: return
        runAction("正在更新订阅…") {
            val currentlySubscribed = library.feeds.value.any { it.id == feed.id }
            if (currentlySubscribed) {
                library.remove(feed.id)
                showFeed(feed, subscribed = false)
            } else {
                val imported = library.`import`(feed.sourceUrl).getOrThrow()
                showFeed(imported.firstOrNull { it.id == feed.id } ?: feed,
                    subscribed = library.feeds.value.any { it.id == feed.id })
            }
            _state.update { it.copy(offlineStatus = null) }
            _message.value = if (_state.value.subscribed) "已重新订阅" else "已取消订阅"
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
