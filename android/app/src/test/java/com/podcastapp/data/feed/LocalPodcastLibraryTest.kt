package com.podcastapp.data.feed

import com.podcastapp.data.remote.SettingsDto
import java.io.File
import java.net.InetAddress
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalPodcastLibraryTest {

    private fun tempDir(): File = Files.createTempDirectory("local-podcast-test").toFile().also { it.deleteOnExit() }

    private fun store(): SourceCredentialStore = SourceCredentialStore(InMemoryCredentialStorage())

    private fun library(dir: File, fetcher: (String) -> String): LocalPodcastLibrary =
        LocalPodcastLibrary(dir, fetcher, true)

    private fun libraryWithCredentials(
        dir: File,
        credentialStore: SourceCredentialStore,
        fetcher: (String, SourceCredentials) -> String,
    ): LocalPodcastLibrary = LocalPodcastLibrary(dir, fetcher, credentialStore, true)

    private fun rss(
        title: String,
        itemTitle: String = "第一集",
        guid: String = "ep-1",
        audio: String = "https://cdn.example.com/1.mp3",
        duration: String = "01:02:03",
    ): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd">
          <channel>
            <title>$title</title><description>测试描述</description>
            <item>
              <title>$itemTitle</title><description>简介</description><guid>$guid</guid>
              <enclosure url="$audio" type="audio/mpeg"/>
              <pubDate>Tue, 18 Nov 2025 08:00:00 +0000</pubDate>
              <itunes:duration>$duration</itunes:duration>
            </item>
          </channel>
        </rss>
    """.trimIndent()

    @Test fun directRssImportMapsToDtosAndPersistsLibrary() {
        val dir = tempDir()
        val xml = rss("本地节目")
        val library = library(dir) { url -> if (url == FEED) xml else error("unexpected fetch: $url") }
        runBlocking {
            val imported = library.`import`(FEED).getOrThrow().single()
            assertEquals("本地节目", imported.podcast.title)
            assertEquals("测试描述", imported.podcast.description)
            assertEquals(FEED, imported.feedUrl)
            assertEquals(FEED, imported.podcast.feedUrl)
            assertTrue(imported.podcast.subscribed)
            assertEquals(1, imported.episodes.size)
            val episode = imported.episodes.single()
            assertEquals(imported.id, episode.podcastId)
            assertEquals("第一集", episode.title)
            assertEquals("ep-1", episode.guid)
            assertEquals("https://cdn.example.com/1.mp3", episode.audioUrl)
            assertEquals(3723, episode.durationSeconds)
            assertEquals(imported.id, library.feeds.value.single().id)
        }
        assertTrue(File(dir, "local_podcast_library.json").exists())
    }

    @Test fun anonymousHttpsRssOnExplicitPortImportsAndReloadsEpisode() {
        val dir = tempDir()
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("127.0.0.1").build()
        val tls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val imported = MockWebServer().use { server ->
            server.useHttps(tls.sslSocketFactory(), false)
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            val origin = "https://127.0.0.1:${server.port}"
            val feedUrl = "$origin/feed.xml"
            val audioUrl = "$origin/episode.mp3"
            server.enqueue(MockResponse()
                .setHeader("Content-Type", "application/rss+xml; charset=utf-8")
                .setBody(rss("Loopback podcast", "Loopback episode", audio = audioUrl)))
            val library = LocalPodcastLibrary(dir, LocalPodcastLibrary.Companion::fetchHttpText, store(), 1)
            val feed = runBlocking {
                library.`import`(feedUrl, transport = SourceTransport(
                    requireHttps = true, customCaPem = certificate.certificatePem(),
                )).getOrThrow().single()
            }
            val request = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            assertEquals("GET", request.method)
            assertEquals("/feed.xml", request.path)
            assertEquals(null, request.getHeader("Authorization"))
            assertEquals(null, request.getHeader("Cookie"))
            assertEquals(feedUrl, feed.feedUrl)
            assertEquals(audioUrl, feed.episodes.single().audioUrl)
            feed
        }
        assertEquals("Loopback podcast", imported.podcast.title)
        assertTrue(imported.podcast.subscribed)
        val episode = imported.episodes.single()
        assertEquals("Loopback episode", episode.title)
        assertEquals("ep-1", episode.guid)
        val reloaded = library(dir) { error("Reload should not fetch $it") }.feeds.value.single()
        assertEquals(imported, reloaded)
    }

    @Test fun reimportAndRefreshKeepStableLocalIdAndUpdateContent() {
        var title = "旧标题"
        val library = library(tempDir()) { url -> if (url == FEED) rss(title) else error("unexpected fetch: $url") }
        runBlocking {
            val first = library.`import`(FEED).getOrThrow().single()
            val again = library.`import`(FEED).getOrThrow().single()
            assertEquals(first.id, again.id)
            assertEquals(1, library.feeds.value.size)

            title = "新标题"
            val refreshed = library.refresh(first.id).getOrThrow()
            assertEquals(first.id, refreshed.id)
            assertEquals("新标题", refreshed.podcast.title)
            assertEquals(1, library.feeds.value.size)
            assertEquals("新标题", library.feeds.value.single().podcast.title)

            library.remove(first.id)
            assertTrue(library.feeds.value.isEmpty())
        }
    }

    @Test fun appleShareLinkResolvesFeedUrlFromOfficialLookupJson() {
        val apple = "https://podcasts.apple.com/us/podcast/show/id1234567890"
        val feed = "https://cdn.example.com/apple.xml"
        val calls = ArrayList<String>()
        val library = library(tempDir()) { url ->
            calls.add(url)
            when {
                url.startsWith("https://itunes.apple.com/lookup?") ->
                    """{"resultCount":1,"results":[{"feedUrl":"$feed","collectionName":"Apple 节目"}]}"""
                url == feed -> rss("Apple 节目")
                else -> error("unexpected fetch: $url")
            }
        }
        runBlocking {
            val imported = library.`import`(apple).getOrThrow().single()
            assertEquals(FeedPlatform.APPLE.name, imported.platform)
            assertEquals(apple, imported.sourceUrl)
            assertEquals(feed, imported.feedUrl)
            assertTrue(calls.first().contains("itunes.apple.com/lookup?id=1234567890"))
            assertEquals(2, calls.size)
        }
    }

    @Test fun opmlImportAddsEveryOutlineFeed() {
        val opmlUrl = "https://example.com/subscriptions.opml"
        val a = "https://example.com/a.xml"
        val b = "https://example.com/b.xml"
        val opml = """
            <opml version="2.0"><body>
              <outline title="甲节目" xmlUrl="$a"/>
              <outline title="乙节目" xmlUrl="$b"/>
            </body></opml>
        """.trimIndent()
        val library = library(tempDir()) { url ->
            when (url) {
                opmlUrl -> opml
                a -> rss(" Feed A ")
                b -> rss(" Feed B ")
                else -> error("unexpected fetch: $url")
            }
        }
        runBlocking {
            val imported = library.`import`(opmlUrl).getOrThrow()
            assertEquals(2, imported.size)
            assertEquals("甲节目", imported[0].podcast.title)
            assertEquals("乙节目", imported[1].podcast.title)
            assertEquals(listOf(a, b), library.feeds.value.map { it.feedUrl })
        }
    }

    @Test fun webMetadataOnlyReturnsPublicFeedPromptWithoutFetching() {
        var fetched = false
        val library = library(tempDir()) { fetched = true; error("must not fetch web page") }
        val result = runBlocking { library.`import`("https://open.spotify.com/show/abc123") }
        assertTrue(result.isFailure)
        assertEquals(PUBLIC_FEED_REQUIRED_MESSAGE, result.exceptionOrNull()?.message)
        assertFalse(fetched)
        assertTrue(library.feeds.value.isEmpty())
    }

    @Test fun savedLibraryReloadsFromDiskWithoutNetwork() {
        val dir = tempDir()
        val writer = library(dir) { url -> if (url == FEED) rss("落盘节目") else error("unexpected fetch: $url") }
        val id = runBlocking { writer.`import`(FEED).getOrThrow().single().id }
        val reader = library(dir) { error("no network expected") }
        assertEquals(1, reader.feeds.value.size)
        assertEquals(id, reader.feeds.value.single().id)
        assertEquals("落盘节目", reader.feeds.value.single().podcast.title)
    }

    @Test fun redirectResolverAllowsRelativeHttpTargetsButRejectsOtherSchemes() {
        assertEquals(
            "https://example.com/b.xml",
            resolveRedirect("https://example.com/dir/a.xml", "../b.xml").getOrThrow(),
        )
        assertEquals(
            "https://cdn.example.com/rss.xml",
            resolveRedirect("https://example.com/a.xml", "https://cdn.example.com/rss.xml").getOrThrow(),
        )
        listOf("file:///etc/passwd", "ftp://example.com/a.mp3", "javascript:alert(1)", "mailto:a@b.com")
            .forEach { location ->
                assertFalse("Expected rejection: $location",
                    resolveRedirect("https://example.com/a.xml", location).isSuccess)
            }
    }

    @Test fun durationParsingHandlesClockAndPlainSeconds() {
        assertEquals(3723, parseDurationSeconds("01:02:03"))
        assertEquals(125, parseDurationSeconds("02:05"))
        assertEquals(1800, parseDurationSeconds("1800"))
        assertEquals(0, parseDurationSeconds(""))
        assertEquals(0, parseDurationSeconds("not a duration"))
    }

    @Test fun getOrCreateReturnsProcessWideSingleton() {
        LocalPodcastLibrary.resetForTest()
        val dir = tempDir()
        val first = LocalPodcastLibrary.getOrCreate(dir) { error("no network expected") }
        val second = LocalPodcastLibrary.getOrCreate(tempDir()) { error("no network expected") }
        assertSame(first, second)

        first.recordProgress("ep-singleton", 1L, 2L, false)
        assertEquals(1, second.state.value.progress.size)

        LocalPodcastLibrary.resetForTest()
        val reloaded = LocalPodcastLibrary.getOrCreate(dir) { error("no network expected") }
        assertEquals(first.state.value, reloaded.state.value)
        LocalPodcastLibrary.resetForTest()
    }

    @Test fun localStateIsSavedToDiskAndReloaded() {
        val dir = tempDir()
        val writer = library(dir) { error("no network expected") }
        writer.recordProgress("ep-1", 12_000L, 120_000L, false)
        writer.recordProgress("ep-2", 90_000L, 90_000L, true)
        writer.setStarred("ep-1", true)
        writer.setStarred("ep-2", true)
        writer.setStarred("ep-2", false)
        writer.setQueued("ep-2", true)
        writer.saveSettings(SettingsDto(1.5, 45, 20, true, "always", "dark", false))

        val state = writer.state.value
        assertEquals(2, state.progress.size)
        val first = state.progress.getValue("ep-1")
        assertEquals("ep-1", first.episodeId)
        assertEquals(12_000L, first.positionMs)
        assertEquals(120_000L, first.durationMs)
        assertFalse(first.completed)
        assertTrue(first.updatedAt > 0L)
        assertTrue(state.progress.getValue("ep-2").completed)
        assertEquals(setOf("ep-1"), state.starred)
        assertEquals(setOf("ep-2"), state.queued)
        assertEquals(1.5, state.settings.playbackSpeed, 0.0)
        assertEquals(45, state.settings.skipForward)
        assertEquals(20, state.settings.skipBack)
        assertTrue(state.settings.autoDownload)
        assertEquals("always", state.settings.downloadPolicy)
        assertEquals("dark", state.settings.theme)
        assertFalse(state.settings.notificationOn)

        assertTrue(File(dir, "local_podcast_state.json").exists())
        val reader = library(dir) { error("no network expected") }
        assertEquals(state, reader.state.value)
    }

    @Test fun stateWritesReplaceSameKeysAndUpdateStateFlow() {
        val library = library(tempDir()) { error("no network expected") }
        library.recordProgress("ep-1", 1L, 10L, false)
        val firstUpdatedAt = library.state.value.progress.getValue("ep-1").updatedAt
        library.recordProgress("ep-1", 5L, 10L, true)
        val updated = library.state.value.progress.getValue("ep-1")
        assertEquals(1, library.state.value.progress.size)
        assertEquals(5L, updated.positionMs)
        assertTrue(updated.completed)
        assertTrue(updated.updatedAt >= firstUpdatedAt)

        library.setStarred("ep-1", true)
        library.setQueued("ep-1", true)
        assertEquals(setOf("ep-1"), library.state.value.starred)
        assertEquals(setOf("ep-1"), library.state.value.queued)
        library.setStarred("ep-1", false)
        library.setQueued("ep-1", false)
        assertTrue(library.state.value.starred.isEmpty())
        assertTrue(library.state.value.queued.isEmpty())
    }

    @Test fun corruptedOrPartialStateFileFallsBackToDefaults() {
        val dir = tempDir()
        val stateFile = File(dir, "local_podcast_state.json")
        stateFile.writeText("{ this is not json", Charsets.UTF_8)
        assertEquals(LocalLibraryState(), library(dir) { error("no network expected") }.state.value)

        stateFile.writeText("""{"starred":["only"]}""", Charsets.UTF_8)
        val partial = library(dir) { error("no network expected") }.state.value
        assertEquals(setOf("only"), partial.starred)
        assertEquals(SettingsDto(), partial.settings)
        assertTrue(partial.progress.isEmpty())
        assertTrue(partial.queued.isEmpty())
    }


    @Test fun credentialHeadersCoverBasicBearerAndApiKey() {
        val basic = credentialHeaders(SourceCredentials(CredentialKind.BASIC, "alice", "s3cr3t"))
        assertEquals(1, basic.size)
        assertEquals(
            "Basic " + Base64.getEncoder().encodeToString("alice:s3cr3t".toByteArray(Charsets.UTF_8)),
            basic["Authorization"],
        )
        assertEquals(
            "Bearer tok-1",
            credentialHeaders(SourceCredentials(CredentialKind.BEARER, secret = "tok-1"))["Authorization"],
        )
        assertEquals(
            "k-1",
            credentialHeaders(
                SourceCredentials(CredentialKind.API_KEY, secret = "k-1", header = "X-Private-Key"),
            )["X-Private-Key"],
        )
        assertTrue(credentialHeaders(SourceCredentials()).isEmpty())
    }

    @Test fun urlCredentialsAreStoredSeparatelyAndNeverPersistedInJson() {
        val dir = tempDir()
        val credentialStore = store()
        val requests = ArrayList<Pair<String, SourceCredentials>>()
        val library = libraryWithCredentials(dir, credentialStore) { url, credentials ->
            requests.add(url to credentials)
            if (url == "https://example.com/feed") rss("私有节目") else error("unexpected fetch: $url")
        }
        runBlocking {
            val imported = library.`import`("https://alice:secret-value@example.com/feed").getOrThrow().single()
            assertEquals("https://example.com/feed", imported.feedUrl)
            assertEquals("https://example.com/feed", imported.sourceUrl)
            assertEquals("https://example.com/feed", imported.podcast.feedUrl)
            assertEquals(1, requests.size)
            assertEquals(CredentialKind.BASIC, requests.single().second.kind)
            assertEquals("alice", requests.single().second.username)
            assertEquals("secret-value", requests.single().second.secret)
            assertEquals("secret-value", credentialStore.load(imported.id).secret)
        }
        val json = File(dir, "local_podcast_library.json").readText(Charsets.UTF_8)
        assertFalse(json.contains("alice"))
        assertFalse(json.contains("secret-value"))
        assertFalse(json.contains("@example.com"))
    }

    @Test fun reimportAndRemoveKeepCredentialStoreInSync() {
        val credentialStore = store()
        val library = libraryWithCredentials(tempDir(), credentialStore) { url, _ ->
            if (url == FEED) rss("标题") else error("unexpected fetch")
        }
        runBlocking {
            val first = library.`import`(FEED, SourceCredentials(CredentialKind.BEARER, secret = "tok-1"))
                .getOrThrow().single()
            assertEquals("tok-1", credentialStore.load(first.id).secret)
            val second = library.`import`(FEED, SourceCredentials(CredentialKind.BEARER, secret = "tok-2"))
                .getOrThrow().single()
            assertEquals(first.id, second.id)
            assertEquals("tok-2", credentialStore.load(second.id).secret)
            library.remove(first.id)
            assertEquals(CredentialKind.NONE, credentialStore.load(first.id).kind)
        }
    }

    @Test fun refreshUsesStoredCredentials() {
        val credentialStore = store()
        val seen = ArrayList<SourceCredentials>()
        val library = libraryWithCredentials(tempDir(), credentialStore) { url, credentials ->
            if (url == FEED) {
                seen.add(credentials)
                rss("标题")
            } else error("unexpected fetch")
        }
        runBlocking {
            val first = library.`import`(
                FEED,
                SourceCredentials(CredentialKind.API_KEY, secret = "k-1", header = "X-Key"),
            ).getOrThrow().single()
            seen.clear()
            library.refresh(first.id).getOrThrow()
            assertEquals(CredentialKind.API_KEY, seen.single().kind)
            assertEquals("X-Key", seen.single().header)
            assertEquals("k-1", seen.single().secret)
        }
    }

    @Test fun redirectForwardsCredentialsOnlyOnSameHttpsHost() {
        val credentials = SourceCredentials(CredentialKind.BEARER, secret = "tok")
        assertEquals(
            credentials,
            credentialsForRedirect("https://example.com/a.xml", "https://example.com/b.xml", credentials),
        )
        listOf(
            "https://cdn.example.com/b.xml",
            "http://example.com/b.xml",
            "http://cdn.example.com/b.xml",
        ).forEach { target ->
            assertEquals(
                CredentialKind.NONE,
                credentialsForRedirect("https://example.com/a.xml", target, credentials).kind,
            )
        }
        assertEquals(
            CredentialKind.NONE,
            credentialsForRedirect(
                "https://example.com/a.xml",
                "https://example.com/b.xml",
                SourceCredentials(),
            ).kind,
        )
    }

    @Test fun digestCredentialsProduceNoStaticAuthorizationHeader() {
        assertTrue(credentialHeaders(SourceCredentials(CredentialKind.DIGEST, "u", "pw")).isEmpty())
        assertTrue(digestEnabled(SourceCredentials(CredentialKind.DIGEST, "u", "pw"), SourceTransport()))
        assertTrue(digestEnabled(SourceCredentials(), SourceTransport(digest = true)))
        assertFalse(digestEnabled(SourceCredentials(), SourceTransport()))
    }

    @Test fun requireHttpsRejectsHttpFeedBeforeAnyFetch() {
        var fetched = false
        val library = LocalPodcastLibrary(
            tempDir(),
            { _, _, _ -> fetched = true; error("must not fetch") },
            store(),
            1,
        )
        val result = runBlocking {
            library.`import`("http://cdn.example.com/local.xml", transport = SourceTransport(requireHttps = true))
        }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("requireHttps") == true)
        assertFalse(fetched)
    }

    @Test fun requireHttpsRejectsHttpDowngradeRedirect() {
        assertTrue(resolveRedirect(
            "https://example.com/a.xml",
            "http://example.com/b.xml",
            SourceTransport(requireHttps = true),
        ).isFailure)
        assertEquals(
            "http://example.com/b.xml",
            resolveRedirect("https://example.com/a.xml", "http://example.com/b.xml").getOrThrow(),
        )
    }

    @Test fun refreshUsesSavedTransportAndTransportNeverPersistedInJson() {
        val dir = tempDir()
        val credentialStore = store()
        val seen = ArrayList<SourceTransport>()
        val library = LocalPodcastLibrary(
            dir,
            { _, _, transport -> seen.add(transport); rss("标题") },
            credentialStore,
            1,
        )
        val transport = SourceTransport(
            requireHttps = true,
            customCaPem = "ca-pem-marker",
            clientCertPem = "cert-pem-marker",
            clientKeyPem = "key-pem-marker",
            clientKeyPassword = "key-password-marker",
            digest = true,
        )
        runBlocking {
            val first = library.`import`(FEED, transport = transport).getOrThrow().single()
            assertEquals(transport, credentialStore.loadTransport(first.id))
            seen.clear()
            library.refresh(first.id).getOrThrow()
            assertEquals(transport, seen.single())
        }
        val json = File(dir, "local_podcast_library.json").readText(Charsets.UTF_8)
        listOf("ca-pem-marker", "cert-pem-marker", "key-pem-marker", "key-password-marker").forEach {
            assertFalse("Transport material leaked into JSON: " + it, json.contains(it))
        }
    }

    private companion object {
        const val FEED = "https://cdn.example.com/local.xml"
    }
}
