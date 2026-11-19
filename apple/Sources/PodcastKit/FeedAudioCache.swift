import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
#if canImport(CryptoKit)
import CryptoKit
#endif
import PodcastNativeCache

public enum FeedCachePolicy: Int32, CaseIterable, Codable, Sendable {
    case none = 0
    case latestFive = 5
    case latestTen = 10
    case all = -1

    public func count(available: Int) -> Int {
        Int(podcast_cache_target_count(rawValue, Int32(clamping: available)))
    }
}

/// Only direct HTTP(S) enclosures can enter the cache, not network storage or app links.
public enum FeedCacheMedia {
    private static let extensions: Set<String> = [
        "mp3", "m4a", "mp4", "aac", "ogg", "opus", "wav", "flac", "aiff", "caf", "webm", "m4b", "wma", "audio",
    ]
    private static let binaryTypes: Set<String> = ["application/octet-stream", "binary/octet-stream", "application/ogg"]
    public static let maximumBytes: Int64 = 2 * 1024 * 1024 * 1024

    private static func origin(_ url: URL) -> (String, String, Int)? {
        guard let parts = URLComponents(url: url, resolvingAgainstBaseURL: false),
              let scheme = parts.scheme?.lowercased(), scheme == "https" || scheme == "http",
              let host = parts.host?.lowercased(), !host.isEmpty,
              parts.user == nil, parts.password == nil else { return nil }
        return (scheme, host, parts.port ?? (scheme == "https" ? 443 : 80))
    }

    public static func sameOrigin(_ lhs: URL?, _ rhs: URL) -> Bool {
        guard let lhs, let a = origin(lhs), let b = origin(rhs) else { return false }
        return a == b
    }

    public static func fileExtension(_ url: URL) -> String? {
        guard origin(url) != nil else { return nil }
        let ext = url.pathExtension.lowercased()
        return ext.isEmpty ? "audio" : (extensions.contains(ext) ? ext : nil)
    }

    /// A complete 200 response, never a partial response, playlist, webpage or ICY stream.
    public static func expectedSize(_ response: HTTPURLResponse, for originalURL: URL) -> Int64? {
        guard response.statusCode == 200, let url = response.url, sameOrigin(originalURL, url),
              fileExtension(url) != nil,
              response.value(forHTTPHeaderField: "Content-Range") == nil,
              !response.allHeaderFields.keys.contains(where: { String(describing: $0).lowercased().hasPrefix("icy-") })
        else { return nil }
        let encoding = response.value(forHTTPHeaderField: "Content-Encoding")?.lowercased()
        guard encoding == nil || encoding == "identity" else { return nil }
        let mime = response.mimeType?.lowercased() ?? ""
        guard mime.isEmpty || binaryTypes.contains(mime) ||
                (mime.hasPrefix("audio/") && !mime.contains("mpegurl") && !mime.contains("playlist"))
        else { return nil }
        let length = response.expectedContentLength
        return length == 0 || length > maximumBytes ? nil : (length < 0 ? -1 : length)
    }

    /// Out-of-band MIME alone cannot make a login page or disguised playlist an audio file.
    public static func hasAudioSignature(_ data: Data) -> Bool {
        data.withUnsafeBytes { bytes in
            podcast_cache_has_media_signature(bytes.bindMemory(to: UInt8.self).baseAddress,
                                              Int32(min(bytes.count, 16))) != 0
        }
    }
}

#if canImport(CryptoKit)
public struct FeedAudioCache: Sendable {
    public struct Item: Sendable {
        public let url: URL
        public let name: String

        public init?(episodeID: String, mediaURL: URL) {
            guard let ext = FeedCacheMedia.fileExtension(mediaURL) else { return nil }
            url = mediaURL
            name = FeedAudioCache.hash("\(episodeID.utf8.count):\(episodeID)\(mediaURL.absoluteString)") + "." + ext
        }
    }

    public enum Failure: Error { case storage, incomplete, unsupported }

    public let directory: URL
    private let root: URL

    /// Use Application Support in production. Tests can pass an isolated temporary base.
    public init(feedID: String, applicationSupport: URL? = nil) throws {
        guard let base = applicationSupport ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
        else { throw Failure.storage }
        root = base.appendingPathComponent("PodcastAudioCache", isDirectory: true)
        directory = root.appendingPathComponent(Self.hash(feedID), isDirectory: true)
    }

    public func prepare() throws {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        var root = root
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try root.setResourceValues(values)
    }

    public func localURL(for item: Item) -> URL? {
        guard podcast_cache_file_size(directory.path, item.name) > 0 else { return nil }
        return directory.appendingPathComponent(item.name)
    }

    public func partialURL(for item: Item) -> URL {
        directory.appendingPathComponent(item.name + ".part")
    }

    public func commit(_ item: Item, expectedSize: Int64) throws {
        guard podcast_cache_commit(directory.path, item.name + ".part", item.name, expectedSize) == 0
        else { throw Failure.incomplete }
    }

    public func prune(keeping items: [Item]) throws {
        guard FileManager.default.fileExists(atPath: directory.path) else { return }
        let keepNames = items.map(\.name).joined(separator: "\n")
        guard podcast_cache_prune(directory.path, keepNames) >= 0 else { throw Failure.storage }
    }

    /// Date-aware, stable for equal or missing dates; duplicate filenames count only once.
    public static func plan(_ episodes: [(item: Item, date: String?)], policy: FeedCachePolicy) -> [Item] {
        let iso = ISO8601DateFormatter()
        let fractional = ISO8601DateFormatter()
        fractional.formatOptions.insert(.withFractionalSeconds)
        let rfc = DateFormatter()
        rfc.locale = Locale(identifier: "en_US_POSIX")
        var ordered: [(Int, Item, Date)] = []
        for (index, episode) in episodes.enumerated() {
            let date = episode.date.flatMap { value -> Date? in
                if let date = iso.date(from: value) ?? fractional.date(from: value) { return date }
                for format in ["EEE, d MMM yyyy HH:mm:ss Z", "d MMM yyyy HH:mm:ss Z"] {
                    rfc.dateFormat = format
                    if let date = rfc.date(from: value) { return date }
                }
                return nil
            }
            ordered.append((index, episode.item, date ?? .distantPast))
        }
        ordered.sort { a, b in a.2 == b.2 ? a.0 < b.0 : a.2 > b.2 }
        var seen = Set<String>()
        let items = ordered.map { $0.1 }.filter { seen.insert($0.name).inserted }
        return Array(items.prefix(policy.count(available: items.count)))
    }

    private static func hash(_ value: String) -> String {
        SHA256.hash(data: Data(value.utf8)).map { String(format: "%02x", $0) }.joined()
    }
}
#endif
