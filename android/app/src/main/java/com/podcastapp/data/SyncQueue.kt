package com.podcastapp.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.serialization.builtins.ListSerializer
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val Context.syncStore by preferencesDataStore(name = "podcast_sync_queue")

/**
 * 离线可用的待同步变更队列：断网时先落本地，恢复网络后批量 flush，
 * 保证弱网 / 切后台场景下进度不丢。
 */
@Serializable
data class PendingItem(
    val episodeId: String,
    val positionMs: Long,
    val durationMs: Long,
    val completed: Boolean = false,
    val playedAt: String = "",
)

@Serializable
data class ProgressOwner(val baseUrl: String, val userId: String)

fun Session.progressOwner(): ProgressOwner? = userId
    ?.takeIf { isLoggedIn && baseUrl.isNotBlank() }
    ?.let { ProgressOwner(baseUrl, it) }

class SyncQueue internal constructor(private val store: DataStore<Preferences>) {
    constructor(context: Context) : this(context.applicationContext.syncStore)

    @Serializable
    private data class Entry(val owner: ProgressOwner, val item: PendingItem)

    suspend fun enqueue(owner: ProgressOwner, item: PendingItem) {
        store.edit { prefs ->
            val entries = read(prefs)
            val next = entries.filterNot { it.owner == owner && it.item.episodeId == item.episodeId } + Entry(owner, item)
            prefs[ITEMS] = json.encodeToString(serializer, next.takeLast(MAX_ITEMS))
        }
    }

    suspend fun pending(owner: ProgressOwner): List<PendingItem> =
        read(store.data.first()).filter { it.owner == owner }.map { it.item }

    /** Only remove the exact snapshots confirmed by the server. */
    suspend fun acknowledge(owner: ProgressOwner, sent: List<PendingItem>) {
        if (sent.isEmpty()) return
        val acknowledged = sent.toHashSet()
        store.edit { prefs ->
            val entries = read(prefs)
            val remaining = entries.filterNot { it.owner == owner && it.item in acknowledged }
            if (remaining.size != entries.size) {
                if (remaining.isEmpty()) prefs.remove(ITEMS)
                else prefs[ITEMS] = json.encodeToString(serializer, remaining)
            }
        }
    }

    private fun read(prefs: Preferences): List<Entry> = prefs[ITEMS]?.let { json.decodeFromString(serializer, it) }.orEmpty()

    private companion object {
        // Legacy pending_items has no verifiable owner; preserve it without uploading it.
        val ITEMS = stringPreferencesKey("pending_items_v2")
        const val MAX_ITEMS = 500
        val json = Json { ignoreUnknownKeys = true }
        val serializer = ListSerializer(Entry.serializer())
    }
}
