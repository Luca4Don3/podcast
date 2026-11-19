package com.podcastapp.data.feed

import org.junit.Assert.assertEquals
import org.junit.Test

/** 纯 fake 存储验证加密存储的 save/load/remove 契约，不依赖真机与 keystore。 */
class SourceCredentialStoreTest {

    private fun store(): SourceCredentialStore = SourceCredentialStore(InMemoryCredentialStorage())

    @Test fun savesAndLoadsAllCredentialFieldsByFeedId() {
        val store = store()
        val credentials = SourceCredentials(
            kind = CredentialKind.API_KEY,
            username = "alice",
            secret = "s3cr3t",
            header = "X-Private-Key",
        )
        store.save("feed-a", credentials)
        assertEquals(credentials, store.load("feed-a"))
    }

    @Test fun missingFeedLoadsNoCredentials() {
        assertEquals(SourceCredentials(), store().load("missing"))
    }

    @Test fun removeDeletesOnlyTheRequestedFeed() {
        val store = store()
        store.save("feed-a", SourceCredentials(CredentialKind.BASIC, "alice", "pw"))
        store.save("feed-b", SourceCredentials(CredentialKind.BEARER, secret = "tok"))
        store.remove("feed-a")
        assertEquals(SourceCredentials(), store.load("feed-a"))
        assertEquals(SourceCredentials(CredentialKind.BEARER, secret = "tok"), store.load("feed-b"))
    }

    @Test fun savingSameFeedAgainOverwritesPreviousCredentials() {
        val store = store()
        store.save("feed-a", SourceCredentials(CredentialKind.BASIC, "alice", "old-pw"))
        store.save("feed-a", SourceCredentials(CredentialKind.BASIC, "alice", "new-pw"))
        assertEquals("new-pw", store.load("feed-a").secret)
    }
}
