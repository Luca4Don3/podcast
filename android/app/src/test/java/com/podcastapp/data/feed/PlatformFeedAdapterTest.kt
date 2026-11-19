package com.podcastapp.data.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformFeedAdapterTest {

    private fun normalized(input: String) = PlatformFeedAdapter.normalize(input).getOrThrow()

    // ---------- normalize: 直链与平台分流 ----------

    @Test fun httpsRssDirectLinkStaysDirectFeed() {
        val ref = normalized("https://cdn.example.com/podcast/rss.xml")
        assertTrue(ref is FeedReference.DirectFeed)
        assertEquals("https://cdn.example.com/podcast/rss.xml", ref.url)
        assertEquals("https://cdn.example.com/podcast/rss.xml", ref.fetchUrl)
        assertEquals(FeedPlatform.DIRECT, ref.platform)
    }

    @Test fun httpAndFeedAliasesStayDirectFeed() {
        listOf(
            "http://example.com/rss",
            "https://example.com/feed/",
            "https://example.com/atom.xml",
            "https://example.com/blog?format=rss",
            "https://example.com",
        ).forEach { url ->
            val ref = normalized(url)
            assertTrue(ref is FeedReference.DirectFeed)
            assertEquals(url, ref.url)
        }
    }

    @Test fun appleShowBecomesItunesLookupWithoutFetchingPage() {
        val ref = normalized("https://podcasts.apple.com/us/podcast/my-show/id1234567890") as FeedReference.AppleLookup
        assertEquals("1234567890", ref.showId)
        assertEquals("https://itunes.apple.com/lookup?id=1234567890&entity=podcast", ref.lookupUrl)
        assertEquals(ref.lookupUrl, ref.fetchUrl)
        assertEquals("https://podcasts.apple.com/us/podcast/my-show/id1234567890", ref.originalUrl)
        assertEquals(FeedPlatform.APPLE, ref.platform)
    }

    @Test fun appleUrlWithQueryAndTrailingSlashStillMatches() {
        val ref = normalized("  HTTPS://PODCASTS.APPLE.COM/cn/podcast/x/id987654321/?i=123  ") as FeedReference.AppleLookup
        assertEquals("987654321", ref.showId)
        assertEquals("https://itunes.apple.com/lookup?id=987654321&entity=podcast", ref.fetchUrl)
    }

    @Test fun appleUrlWithoutNumericIdIsDirectFeed() {
        val ref = normalized("https://podcasts.apple.com/us/podcast")
        assertTrue(ref is FeedReference.DirectFeed)
    }

    @Test fun spotifyShowIsWebMetadataOnly() {
        val url = "https://open.spotify.com/show/4rOoJ6Egrf8K2IrywzwOMk"
        val ref = normalized(url) as FeedReference.WebMetadata
        assertEquals(FeedPlatform.SPOTIFY, ref.platform)
        assertEquals(url, ref.pageUrl)
        assertEquals(url, ref.url)
        assertNull(ref.fetchUrl)
        assertFalse(ref.pageUrl.contains("rss"))
        assertFalse(ref.pageUrl.contains("itunes"))
    }

    @Test fun xiaoyuzhouPodcastIsWebMetadataOnly() {
        val url = "https://www.xiaoyuzhoufm.com/podcast/5f2a1b3c4d5e6f0011223344"
        val ref = normalized(url) as FeedReference.WebMetadata
        assertEquals(FeedPlatform.XIAOYUZHOU, ref.platform)
        assertEquals(url, ref.pageUrl)
        assertNull(ref.fetchUrl)
    }

    @Test fun ximalayaAlbumIsWebMetadataOnly() {
        val url = "https://www.ximalaya.com/album/12345678"
        val ref = normalized(url) as FeedReference.WebMetadata
        assertEquals(FeedPlatform.XIMALAYA, ref.platform)
        assertEquals(url, ref.pageUrl)
        assertNull(ref.fetchUrl)
    }

    @Test fun platformPathsMustMatchExactly() {
        assertTrue(normalized("https://open.spotify.com/album/abc") is FeedReference.DirectFeed)
        assertTrue(normalized("https://www.xiaoyuzhoufm.com/episode/abc") is FeedReference.DirectFeed)
        assertTrue(normalized("https://www.ximalaya.com/sound/123") is FeedReference.DirectFeed)
    }

    @Test fun unrelatedHttpPageIsDirectFeed() {
        val ref = normalized("https://example.com/some/article?a=1")
        assertTrue(ref is FeedReference.DirectFeed)
        assertEquals("https://example.com/some/article?a=1", ref.fetchUrl)
    }

    // ---------- normalize: 拒绝项 ----------

    @Test fun rejectsNonHttpSchemes() {
        listOf(
            "ftp://example.com/rss.xml",
            "file:///etc/passwd",
            "javascript:alert(1)",
            "data:text/plain,hello",
            "//example.com/feed",
            "example.com/feed",
            "httpx://example.com/feed",
        ).forEach { url ->
            assertTrue("Expected rejection: $url", PlatformFeedAdapter.normalize(url).isFailure)
        }
    }

    // ---------- normalize: URL 内嵌凭据 ----------

    @Test fun extractsUserInfoCredentialsAndRemovesThemFromUrl() {
        val ref = normalized("https://alice:s3cr3t@example.com/feed") as FeedReference.DirectFeed
        assertEquals("https://example.com/feed", ref.url)
        assertEquals(ref.url, ref.fetchUrl)
        assertEquals(CredentialKind.BASIC, ref.credentials.kind)
        assertEquals("alice", ref.credentials.username)
        assertEquals("s3cr3t", ref.credentials.secret)

        val userOnly = normalized("https://alice@example.com/feed") as FeedReference.DirectFeed
        assertEquals("https://example.com/feed", userOnly.url)
        assertEquals(CredentialKind.BASIC, userOnly.credentials.kind)
        assertEquals("alice", userOnly.credentials.username)
        assertEquals("", userOnly.credentials.secret)

        val encoded = normalized("https://a%40b:p%3Aq@example.com/feed") as FeedReference.DirectFeed
        assertEquals("a@b", encoded.credentials.username)
        assertEquals("p:q", encoded.credentials.secret)
        assertEquals("https://example.com/feed", encoded.url)
    }

    @Test fun rejectsAmbiguousAuthorityWithMultipleAtSigns() {
        assertTrue(PlatformFeedAdapter.normalize("http://a@b@example.com/feed").isFailure)
    }

    @Test fun extractsTokenApiKeyAndKeyQueryCredentials() {
        val bearer = normalized("https://example.com/feed?token=tok-123&format=rss") as FeedReference.DirectFeed
        assertEquals("https://example.com/feed?format=rss", bearer.url)
        assertEquals(CredentialKind.BEARER, bearer.credentials.kind)
        assertEquals("tok-123", bearer.credentials.secret)

        val apiKey = normalized("https://example.com/feed?api_key=k-1") as FeedReference.DirectFeed
        assertEquals("https://example.com/feed", apiKey.url)
        assertEquals(CredentialKind.API_KEY, apiKey.credentials.kind)
        assertEquals("k-1", apiKey.credentials.secret)
        assertEquals("X-API-Key", apiKey.credentials.header)

        val key = normalized("https://example.com/feed?key=k-2&v=1#frag") as FeedReference.DirectFeed
        assertEquals("https://example.com/feed?v=1#frag", key.url)
        assertEquals(CredentialKind.API_KEY, key.credentials.kind)
        assertEquals("k-2", key.credentials.secret)
    }

    @Test fun urlCredentialsTakePrecedenceOverExplicitCredentials() {
        val ref = PlatformFeedAdapter.normalize(
            "https://alice:pw@example.com/feed?token=from-url",
            SourceCredentials(kind = CredentialKind.BEARER, secret = "from-argument"),
        ).getOrThrow() as FeedReference.DirectFeed
        assertEquals(CredentialKind.BEARER, ref.credentials.kind)
        assertEquals("from-url", ref.credentials.secret)
        assertEquals("alice", ref.credentials.username)
        assertEquals("https://example.com/feed", ref.url)
    }

    @Test fun explicitCredentialsSurviveWhenUrlHasNone() {
        val explicit = SourceCredentials(CredentialKind.API_KEY, "", "secret", "X-Custom-Key")
        val ref = PlatformFeedAdapter.normalize("https://example.com/feed", explicit).getOrThrow()
        assertEquals(explicit, (ref as FeedReference.DirectFeed).credentials)
        assertEquals(SourceCredentials(), (normalized("https://example.com/feed") as FeedReference.DirectFeed).credentials)
    }

    @Test fun rejectsControlCharacters() {
        listOf(
            "https://example.com/feed\n",
            "https://exa\u0000mple.com/feed",
            "https://example.com/\u0007",
            "https://example.com/\r\nfeed",
            "https://example.com/feed\u007f",
        ).forEach { url ->
            assertTrue("Expected rejection", PlatformFeedAdapter.normalize(url).isFailure)
        }
    }

    @Test fun rejectsInvalidHosts() {
        listOf(
            "https://exa_mple.com/feed",
            "https:///feed",
            "https://:/feed",
            "http://exa mple.com/feed",
            "https://.example.com/feed",
            "https://example..com/feed",
            "https://999.999.999.999/feed",
            "https://example.com:70000/feed",
        ).forEach { url ->
            assertTrue("Expected rejection: $url", PlatformFeedAdapter.normalize(url).isFailure)
        }
    }

    @Test fun rejectsBlankAndSpoofedUrls() {
        listOf(
            "",
            "   ",
            "https:/example.com/feed",
            "https://example.com\\@evil.com/feed",
            "https://example.com/feed\t",
        ).forEach { url ->
            assertTrue("Expected rejection: $url", PlatformFeedAdapter.normalize(url).isFailure)
        }
    }

    @Test fun acceptsBracketedIpv6Host() {
        val ref = normalized("http://[::1]:8080/feed.xml")
        assertTrue(ref is FeedReference.DirectFeed)
    }

    // ---------- parseOpml ----------

    @Test fun parseOpmlExtractsTitleAndXmlUrlInOrder() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <opml version="2.0"><head><title>订阅</title></head><body>
              <outline title="甲节目" xmlUrl="https://example.com/a.xml"/>
              <outline title="乙节目" xmlUrl="http://example.com/b.xml"/>
            </body></opml>
        """.trimIndent()
        val feeds = PlatformFeedAdapter.parseOpml(xml).getOrThrow()
        assertEquals(
            listOf(
                OpmlFeed("甲节目", "https://example.com/a.xml"),
                OpmlFeed("乙节目", "http://example.com/b.xml"),
            ),
            feeds,
        )
    }

    @Test fun parseOpmlUsesTextFallbackAndSkipsMissingOrNonHttpXmlUrl() {
        val xml = """
            <opml><body>
              <outline text="备用标题" xmlUrl="https://example.com/feed"/>
              <outline title="没有链接"/>
              <outline title="非 http" xmlUrl="file:///etc/passwd"/>
            </body></opml>
        """.trimIndent()
        assertEquals(
            listOf(OpmlFeed("备用标题", "https://example.com/feed")),
            PlatformFeedAdapter.parseOpml(xml).getOrThrow(),
        )
    }

    @Test fun parseOpmlDeduplicatesByXmlUrl() {
        val xml = """
            <opml><body>
              <outline title="第一次" xmlUrl="https://example.com/feed.xml"/>
              <outline title="第二次" xmlUrl="https://example.com/feed.xml"/>
            </body></opml>
        """.trimIndent()
        val feeds = PlatformFeedAdapter.parseOpml(xml).getOrThrow()
        assertEquals(1, feeds.size)
        assertEquals("第一次", feeds[0].title)
    }

    @Test fun parseOpmlCapsAtFiveHundredFeeds() {
        val outlines = (1..502).joinToString("") { index ->
            """<outline title="f$index" xmlUrl="https://example.com/feed$index.xml"/>"""
        }
        val feeds = PlatformFeedAdapter.parseOpml("<opml><body>$outlines</body></opml>").getOrThrow()
        assertEquals(500, feeds.size)
        assertEquals("https://example.com/feed1.xml", feeds.first().xmlUrl)
        assertEquals("https://example.com/feed500.xml", feeds.last().xmlUrl)
    }

    @Test fun parseOpmlRejectsInputOverFourMiB() {
        val xml = "<opml><body></body></opml>" + " ".repeat(4 * 1024 * 1024)
        assertTrue(PlatformFeedAdapter.parseOpml(xml).isFailure)
    }

    @Test fun parseOpmlRejectsDoctypeExternalEntity() {
        val xml = """
            <!DOCTYPE opml [ <!ENTITY xxe SYSTEM "file:///etc/passwd"> ]>
            <opml><body><outline title="&xxe;" xmlUrl="https://example.com/feed"/></body></opml>
        """.trimIndent()
        assertTrue(PlatformFeedAdapter.parseOpml(xml).isFailure)
    }

    @Test fun parseOpmlRejectsMalformedXml() {
        assertTrue(PlatformFeedAdapter.parseOpml("<opml><body><outline/></opml>").isFailure)
        assertTrue(PlatformFeedAdapter.parseOpml("").isFailure)
    }

    // ---------- parseFeedXml ----------

    @Test fun parseRss2ChannelAndItems() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd">
              <channel>
                <title>测试播客</title>
                <description>每日一集</description>
                <item>
                  <title>第一集</title>
                  <description>开场</description>
                  <guid isPermaLink="false">ep-1</guid>
                  <enclosure url="https://cdn.example.com/ep1.mp3" type="audio/mpeg" length="123"/>
                  <pubDate>Tue, 18 Nov 2025 08:00:00 +0000</pubDate>
                  <itunes:duration>01:02:03</itunes:duration>
                </item>
                <item>
                  <title>第二集</title>
                  <guid>ep-2</guid>
                  <enclosure url="https://cdn.example.com/ep2.m4a" type="audio/x-m4a" length="1"/>
                  <pubDate>Wed, 19 Nov 2025 08:00:00 +0000</pubDate>
                </item>
              </channel>
            </rss>
        """.trimIndent()
        val feed = PlatformFeedAdapter.parseFeedXml(xml).getOrThrow()
        assertEquals("测试播客", feed.title)
        assertEquals("每日一集", feed.description)
        assertEquals(2, feed.items.size)
        assertEquals(
            ParsedEpisode(
                title = "第一集",
                description = "开场",
                guid = "ep-1",
                audioUrl = "https://cdn.example.com/ep1.mp3",
                pubDate = "Tue, 18 Nov 2025 08:00:00 +0000",
                duration = "01:02:03",
            ),
            feed.items[0],
        )
        assertEquals("ep-2", feed.items[1].guid)
        assertEquals("", feed.items[1].duration)
    }

    @Test fun parseAtomFeedAndEntries() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <title>Atom 节目</title>
              <subtitle>Atom 描述</subtitle>
              <entry>
                <title>原子一</title>
                <summary>摘要</summary>
                <id>urn:uuid:abc</id>
                <link rel="enclosure" href="https://cdn.example.com/atom1.mp3" type="audio/mpeg"/>
                <published>2025-11-18T08:00:00Z</published>
                <updated>2025-11-19T08:00:00Z</updated>
                <duration>1800</duration>
              </entry>
            </feed>
        """.trimIndent()
        val feed = PlatformFeedAdapter.parseFeedXml(xml).getOrThrow()
        assertEquals("Atom 节目", feed.title)
        assertEquals("Atom 描述", feed.description)
        assertEquals(1, feed.items.size)
        assertEquals("原子一", feed.items[0].title)
        assertEquals("摘要", feed.items[0].description)
        assertEquals("urn:uuid:abc", feed.items[0].guid)
        assertEquals("https://cdn.example.com/atom1.mp3", feed.items[0].audioUrl)
        assertEquals("2025-11-18T08:00:00Z", feed.items[0].pubDate)
        assertEquals("1800", feed.items[0].duration)
    }

    @Test fun prefersAudioEnclosureButKeepsHttpFallback() {
        val xml = """
            <rss><channel><title>混合</title><item>
              <title>混合集</title>
              <enclosure url="https://cdn.example.com/cover.jpg" type="image/jpeg"/>
              <enclosure url="https://cdn.example.com/audio.mp3" type="AUDIO/MPEG"/>
            </item></channel></rss>
        """.trimIndent()
        assertEquals("https://cdn.example.com/audio.mp3", PlatformFeedAdapter.parseFeedXml(xml).getOrThrow().items[0].audioUrl)
    }

    @Test fun ignoresNonHttpEnclosureAndNonEnclosureLinks() {
        val xml = """
            <rss><channel><title>无音频</title><item>
              <title>无音频集</title>
              <enclosure url="file:///etc/passwd" type="audio/mpeg"/>
              <link rel="enclosure" href="ftp://example.com/a.mp3"/>
              <link href="https://example.com/page"/>
            </item></channel></rss>
        """.trimIndent()
        assertEquals("", PlatformFeedAdapter.parseFeedXml(xml).getOrThrow().items[0].audioUrl)
    }

    @Test fun fallsBackToUpdatedWhenPublishedMissing() {
        val xml = """
            <feed xmlns="http://www.w3.org/2005/Atom"><title>t</title><entry>
              <id>id-1</id><updated>2025-11-19T08:00:00Z</updated>
            </entry></feed>
        """.trimIndent()
        assertEquals("2025-11-19T08:00:00Z", PlatformFeedAdapter.parseFeedXml(xml).getOrThrow().items[0].pubDate)
    }

    @Test fun parseFeedXmlCapsAtFiveHundredItems() {
        val items = (1..502).joinToString("") { index ->
            """<item><title>第$index</title><guid>g$index</guid><enclosure url="https://cdn.example.com/$index.mp3" type="audio/mpeg"/></item>"""
        }
        val feed = PlatformFeedAdapter.parseFeedXml("<rss><channel><title>大节目</title>$items</channel></rss>").getOrThrow()
        assertEquals(500, feed.items.size)
        assertEquals("第1", feed.items.first().title)
        assertEquals("第500", feed.items.last().title)
    }

    @Test fun parseFeedXmlRejectsInputOverFourMiB() {
        val xml = "<rss><channel><title>x</title></channel></rss>" + " ".repeat(4 * 1024 * 1024)
        assertTrue(PlatformFeedAdapter.parseFeedXml(xml).isFailure)
    }

    @Test fun parseFeedXmlRejectsDoctypeExternalEntity() {
        val xml = """
            <!DOCTYPE rss [ <!ENTITY xxe SYSTEM "file:///etc/passwd"> ]>
            <rss><channel><title>&xxe;</title></channel></rss>
        """.trimIndent()
        assertTrue(PlatformFeedAdapter.parseFeedXml(xml).isFailure)
    }

    @Test fun parseFeedXmlRejectsMalformedXml() {
        assertTrue(PlatformFeedAdapter.parseFeedXml("<rss><channel><title>未闭合</channel></rss>").isFailure)
        assertTrue(PlatformFeedAdapter.parseFeedXml("not xml at all").isFailure)
    }
}
