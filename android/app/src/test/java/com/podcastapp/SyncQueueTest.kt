package com.podcastapp.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SyncQueueTest {
    @get:Rule val tmp = TemporaryFolder()
    private val a = ProgressOwner("https://a.example/api/v1/", "user-1")
    private val b = a.copy(userId = "user-2")
    private val c = a.copy(baseUrl = "https://b.example/api/v1/")
    private val first = PendingItem("ep", 10, 100, false, "2026-01-01T00:00:00Z")

    @Test fun separatesAccountsAndServersAndPreservesLegacy() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope) { tmp.root.resolve("queue.preferences_pb") }
            val queue = SyncQueue(store)
            val legacyKey = stringPreferencesKey("pending_items")
            store.edit { it[legacyKey] = "legacy-without-owner" }
            assertEquals(emptyList<PendingItem>(), queue.pending(a))
            val other = first.copy(positionMs = 20)
            queue.enqueue(a, first)
            queue.enqueue(b, other)
            queue.enqueue(c, first)
            assertEquals(listOf(first), queue.pending(a))
            assertEquals(listOf(other), queue.pending(b))
            assertEquals(listOf(first), queue.pending(c))
            queue.acknowledge(a, listOf(first))
            assertEquals(emptyList<PendingItem>(), queue.pending(a))
            assertEquals(listOf(other), queue.pending(b))
            assertEquals(listOf(first), queue.pending(c))
            assertEquals("legacy-without-owner", store.data.first()[legacyKey])
        } finally {
            scope.cancel()
        }
    }

    @Test fun acknowledgementDoesNotRemoveNewerOrNewProgress() = withQueue { queue ->
        queue.enqueue(a, first)
        val inFlight = queue.pending(a)
        val newer = first.copy(positionMs = 30)
        val newEpisode = first.copy(episodeId = "next")
        queue.enqueue(a, newer)
        queue.enqueue(a, newEpisode)
        queue.acknowledge(a, inFlight)
        assertEquals(listOf(newer, newEpisode), queue.pending(a))
    }

    @Test fun concurrentEnqueuesAreNotLost() = withQueue { queue ->
        coroutineScope {
            repeat(24) { n -> launch(Dispatchers.Default) { queue.enqueue(a, first.copy(episodeId = "ep-" + n)) } }
        }
        assertEquals((0 until 24).map { "ep-" + it }.toSet(), queue.pending(a).map { it.episodeId }.toSet())
    }

    @Test fun sessionWithoutValidOwnerHasNoQueue() {
        val session = Session(a.baseUrl, "test-token", null, a.userId, null, null, null)
        assertEquals(a, session.progressOwner())
        assertNull(session.copy(accessToken = null).progressOwner())
        assertNull(session.copy(baseUrl = "").progressOwner())
        assertNull(session.copy(userId = "").progressOwner())
    }

    private fun withQueue(block: suspend (SyncQueue) -> Unit) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope) { tmp.root.resolve("queue.preferences_pb") }
            block(SyncQueue(store))
        } finally {
            scope.cancel()
        }
    }
}
