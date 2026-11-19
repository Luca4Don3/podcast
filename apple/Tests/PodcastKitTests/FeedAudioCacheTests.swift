import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
import XCTest
@testable import PodcastKit

final class FeedAudioCacheTests: XCTestCase {
    func testPolicyCountsAndPersistence() throws {
        XCTAssertEqual(FeedCachePolicy.allCases.map { $0.count(available: 12) }, [0, 5, 10, 12])
        XCTAssertEqual(FeedCachePolicy.latestTen.count(available: 2), 2)
        XCTAssertEqual(FeedCachePolicy.all.count(available: -1), 0)
        for policy in FeedCachePolicy.allCases {
            let data = try JSONEncoder().encode(policy)
            XCTAssertEqual(try JSONDecoder().decode(FeedCachePolicy.self, from: data), policy)
        }
    }

    func testDirectEnclosuresAndOrigin() {
        let origin = URL(string: "https://example.test/feed")!
        XCTAssertTrue(FeedCacheMedia.sameOrigin(origin, URL(string: "https://EXAMPLE.test:443/audio.mp3")!))
        for address in ["http://example.test/a.mp3", "https://example.test:444/a.mp3",
                        "https://cdn.example.test/a.mp3", "https://u:p@example.test/a.mp3"] {
            XCTAssertFalse(FeedCacheMedia.sameOrigin(origin, URL(string: address)!))
        }
        XCTAssertEqual(FeedCacheMedia.fileExtension(URL(string: "https://example.test/audio.MP3?key=fixture")!), "mp3")
        XCTAssertEqual(FeedCacheMedia.fileExtension(URL(string: "https://example.test/download")!), "audio")
        for address in ["https://example.test/live.m3u8", "https://example.test/index.html", "smb://nas/a.mp3",
                        "file:///tmp/a.mp3", "https://u:p@example.test/a.mp3"] {
            XCTAssertNil(FeedCacheMedia.fileExtension(URL(string: address)!))
        }
    }

    func testMockResponseRejectsNonDownloads() {
        let origin = URL(string: "https://example.test/a.mp3")!
        func response(status: Int = 200, headers: [String: String], url: URL? = nil) -> HTTPURLResponse {
            HTTPURLResponse(url: url ?? origin, statusCode: status, httpVersion: "HTTP/1.1", headerFields: headers)!
        }
        func size(_ response: HTTPURLResponse) -> Int64? { FeedCacheMedia.expectedSize(response, for: origin) }
        XCTAssertEqual(size(response(headers: ["Content-Type": "audio/mpeg", "Content-Length": "32"])), 32)
        XCTAssertEqual(size(response(headers: ["Content-Type": "audio/mpeg"])), -1)
        XCTAssertNil(size(response(status: 206, headers: ["Content-Type": "audio/mpeg"])))
        XCTAssertNil(size(response(headers: ["Content-Type": "audio/mpeg", "Content-Range": "bytes 0-31/128"])))
        XCTAssertNil(size(response(headers: ["Content-Type": "audio/mpeg", "Icy-Name": "radio"])))
        XCTAssertNil(size(response(headers: ["Content-Type": "audio/mpeg", "Content-Encoding": "gzip"])))
        XCTAssertNil(size(response(headers: ["Content-Type": "audio/mpeg", "Content-Length": "2147483649"])))
        XCTAssertNil(size(response(headers: ["Content-Type": "audio/x-mpegurl"])))
        XCTAssertNil(size(response(headers: ["Content-Type": "text/html"])))
        XCTAssertNil(size(response(headers: ["Content-Type": "audio/mpeg"], url: URL(string: "https://cdn.example.test/a.mp3"))))
        XCTAssertTrue(FeedCacheMedia.hasAudioSignature(Data("ID3\0audio".utf8)))
        XCTAssertFalse(FeedCacheMedia.hasAudioSignature(Data("#EXTM3U\n#EXT-X-VERSION:3".utf8)))
        XCTAssertFalse(FeedCacheMedia.hasAudioSignature(Data("<!doctype html>".utf8)))
        XCTAssertFalse(FeedCacheMedia.hasAudioSignature(Data()))
    }

#if canImport(CryptoKit)
    func testLatestStableOrderAndDeduplication() throws {
        let items = try (0..<12).map { index in
            try XCTUnwrap(FeedAudioCache.Item(episodeID: String(index), mediaURL: URL(string: "https://example.test/a.mp3")!))
        }
        var episodes = items.map { (item: $0, date: nil as String?) }
        episodes[10].date = "2025-02-02T10:00:00.500Z"
        episodes[11].date = "Mon, 3 Feb 2025 10:00:00 +0000"
        episodes.append(episodes[11])
        let selected = FeedAudioCache.plan(episodes, policy: .latestFive)
        XCTAssertEqual(selected.map(\.name), [items[11], items[10], items[0], items[1], items[2]].map(\.name))
        XCTAssertEqual(FeedAudioCache.plan(episodes, policy: .latestTen).count, 10)
        XCTAssertEqual(FeedAudioCache.plan(episodes, policy: .all).count, 12)
        XCTAssertTrue(FeedAudioCache.plan(episodes, policy: .none).isEmpty)
    }

    func testIsolatedHashedPathsIntegrityAndPruning() throws {
        let base = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        defer { try? FileManager.default.removeItem(at: base) }
        let feedID = "https://example.test/rss?key=fixture-only"
        let cache = try FeedAudioCache(feedID: feedID, applicationSupport: base)
        let other = try FeedAudioCache(feedID: feedID + "2", applicationSupport: base)
        try cache.prepare()
        try other.prepare()
        let item = try XCTUnwrap(FeedAudioCache.Item(episodeID: "episode/one", mediaURL: URL(string: "https://example.test/live.mp3?key=fixture")!))
        XCTAssertNotEqual(cache.directory, other.directory)
        XCTAssertNotNil(cache.directory.lastPathComponent.range(of: "^[a-f0-9]{64}$", options: .regularExpression))
        XCTAssertNotNil(item.name.range(of: "^[a-f0-9]{64}\\.mp3$", options: .regularExpression))
        XCTAssertNotEqual(item.name, FeedAudioCache.Item(episodeID: "episode/one", mediaURL: URL(string: "https://example.test/reissued.mp3")!)?.name)
        let part = cache.partialURL(for: item)
        XCTAssertEqual(part.lastPathComponent, item.name + ".part")
        let payload = Data("fixture-audio".utf8)
        try payload.write(to: part)
        XCTAssertThrowsError(try cache.commit(item, expectedSize: 1))
        XCTAssertNil(cache.localURL(for: item))
        try payload.write(to: part)
        try cache.commit(item, expectedSize: Int64(payload.count))
        XCTAssertEqual(try Data(contentsOf: XCTUnwrap(cache.localURL(for: item))), payload)
        XCTAssertNil(other.localURL(for: item))
        XCTAssertFalse(FileManager.default.fileExists(atPath: part.path))
        try payload.write(to: other.partialURL(for: item))
        try other.commit(item, expectedSize: -1)
        try cache.prune(keeping: [item])
        XCTAssertNotNil(cache.localURL(for: item))
        try cache.prune(keeping: [])
        XCTAssertNil(cache.localURL(for: item))
        XCTAssertNotNil(other.localURL(for: item))
    }
#endif
}
