package com.podcastapp.data.feed

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalPodcastLibraryTest {

    private fun tempDir(): File = Files.createTempDirectory("local-podcast-test").toFile().also { it.deleteOnExit() }

    private fun library(dir: File, fetcher: (String) -> String): LocalPodcastLibrary =
        LocalPodcastLibrary(dir, fetcher, true)

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

    private companion object {
        const val FEED = "https://cdn.example.com/local.xml"
    }
}
