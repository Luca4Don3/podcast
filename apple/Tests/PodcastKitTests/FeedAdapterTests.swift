import Foundation
import XCTest
@testable import PodcastKit

final class FeedAdapterTests: XCTestCase {

    // MARK: - normalize

    func testNormalizeDirectFeedCanonicalizesAndKeepsOrdinaryQuery() throws {
        let cases: [(String, String)] = [
            ("https://feeds.example.invalid/rss.xml", "https://feeds.example.invalid/rss.xml"),
            ("  HTTP://Feeds.Example.Invalid/rss  ", "http://feeds.example.invalid/rss"),
            ("https://feeds.example.invalid/rss?page=2#top", "https://feeds.example.invalid/rss?page=2"),
            ("http://feeds.example.invalid:8080/feed", "http://feeds.example.invalid:8080/feed"),
        ]
        for (input, expected) in cases {
            let reference = try PlatformFeedAdapter.normalize(input)
            guard case .directFeed(let url, let credentials) = reference else {
                XCTFail("Expected a direct feed for \(input)")
                continue
            }
            XCTAssertEqual(url.absoluteString, expected)
            XCTAssertEqual(credentials, SourceCredentials())
            XCTAssertNil(url.fragment)
        }
    }

    func testNormalizeAppleShareLinkUsesOfficialLookupEndpoint() throws {
        let inputs = [
            "https://podcasts.apple.com/us/podcast/show/id1234567890",
            "https://podcasts.apple.com/cn/podcast/%E7%A4%BA%E4%BE%8B/id1234567890?i=100055&ls=1",
        ]
        for input in inputs {
            let reference = try PlatformFeedAdapter.normalize(input)
            guard case .appleLookup(let url) = reference else {
                XCTFail("Expected an Apple lookup for \(input)")
                continue
            }
            XCTAssertEqual(url.absoluteString, "https://itunes.apple.com/lookup?id=1234567890&entity=podcast")
        }
    }

    func testNormalizePlatformShareLinksReturnWebMetadataWithoutFakeRSS() throws {
        let cases: [(String, String, String)] = [
            ("https://open.spotify.com/show/4rOoJ6Egrf8K2IrywzwOMk?si=tracking",
             "spotify", "https://open.spotify.com/show/4rOoJ6Egrf8K2IrywzwOMk"),
            ("https://www.xiaoyuzhoufm.com/podcast/5f3b2c1d4e5a6b7c8d9e0f1a",
             "xiaoyuzhoufm", "https://www.xiaoyuzhoufm.com/podcast/5f3b2c1d4e5a6b7c8d9e0f1a"),
            ("http://www.ximalaya.com/album/12345678",
             "ximalaya", "http://www.ximalaya.com/album/12345678"),
        ]
        for (input, platform, expected) in cases {
            let reference = try PlatformFeedAdapter.normalize(input)
            guard case .webMetadata(let url, let foundPlatform) = reference else {
                XCTFail("Expected web metadata for \(input)")
                continue
            }
            XCTAssertEqual(foundPlatform, platform)
            XCTAssertEqual(url.absoluteString, expected)
            XCTAssertFalse(url.absoluteString.localizedCaseInsensitiveContains("rss"))
            XCTAssertNil(url.query)
        }
    }

    func testNormalizeRejectsUnsafeAndUnrecognizedReferences() {
        let invalid = [
            "", "   ", "not a url", "podcasts.example.invalid/feed.xml",
            "ftp://feeds.example.invalid/rss", "file:///etc/passwd",
            "https:///rss", "https://feeds.example.invalid:65536/rss",
            "https://feeds.example.invalid/a b.xml",
            "https://feeds.example.invalid/a\nb.xml",
            "https://feeds.example.invalid/a\(Character(UnicodeScalar(0)))xml",
            "https://feeds.example.invalid\\@evil.invalid/rss",
            "https://podcasts.apple.com/us/podcast/id12x",
            "https://open.spotify.com/user/abc",
            "https://www.xiaoyuzhoufm.com/episode/abc",
            "https://www.ximalaya.com/sound/123",
            "https://open.spotify.com/show/abc%20def",
        ]
        for input in invalid {
            assertAdapterError(.invalidReference) { _ = try PlatformFeedAdapter.normalize(input) }
        }
    }

    func testErrorTextNeverEchoesSensitiveInput() {
        let marker = "secret-fixture-marker-42"
        do {
            _ = try PlatformFeedAdapter.normalize("https://user:\(marker)@feeds.example.invalid:65536/rss")
            XCTFail("Expected rejection")
        } catch {
            let description = (error as? LocalizedError)?.errorDescription ?? ""
            XCTAssertFalse(description.contains(marker))
            XCTAssertFalse(String(reflecting: error).contains(marker))
        }
        for error in [FeedAdapterError.invalidReference, .inputTooLarge, .malformedFeed,
                      .malformedOPML, .itemLimitExceeded, .externalEntityForbidden] {
            XCTAssertFalse((error.errorDescription ?? "").isEmpty)
            XCTAssertFalse((error.errorDescription ?? "").contains(marker))
        }
    }

    // MARK: - Source credentials

    func testNormalizeExtractsUserInfoIntoBasicCredentialsAndStripsURL() throws {
        let reference = try PlatformFeedAdapter.normalize("https://alice:s3cr3t@feeds.example.invalid/rss?page=2")
        guard case .directFeed(let url, let credentials) = reference else {
            return XCTFail("Expected a direct feed")
        }
        XCTAssertEqual(url.absoluteString, "https://feeds.example.invalid/rss?page=2")
        XCTAssertEqual(credentials, SourceCredentials(kind: .basic, username: "alice", secret: "s3cr3t"))
        let components = URLComponents(url: url, resolvingAgainstBaseURL: false)
        XCTAssertNil(components?.user)
        XCTAssertNil(components?.password)
        XCTAssertFalse(url.absoluteString.contains("alice"))
        XCTAssertFalse(url.absoluteString.contains("s3cr3t"))
    }

    func testNormalizeExtractsTokenAndAPIKeyQueriesAndStripsThem() throws {
        let tokenRef = try PlatformFeedAdapter.normalize("https://feeds.example.invalid/rss?token=tok-42&page=2")
        guard case .directFeed(let tokenURL, let tokenCredentials) = tokenRef else {
            return XCTFail("Expected a direct feed")
        }
        XCTAssertEqual(tokenURL.absoluteString, "https://feeds.example.invalid/rss?page=2")
        XCTAssertEqual(tokenCredentials, SourceCredentials(kind: .bearer, secret: "tok-42"))

        let keyRef = try PlatformFeedAdapter.normalize("https://feeds.example.invalid/rss?API_KEY=key-7&key=ignored&q=news")
        guard case .directFeed(let keyURL, let keyCredentials) = keyRef else {
            return XCTFail("Expected a direct feed")
        }
        XCTAssertEqual(keyURL.absoluteString, "https://feeds.example.invalid/rss?q=news")
        XCTAssertEqual(keyCredentials, SourceCredentials(kind: .apiKey, secret: "key-7"))
        XCTAssertEqual(keyCredentials.httpHeaders, [SourceCredentials.defaultAPIKeyHeader: "key-7"])
    }

    func testExplicitCredentialsOverrideEmbeddedCredentials() throws {
        let manual = SourceCredentials(kind: .bearer, secret: "manual-token")
        let reference = try PlatformFeedAdapter.normalize(
            "https://alice:secret@feeds.example.invalid/rss?token=embedded",
            credentials: manual)
        guard case .directFeed(let url, let credentials) = reference else {
            return XCTFail("Expected a direct feed")
        }
        XCTAssertEqual(credentials, manual)
        XCTAssertEqual(url.absoluteString, "https://feeds.example.invalid/rss")
        XCTAssertFalse(url.absoluteString.contains("embedded"))
    }

    func testStoredFeedURLNeverContainsCredentials() throws {
        let inputs = [
            "https://alice:s3cr3t@feeds.example.invalid/rss",
            "https://feeds.example.invalid/rss?token=tok-42",
            "https://feeds.example.invalid/rss?api_key=key-7",
        ]
        let markers = ["alice", "s3cr3t", "tok-42", "key-7"]
        for input in inputs {
            let reference = try PlatformFeedAdapter.normalize(input)
            guard case .directFeed(let url, let credentials) = reference else {
                XCTFail("Expected a direct feed for \(input)")
                continue
            }
            XCTAssertNotEqual(credentials.kind, .none)
            for marker in markers {
                XCTAssertFalse(url.absoluteString.contains(marker), "Leaked \(marker) into \(url.absoluteString)")
            }
            XCTAssertNil(URLComponents(url: url, resolvingAgainstBaseURL: false)?.user)
            XCTAssertNil(URLComponents(url: url, resolvingAgainstBaseURL: false)?.password)
        }
    }

    func testCredentialHTTPHeadersMapping() {
        XCTAssertEqual(SourceCredentials().httpHeaders, [:])
        XCTAssertEqual(
            SourceCredentials(kind: .basic, username: "alice", secret: "s3cr3t").httpHeaders,
            ["Authorization": "Basic YWxpY2U6czNjcjN0"])
        XCTAssertEqual(
            SourceCredentials(kind: .bearer, secret: "tok-42").httpHeaders,
            ["Authorization": "Bearer tok-42"])
        XCTAssertEqual(
            SourceCredentials(kind: .apiKey, secret: "key-7", header: "X-Custom-Key").httpHeaders,
            ["X-Custom-Key": "key-7"])
        XCTAssertEqual(
            SourceCredentials(kind: .apiKey, secret: "key-7").httpHeaders,
            ["X-API-Key": "key-7"])
        XCTAssertEqual(SourceCredentials(kind: .bearer).httpHeaders, [:])
        XCTAssertEqual(SourceCredentials(kind: .apiKey, secret: "key-7", header: "X-Bad\r\nHeader").httpHeaders,
                       ["X-API-Key": "key-7"])
        let injected = SourceCredentials(kind: .bearer, secret: "tok\r\nInjected").httpHeaders["Authorization"] ?? ""
        XCTAssertTrue(injected.hasPrefix("Bearer "))
        XCTAssertFalse(injected.contains { $0.unicodeScalars.contains { $0.value < 0x20 || $0.value == 0x7f } })
    }

    // MARK: - RSS 2.0

    func testParseRSS2ExtractsChannelAndSafeEpisodes() throws {
        let rss = """
<?xml version="1.0" encoding="UTF-8"?>
<rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd">
<channel>
<title>示例播客</title>
<description>测试描述</description>
<link>https://show.example.invalid/</link>
<item>
<title>第一集</title>
<guid>ep-1</guid>
<pubDate>Wed, 19 Nov 2025 00:00:00 +0000</pubDate>
<itunes:duration>01:02:03</itunes:duration>
<enclosure url="https://cdn.example.invalid/a.mp3" type="audio/mpeg" length="123"/>
</item>
<item>
<title>Unsafe enclosure</title>
<enclosure url="file:///etc/passwd" type="audio/mpeg"/>
</item>
<item>
<title>Missing enclosure</title>
</item>
</channel>
</rss>
"""
        let feed = try PlatformFeedAdapter.parseFeed(Data(rss.utf8))
        XCTAssertEqual(feed.id, "https://show.example.invalid/")
        XCTAssertEqual(feed.title, "示例播客")
        XCTAssertEqual(feed.description, "测试描述")
        XCTAssertEqual(feed.episodes.count, 1)
        let episode = try XCTUnwrap(feed.episodes.first)
        XCTAssertEqual(episode.id, "ep-1")
        XCTAssertEqual(episode.title, "第一集")
        XCTAssertEqual(episode.audioUrl.absoluteString, "https://cdn.example.invalid/a.mp3")
        XCTAssertEqual(episode.publishedAt, "Wed, 19 Nov 2025 00:00:00 +0000")
        XCTAssertEqual(episode.durationSeconds, 3723)
    }

    // MARK: - Atom

    func testParseAtomExtractsEntryEnclosureAndDuration() throws {
        let atom = """
<?xml version="1.0" encoding="UTF-8"?>
<feed xmlns="http://www.w3.org/2005/Atom" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd">
<id>urn:uuid:feed-42</id>
<title>Atom Show</title>
<subtitle>Atom description</subtitle>
<entry>
<id>entry-1</id>
<title>Episode One</title>
<published>2025-11-19T00:00:00Z</published>
<itunes:duration>12:34</itunes:duration>
<link rel="enclosure" href="https://cdn.example.invalid/one.m4a" type="audio/mp4"/>
<link rel="alternate" href="https://show.example.invalid/one"/>
</entry>
</feed>
"""
        let feed = try PlatformFeedAdapter.parseFeed(Data(atom.utf8))
        XCTAssertEqual(feed.id, "urn:uuid:feed-42")
        XCTAssertEqual(feed.title, "Atom Show")
        XCTAssertEqual(feed.description, "Atom description")
        XCTAssertEqual(feed.episodes.count, 1)
        let episode = try XCTUnwrap(feed.episodes.first)
        XCTAssertEqual(episode.id, "entry-1")
        XCTAssertEqual(episode.title, "Episode One")
        XCTAssertEqual(episode.audioUrl.absoluteString, "https://cdn.example.invalid/one.m4a")
        XCTAssertEqual(episode.publishedAt, "2025-11-19T00:00:00Z")
        XCTAssertEqual(episode.durationSeconds, 754)
    }

    func testParseFeedHonors500ItemLimit() throws {
        func document(withCount count: Int) -> Data {
            var items = ""
            for index in 0..<count {
                items += "<item><title>E\(index)</title><enclosure url=\"https://cdn.example.invalid/\(index).mp3\"/></item>"
            }
            return Data("<rss version=\"2.0\"><channel><title>Limit</title>\(items)</channel></rss>".utf8)
        }
        let allowed = try PlatformFeedAdapter.parseFeed(document(withCount: 500))
        XCTAssertEqual(allowed.episodes.count, 500)
        assertAdapterError(.itemLimitExceeded) {
            _ = try PlatformFeedAdapter.parseFeed(document(withCount: 501))
        }
    }

    // MARK: - XML attack surface

    func testParseFeedRejectsExternalAndInternalEntities() {
        let external = """
<!DOCTYPE rss [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
<rss version="2.0"><channel><title>&xxe;</title></channel></rss>
"""
        let internalEntity = """
<!DOCTYPE rss [<!ENTITY a "aaaaaaaaaa"><!ENTITY b "&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;">]>
<rss version="2.0"><channel><title>&b;</title></channel></rss>
"""
        assertAdapterError(.externalEntityForbidden) {
            _ = try PlatformFeedAdapter.parseFeed(Data(external.utf8))
        }
        assertAdapterError(.externalEntityForbidden) {
            _ = try PlatformFeedAdapter.parseFeed(Data(internalEntity.utf8))
        }
    }

    func testParseFeedRejectsOversizeAndMalformedInput() {
        let oversize = Data(repeating: 0x20, count: 4 * 1024 * 1024 + 1)
        assertAdapterError(.inputTooLarge) { _ = try PlatformFeedAdapter.parseFeed(oversize) }
        assertAdapterError(.malformedFeed) {
            _ = try PlatformFeedAdapter.parseFeed(Data("<rss><channel>".utf8))
        }
        assertAdapterError(.malformedFeed) {
            _ = try PlatformFeedAdapter.parseFeed(Data("<html><body/></html>".utf8))
        }
    }

    // MARK: - OPML

    func testParseOPMLDeduplicatesAndSkipsUnsafeOutlines() throws {
        let opml = """
<?xml version="1.0" encoding="UTF-8"?>
<opml version="2.0"><head><title>订阅</title></head><body>
<outline text="Folder">
<outline type="rss" title="One" xmlUrl="https://feeds.example.invalid/one.xml"/>
<outline title="Duplicate" xmlUrl="https://feeds.example.invalid/one.xml"/>
<outline title="Case duplicate" xmlUrl="https://FEEDS.EXAMPLE.INVALID/one.xml"/>
<outline text="Two fallback" xmlUrl="https://feeds.example.invalid/two.xml"/>
<outline title="Unsafe" xmlUrl="javascript:alert(1)"/>
<outline title="Missing"/>
</outline>
</body></opml>
"""
        let feeds = try PlatformFeedAdapter.parseOPML(Data(opml.utf8))
        XCTAssertEqual(feeds.count, 2)
        XCTAssertEqual(feeds[0].title, "One")
        XCTAssertEqual(feeds[0].url.absoluteString, "https://feeds.example.invalid/one.xml")
        XCTAssertEqual(feeds[1].title, "Two fallback")
    }

    func testParseOPMLHonors500ItemLimit() {
        func document(withCount count: Int) -> Data {
            var outlines = ""
            for index in 0..<count {
                outlines += "<outline title=\"F\(index)\" xmlUrl=\"https://feeds.example.invalid/\(index).xml\"/>"
            }
            return Data("<opml version=\"2.0\"><body>\(outlines)</body></opml>".utf8)
        }
        XCTAssertEqual(try? PlatformFeedAdapter.parseOPML(document(withCount: 500)).count, 500)
        assertAdapterError(.itemLimitExceeded) {
            _ = try PlatformFeedAdapter.parseOPML(document(withCount: 501))
        }
    }

    func testParseOPMLRejectsEntitiesOversizeAndMalformedInput() {
        let xxe = """
<!DOCTYPE opml [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
<opml version="2.0"><body><outline title="&xxe;" xmlUrl="https://feeds.example.invalid/a"/></body></opml>
"""
        assertAdapterError(.externalEntityForbidden) {
            _ = try PlatformFeedAdapter.parseOPML(Data(xxe.utf8))
        }
        assertAdapterError(.inputTooLarge) {
            _ = try PlatformFeedAdapter.parseOPML(Data(repeating: 0x20, count: 4 * 1024 * 1024 + 1))
        }
        assertAdapterError(.malformedOPML) {
            _ = try PlatformFeedAdapter.parseOPML(Data("<rss><channel/></rss>".utf8))
        }
    }

    // MARK: - Transport security

    func testSourceTransportDefaultsCodableAndValidation() throws {
        let transport = SourceTransport()
        XCTAssertEqual(transport, SourceTransport(requireHttps: false, customCaPem: "", clientCertPem: "",
                                                 clientKeyPem: "", clientKeyPassword: "", digest: false))
        let configured = SourceTransport(requireHttps: true, customCaPem: "ca", clientCertPem: "cert",
                                         clientKeyPem: "key", clientKeyPassword: "pw", digest: true)
        let roundtrip = try JSONDecoder().decode(SourceTransport.self,
                                                 from: JSONEncoder().encode(configured))
        XCTAssertEqual(roundtrip, configured)

        XCTAssertNoThrow(try SourceTransport().validate())
        XCTAssertNoThrow(try SourceTransport(requireHttps: true, digest: true).validate())
        let cert = "-----BEGIN CERTIFICATE-----\nQUJD\n-----END CERTIFICATE-----"
        let key = "-----BEGIN PRIVATE KEY-----\nQUJD\n-----END PRIVATE KEY-----"
        let encrypted = "-----BEGIN ENCRYPTED PRIVATE KEY-----\nQUJD\n-----END ENCRYPTED PRIVATE KEY-----"
        XCTAssertNoThrow(try SourceTransport(customCaPem: cert).validate())
        XCTAssertNoThrow(try SourceTransport(clientCertPem: cert, clientKeyPem: key).validate())
        assertTransportError(.incompleteClientCertificate) {
            _ = try SourceTransport(clientCertPem: cert).validate()
        }
        assertTransportError(.invalidCertificatePEM) {
            _ = try SourceTransport(customCaPem: "not a pem").validate()
        }
        assertTransportError(.invalidPrivateKeyPEM) {
            _ = try SourceTransport(clientCertPem: cert, clientKeyPem: "not a key").validate()
        }
        assertTransportError(.passwordRequired) {
            _ = try SourceTransport(clientCertPem: cert, clientKeyPem: encrypted).validate()
        }
        XCTAssertNoThrow(try SourceTransport(clientCertPem: cert, clientKeyPem: encrypted,
                                             clientKeyPassword: "pw").validate())
    }

    func testHTTPSRequirementRejectsHTTPAndDowngrades() {
        let strict = SourceTransport(requireHttps: true)
        XCTAssertFalse(strict.allows(URL(string: "http://feeds.example.invalid/rss")!))
        XCTAssertTrue(strict.allows(URL(string: "https://feeds.example.invalid/rss")!))
        let https = URL(string: "https://feeds.example.invalid/rss")!
        XCTAssertFalse(strict.allowsRedirect(from: https,
                                             to: URL(string: "http://feeds.example.invalid/rss")!))
        XCTAssertTrue(strict.allowsRedirect(from: https,
                                            to: URL(string: "https://cdn.example.invalid/rss")!))
        XCTAssertFalse(SourceTransport().allowsRedirect(
            from: https, to: URL(string: "http://feeds.example.invalid/rss")!))
        XCTAssertTrue(SourceTransport.shouldStripCredentials(
            from: https, to: URL(string: "https://elsewhere.example.invalid/rss")!))
        XCTAssertTrue(SourceTransport.shouldStripCredentials(
            from: https, to: URL(string: "http://feeds.example.invalid/rss")!))
        XCTAssertFalse(SourceTransport.shouldStripCredentials(
            from: https, to: URL(string: "https://feeds.example.invalid/other")!))
    }

    func testDigestCredentialNeverProducesStaticHeader() {
        XCTAssertEqual(SourceCredentials(kind: .digest, username: "alice", secret: "p@ss").httpHeaders, [:])
        XCTAssertEqual(SourceCredentials(kind: .none).httpHeaders, [:])
        XCTAssertEqual(SourceCredentials(kind: .basic, username: "alice", secret: "s").httpHeaders,
                       ["Authorization": "Basic YWxpY2U6cw=="])
    }

    func testDigestChallengeParsingAndHeaderGeneration() throws {
        let header = "Basic realm=\"other\", Digest realm=\"feed.example\", nonce=\"abc123nonce\", " +
                     "qop=\"auth,auth-int\", opaque=\"op-1\", algorithm=MD5"
        let challenge = try XCTUnwrap(DigestAuthorization.parse(header))
        XCTAssertEqual(challenge.realm, "feed.example")
        XCTAssertEqual(challenge.nonce, "abc123nonce")
        XCTAssertEqual(challenge.qop, "auth")
        XCTAssertEqual(challenge.opaque, "op-1")
        XCTAssertNil(DigestAuthorization.parse("Basic realm=\"only\""))

        let authorization = try XCTUnwrap(DigestAuthorization.authorizationHeader(
            challenge: challenge, username: "alice", password: "p@ss",
            method: "GET", uri: "/rss.xml", cnonce: "0a4f113b"))
        XCTAssertTrue(authorization.hasPrefix("Digest "))
        XCTAssertTrue(authorization.contains("response=\"b02e2c58b702b6a6125fd20d154923d9\""))
        XCTAssertTrue(authorization.contains("nc=00000001"))
        XCTAssertTrue(authorization.contains("cnonce=\"0a4f113b\""))
        XCTAssertFalse(authorization.contains("p@ss"))
        let legacy = DigestChallenge(realm: "feed.example", nonce: "abc123nonce")
        let legacyHeader = try XCTUnwrap(DigestAuthorization.authorizationHeader(
            challenge: legacy, username: "alice", password: "p@ss",
            method: "GET", uri: "/rss.xml", cnonce: "0a4f113b"))
        XCTAssertTrue(legacyHeader.contains("response=\"a39c50b04e3429dff6760d8c536095d8\""))
    }

    private func assertTransportError(_ expected: SourceTransport.ValidationError,
                                      file: StaticString = #filePath, line: UInt = #line,
                                      operation: () throws -> Void) {
        do {
            try operation()
            XCTFail("Expected \(expected)", file: file, line: line)
        } catch {
            XCTAssertEqual(error as? SourceTransport.ValidationError, expected, file: file, line: line)
        }
    }

    // MARK: - Helpers

    private func assertAdapterError(_ expected: FeedAdapterError,
                                    file: StaticString = #filePath, line: UInt = #line,
                                    operation: () throws -> Void) {
        do {
            try operation()
            XCTFail("Expected \(expected)", file: file, line: line)
        } catch {
            XCTAssertEqual(error as? FeedAdapterError, expected, file: file, line: line)
        }
    }
}
