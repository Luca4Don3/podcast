package com.podcastapp.data.repository

import com.podcastapp.data.PendingItem
import com.podcastapp.data.ProgressOwner
import com.podcastapp.data.progressOwner
import com.podcastapp.data.remote.ApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.podcastapp.data.ServiceLocator
import com.podcastapp.data.remote.ActionRequest
import com.podcastapp.data.remote.CreatePlaylistRequest
import com.podcastapp.data.remote.EpisodeDto
import com.podcastapp.data.remote.PlaylistDto
import com.podcastapp.data.remote.PodcastDto
import com.podcastapp.data.remote.ProgressDto
import com.podcastapp.data.remote.ProgressRequest
import com.podcastapp.data.remote.SettingsDto
import com.podcastapp.data.remote.SyncItem
import com.podcastapp.data.remote.SyncRequest
import com.podcastapp.data.remote.SyncSnapshot
import com.podcastapp.util.Dates

class LibraryRepository(private val locator: ServiceLocator) {

    private val progressSendMutex = Mutex()

    suspend fun subscriptions(): List<PodcastDto> = apiCall {
        locator.requireClient().api.subscriptions().items
    }

    suspend fun subscribe(podcastId: String) = apiCall {
        locator.requireClient().api.subscribe(podcastId)
    }

    suspend fun unsubscribe(podcastId: String) = apiCall {
        locator.requireClient().api.unsubscribe(podcastId)
    }

    /**
     * 上报播放进度：离线时入队，联网后由 [flushPendingProgress] 统一补传，
     * 避免弱网导致「听到一半换设备没有进度」。
     */
    suspend fun saveProgress(owner: ProgressOwner, episodeId: String, positionMs: Long, durationMs: Long, completed: Boolean) {
        if (locator.sessionStore.current().progressOwner() != owner) return
        val item = PendingItem(episodeId, positionMs, durationMs, completed, Dates.nowIso())
        if (!locator.networkMonitor.isOnline()) {
            locator.syncQueue.enqueue(owner, item)
            return
        }
        progressSendMutex.withLock {
            // An online update supersedes a previously queued snapshot only after it succeeds.
            val superseded = locator.syncQueue.pending(owner).filter { it.episodeId == episodeId }
            try {
                apiCall {
                    clientFor(owner).api.saveProgress(
                        episodeId,
                        ProgressRequest(positionMs = positionMs, durationMs = durationMs, completed = completed, playedAt = item.playedAt),
                        owner,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                locator.syncQueue.enqueue(owner, item)
                return@withLock
            }
            locator.syncQueue.acknowledge(owner, superseded)
        }
    }

    /** A single reconnect batch, with exact-snapshot acknowledgement. */
    suspend fun flushPendingProgress(owner: ProgressOwner): Int = progressSendMutex.withLock {
        if (!locator.networkMonitor.isOnline() || locator.sessionStore.current().progressOwner() != owner) return@withLock 0
        val pending = locator.syncQueue.pending(owner)
        if (pending.isEmpty()) return@withLock 0
        val changes = pending.map { SyncItem(it.episodeId, it.positionMs, it.durationMs, it.completed, it.playedAt) }
        apiCall { clientFor(owner).api.sync(SyncRequest(changes), owner) }
        locator.syncQueue.acknowledge(owner, pending)
        pending.size
    }

    suspend fun progress(episodeId: String, owner: ProgressOwner): ProgressDto = apiCall {
        clientFor(owner).api.progress(episodeId, owner)
    }

    private suspend fun clientFor(owner: ProgressOwner) = locator.requireClient().also { client ->
        if (client.baseUrlResolved != owner.baseUrl || locator.sessionStore.current().progressOwner() != owner) {
            throw ApiException(401, "播放进度的账号已切换")
        }
    }

    suspend fun recentlyPlayed(): Pair<List<ProgressDto>, List<EpisodeDto>> = apiCall {
        val res = locator.requireClient().api.progressList()
        res.items to res.episodes
    }

    suspend fun setStarred(episodeId: String, starred: Boolean) = apiCall {
        locator.requireClient().api.saveAction(episodeId, ActionRequest(starred = starred))
    }

    suspend fun setQueued(episodeId: String, queued: Boolean) = apiCall {
        locator.requireClient().api.saveAction(episodeId, ActionRequest(queued = queued))
    }

    suspend fun starred(): List<EpisodeDto> = apiCall {
        locator.requireClient().api.actions(type = "starred").episodes
    }

    suspend fun queued(): List<EpisodeDto> = apiCall {
        locator.requireClient().api.actions(type = "queued").episodes
    }

    suspend fun playlists(): List<PlaylistDto> = apiCall {
        locator.requireClient().api.playlists().items
    }

    suspend fun createPlaylist(title: String): List<PlaylistDto> = apiCall {
        locator.requireClient().api.createPlaylist(CreatePlaylistRequest(title)).items
    }

    suspend fun playlist(id: String): Pair<PlaylistDto, List<EpisodeDto>> = apiCall {
        val res = locator.requireClient().api.playlist(id)
        res.playlist to res.episodes
    }

    suspend fun addToPlaylist(playlistId: String, episodeId: String) = apiCall {
        locator.requireClient().api.addPlaylistItem(playlistId, episodeId)
    }

    suspend fun removeFromPlaylist(playlistId: String, episodeId: String) = apiCall {
        locator.requireClient().api.removePlaylistItem(playlistId, episodeId)
    }

    suspend fun deletePlaylist(id: String) = apiCall {
        locator.requireClient().api.deletePlaylist(id)
    }

    suspend fun settings(): SettingsDto = apiCall { locator.requireClient().api.getSettings() }

    suspend fun saveSettings(settings: SettingsDto): SettingsDto = apiCall {
        locator.requireClient().api.saveSettings(settings)
    }

    suspend fun sync(items: List<SyncItem>): SyncSnapshot = apiCall {
        val owner = locator.sessionStore.current().progressOwner() ?: throw ApiException(401, "请先登录")
        clientFor(owner).api.sync(SyncRequest(items), owner)
    }
}
