package com.podcastapp.data.storage

import com.podcastapp.data.feed.CredentialKind
import com.podcastapp.data.feed.InMemoryCredentialStorage
import com.podcastapp.data.feed.SourceCredentialStore
import com.podcastapp.data.feed.SourceCredentials
import com.podcastapp.data.feed.SourceTransport
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 纯 JVM 单测：临时目录 + 内存凭据存储，不依赖 Android keystore，也不发起网络请求。 */
class NetworkStorageLibraryTest {

    private fun fixture(): Pair<File, SourceCredentialStore> {
        val dir = Files.createTempDirectory("network-storage-test").toFile().also { it.deleteOnExit() }
        return dir to SourceCredentialStore(InMemoryCredentialStorage())
    }

    private fun library(dir: File, store: SourceCredentialStore): NetworkStorageLibrary =
        NetworkStorageLibrary(dir, store, true)

    private val credentials = SourceCredentials(
        kind = CredentialKind.API_KEY,
        username = "alice",
        secret = "SECRET_SENTINEL_123",
        header = "X-Private-Key",
    )

    private val transport = SourceTransport(
        requireHttps = false,
        customCaPem = "CA_PEM_SENTINEL",
        clientCertPem = "CERT_PEM_SENTINEL",
        clientKeyPem = "KEY_PEM_SENTINEL",
        clientKeyPassword = "KEY_PASSWORD_SENTINEL",
        digest = true,
    )

    @Test fun addPersistsSourceAndReloadsFromDisk() {
        val (dir, store) = fixture()
        val library = library(dir, store)
        runBlocking {
            val source = library.add(
                StorageProvider.WEBDAV, "家庭 NAS", "https://dav.example.com:5006", "Media",
                credentials, transport,
            ).getOrThrow()

            assertEquals(1, library.sources.value.size)
            assertEquals(source, library.sources.value.single())
            assertTrue(File(dir, "network_storage.json").exists())

            val reloaded = library(dir, store)
            assertEquals(listOf(source), reloaded.sources.value)
            assertEquals(credentials, reloaded.credentialsFor(source.id).first)
            assertEquals(transport, reloaded.credentialsFor(source.id).second)
        }
    }

    @Test fun repeatedAddWithSameKeyOverwritesAndDifferentRootPathIsNewSource() {
        val (dir, store) = fixture()
        val library = library(dir, store)
        runBlocking {
            val first = library.add(
                StorageProvider.WEBDAV, "旧标题", "https://dav.example.com/dav", "Media",
                credentials, transport,
            ).getOrThrow()
            val replacementCreds = SourceCredentials(CredentialKind.BASIC, "bob", "NEW_SECRET")
            val second = library.add(
                StorageProvider.WEBDAV, "新标题", "https://dav.example.com/dav/", "/Media/",
                replacementCreds, SourceTransport(),
            ).getOrThrow()

            assertEquals(first.id, second.id)
            assertEquals("新标题", second.label)
            assertEquals(1, library.sources.value.size)
            assertEquals(replacementCreds, library.credentialsFor(second.id).first)

            val otherRoot = library.add(
                StorageProvider.WEBDAV, "别的目录", "https://dav.example.com/dav/", "Other",
                credentials, transport,
            ).getOrThrow()
            assertNotEquals(second.id, otherRoot.id)
            assertEquals(2, library.sources.value.size)
        }
    }

    @Test fun removeDeletesSourceAndClearsOnlyItsCredentials() {
        val (dir, store) = fixture()
        val library = library(dir, store)
        runBlocking {
            val a = library.add(StorageProvider.WEBDAV, "A", "https://a.example.com/", "", credentials, transport).getOrThrow()
            val b = library.add(StorageProvider.ZSPACE, "B", "https://b.example.com/webdav/", "", credentials, transport).getOrThrow()

            library.remove(a.id)

            assertEquals(listOf(b), library.sources.value)
            assertEquals(SourceCredentials(), library.credentialsFor(a.id).first)
            assertEquals(SourceTransport(), library.credentialsFor(a.id).second)
            assertEquals(credentials, library.credentialsFor(b.id).first)
            assertEquals(transport, library.credentialsFor(b.id).second)
            assertEquals(listOf(b), library(dir, store).sources.value)
        }
    }

    @Test fun storageJsonContainsNoSecretsOrTransportMaterial() {
        val (dir, store) = fixture()
        val library = library(dir, store)
        runBlocking {
            val source = library.add(
                StorageProvider.WEBDAV, "私有源", "https://dav.example.com/", "Media",
                credentials, transport,
            ).getOrThrow()
            val json = File(dir, "network_storage.json").readText(Charsets.UTF_8)

            assertTrue(json.contains(source.id))
            assertTrue(json.contains("https://dav.example.com/"))
            assertFalse(json.contains("SECRET_SENTINEL_123"))
            assertFalse(json.contains("CA_PEM_SENTINEL"))
            assertFalse(json.contains("CERT_PEM_SENTINEL"))
            assertFalse(json.contains("KEY_PEM_SENTINEL"))
            assertFalse(json.contains("KEY_PASSWORD_SENTINEL"))
            assertFalse(json.contains("X-Private-Key"))
            assertFalse(json.contains("alice"))
            assertFalse(json.contains("API_KEY"))

            assertEquals(credentials, library.credentialsFor(source.id).first)
            assertEquals(transport, library.credentialsFor(source.id).second)
        }
    }

    @Test fun invalidBaseUrlsAreRejectedWithoutPersistingAnything() {
        val (dir, store) = fixture()
        val library = library(dir, store)
        val invalid = listOf(
            "",
            "ftp://dav.example.com/",
            "file:///tmp/dav",
            "dav.example.com",
            "https://user:pass@dav.example.com/",
            "https://dav.example.com/a\u0000b",
            "https://dav.example.com/a b",
            "https://dav.example.com/?token=SECRET_IN_URL",
        )
        runBlocking {
            for (value in invalid) {
                assertTrue("应拒绝: $value", library.add(StorageProvider.WEBDAV, "X", value, "", credentials, transport).isFailure)
            }
            assertTrue(library.sources.value.isEmpty())
            assertFalse(File(dir, "network_storage.json").exists())
        }
    }

    @Test fun jianguoyunBlankUrlUsesOfficialDefaultAndZspaceKeepsUserUrl() {
        val (dir, store) = fixture()
        val library = library(dir, store)
        runBlocking {
            val jg = library.add(StorageProvider.JIANGUOYUN, "坚果云", "   ", "", credentials, transport).getOrThrow()
            assertEquals("https://dav.jianguoyun.com/dav/", jg.baseUrl)

            val zs = library.add(
                StorageProvider.ZSPACE, "极空间", "https://192.168.1.10:5006/webdav", "music",
                credentials, transport,
            ).getOrThrow()
            assertEquals("https://192.168.1.10:5006/webdav/", zs.baseUrl)
            assertEquals("music", zs.rootPath)
            assertEquals(2, library.sources.value.size)
        }
    }

    @Test fun credentialsForMissingIdReturnsEmptyDefaults() {
        val (dir, store) = fixture()
        val library = library(dir, store)
        val (creds, tr) = library.credentialsFor("missing-id")
        assertEquals(SourceCredentials(), creds)
        assertEquals(SourceTransport(), tr)
    }
}
