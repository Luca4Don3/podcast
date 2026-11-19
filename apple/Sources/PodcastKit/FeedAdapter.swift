import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// How a single feed source authenticates. Never stored inside a feed URL.
public enum CredentialKind: String, Codable, Hashable, Sendable {
    case none, basic, bearer, apiKey
}

/// Credentials for one feed source. The secret is never written into a stored URL.
public struct SourceCredentials: Equatable, Codable, Sendable {
    public static let defaultAPIKeyHeader = "X-API-Key"
    private static let headerNamePunctuation = Set("!#$%&'*+-.^`|~".utf8)

    public let kind: CredentialKind
    public let username: String
    public let secret: String
    public let header: String

    public init(kind: CredentialKind = .none, username: String = "", secret: String = "", header: String = "") {
        self.kind = kind
        self.username = username
        self.secret = secret
        self.header = header
    }

    /// HTTP headers applied to the feed request, empty for `.none` or an empty secret.
    public var httpHeaders: [String: String] {
        switch kind {
        case .none:
            return [:]
        case .basic:
            let token = Data("\(username):\(secret)".utf8).base64EncodedString()
            return ["Authorization": "Basic \(token)"]
        case .bearer:
            let value = Self.safeHeaderValue(secret)
            return value.isEmpty ? [:] : ["Authorization": "Bearer \(value)"]
        case .apiKey:
            let value = Self.safeHeaderValue(secret)
            return value.isEmpty ? [:] : [Self.safeHeaderName(header): value]
        }
    }

    /// Rejects CRLF and other malformed header names before a URLRequest is built.
    public static func safeHeaderName(_ raw: String) -> String {
        let candidate = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !candidate.isEmpty, candidate.utf8.allSatisfy({ byte in
            (48...57).contains(byte) || (65...90).contains(byte) || (97...122).contains(byte)
                || headerNamePunctuation.contains(byte)
        }) else { return defaultAPIKeyHeader }
        return candidate
    }

    private static func safeHeaderValue(_ raw: String) -> String {
        String(raw.filter { $0 != "\r" && $0 != "\n" })
    }
}

/// A validated, non-networking description of where a podcast feed comes from.
public enum FeedReference: Equatable, Sendable {
    case directFeed(URL, SourceCredentials)
    case appleLookup(URL)
    case webMetadata(URL, platform: String)
}

public struct ParsedEpisode: Identifiable, Equatable, Sendable {
    public let id: String
    public let title: String
    public let audioUrl: URL
    public let publishedAt: String?
    public let durationSeconds: Int?

    public init(id: String, title: String, audioUrl: URL, publishedAt: String?, durationSeconds: Int?) {
        self.id = id
        self.title = title
        self.audioUrl = audioUrl
        self.publishedAt = publishedAt
        self.durationSeconds = durationSeconds
    }
}

public struct ParsedFeed: Identifiable, Equatable, Sendable {
    public let id: String
    public let title: String
    public let description: String?
    public let episodes: [ParsedEpisode]

    public init(id: String, title: String, description: String?, episodes: [ParsedEpisode]) {
        self.id = id
        self.title = title
        self.description = description
        self.episodes = episodes
    }
}

public struct OpmlFeed: Equatable, Sendable {
    public let title: String
    public let url: URL

    public init(title: String, url: URL) {
        self.title = title
        self.url = url
    }
}

/// All errors carry fixed, localized text. Input strings are never echoed.
public enum FeedAdapterError: LocalizedError, Equatable, Sendable {
    case invalidReference
    case inputTooLarge
    case malformedFeed
    case malformedOPML
    case itemLimitExceeded
    case externalEntityForbidden

    public var errorDescription: String? {
        switch self {
        case .invalidReference: return "播客源地址无效或不受支持。"
        case .inputTooLarge: return "播客源内容超过大小限制。"
        case .malformedFeed: return "播客源内容格式不正确。"
        case .malformedOPML: return "OPML 订阅列表格式不正确。"
        case .itemLimitExceeded: return "播客源条目数量超过上限。"
        case .externalEntityForbidden: return "播客源包含不允许的 XML 实体声明。"
        }
    }
}

public enum PlatformFeedAdapter {
    private static let itemLimit = 500

    // MARK: - Link normalization

    public static func normalize(_ input: String,
                                 credentials: SourceCredentials = SourceCredentials()) throws -> FeedReference {
        let canonical = try canonicalComponents(from: input)
        let components = canonical.components
        // Explicit UI input takes precedence over credentials embedded in the URL.
        let resolvedCredentials = credentials.kind == .none ? canonical.credentials : credentials
        let host = (components.host ?? "").lowercased()
        let segments = (components.percentEncodedPath as NSString).pathComponents
        let decoded = segments.compactMap { $0.removingPercentEncoding }
        guard decoded.count == segments.count else { throw FeedAdapterError.invalidReference }
        let parts = decoded.filter { !$0.isEmpty && $0 != "/" }

        if host == "podcasts.apple.com" {
            guard let last = parts.last, last.hasPrefix("id") else {
                throw FeedAdapterError.invalidReference
            }
            let digits = String(last.dropFirst(2))
            guard !digits.isEmpty, digits.utf8.allSatisfy({ (48...57).contains($0) }),
                  let lookup = URL(string: "https://itunes.apple.com/lookup?id=\(digits)&entity=podcast") else {
                throw FeedAdapterError.invalidReference
            }
            return .appleLookup(lookup)
        }

        let platform: String
        let expectedPath: String
        switch host {
        case "open.spotify.com":
            platform = "spotify"
            expectedPath = "show"
        case "www.xiaoyuzhoufm.com":
            platform = "xiaoyuzhoufm"
            expectedPath = "podcast"
        case "www.ximalaya.com":
            platform = "ximalaya"
            expectedPath = "album"
        default:
            guard let url = components.url else { throw FeedAdapterError.invalidReference }
            return .directFeed(url, resolvedCredentials)
        }

        guard parts.count == 2, parts[0] == expectedPath, isSafeID(parts[1]) else {
            throw FeedAdapterError.invalidReference
        }
        var resolved = components
        resolved.query = nil
        resolved.percentEncodedQuery = nil
        guard let url = resolved.url else { throw FeedAdapterError.invalidReference }
        // A platform share link stays a web page reference; no RSS URL is invented.
        return .webMetadata(url, platform: platform)
    }

    /// Rejects ambiguous authorities and builds a canonical http(s) URL.
    /// Userinfo and token/api_key/key query items are lifted into credentials and removed.
    private static func canonicalComponents(from input: String) throws -> (components: URLComponents, credentials: SourceCredentials) {
        let value = input.trimmingCharacters(in: .whitespacesAndNewlines)
        let forbidden = CharacterSet.whitespacesAndNewlines.union(.controlCharacters)
        guard !value.isEmpty,
              value.rangeOfCharacter(from: forbidden) == nil,
              !value.contains("\\"),
              var components = URLComponents(string: value),
              let scheme = components.scheme?.lowercased(),
              scheme == "http" || scheme == "https",
              let encodedHost = components.percentEncodedHost,
              !encodedHost.isEmpty,
              components.port.map({ (1...65535).contains($0) }) ?? true,
              let decodedHost = encodedHost.removingPercentEncoding,
              !decodedHost.isEmpty,
              decodedHost.rangeOfCharacter(from: forbidden) == nil,
              !decodedHost.contains("@"), !decodedHost.contains("/"),
              !decodedHost.contains("\\"), !decodedHost.contains("?"),
              !decodedHost.contains("#")
        else { throw FeedAdapterError.invalidReference }

        let embedded = extractCredentials(from: &components)
        components.scheme = scheme
        components.user = nil
        components.password = nil
        components.percentEncodedUser = nil
        components.percentEncodedPassword = nil
        components.fragment = nil
        components.percentEncodedHost = encodedHost.lowercased()
        guard components.url != nil else { throw FeedAdapterError.invalidReference }
        return (components, embedded)
    }

    /// Strips credential-bearing pieces from the URL, returning them as source credentials.
    /// Userinfo wins over query tokens; all credential query items are removed regardless.
    private static func extractCredentials(from components: inout URLComponents) -> SourceCredentials {
        var token: String?
        var apiKey: String?
        if let query = components.percentEncodedQuery {
            var kept: [String] = []
            for pair in query.split(separator: "&", omittingEmptySubsequences: false) {
                let parts = pair.split(separator: "=", maxSplits: 1, omittingEmptySubsequences: false)
                let rawName = parts.first.map(String.init) ?? ""
                let name = (rawName.removingPercentEncoding ?? rawName).lowercased()
                let rawValue = parts.count > 1 ? String(parts[1]) : ""
                let value = rawValue.removingPercentEncoding ?? rawValue
                switch name {
                case "token", "access_token":
                    if token == nil, !value.isEmpty { token = value }
                case "api_key", "apikey", "key":
                    if apiKey == nil, !value.isEmpty { apiKey = value }
                default:
                    kept.append(String(pair))
                }
            }
            components.percentEncodedQuery = kept.isEmpty ? nil : kept.joined(separator: "&")
        }
        if let user = components.user, !user.isEmpty {
            return SourceCredentials(kind: .basic, username: user, secret: components.password ?? "")
        }
        if let token { return SourceCredentials(kind: .bearer, secret: token) }
        if let apiKey { return SourceCredentials(kind: .apiKey, secret: apiKey) }
        return SourceCredentials()
    }

    private static func isSafeID(_ value: String) -> Bool {
        guard (1...128).contains(value.utf8.count) else { return false }
        return value.utf8.allSatisfy { byte in
            (48...57).contains(byte) || (65...90).contains(byte) || (97...122).contains(byte)
        }
    }

    // MARK: - Feed parsing

    public static func parseFeed(_ data: Data) throws -> ParsedFeed {
        try XMLInput.check(data, malformed: .malformedFeed)
        let delegate = FeedParserDelegate()
        let parser = XMLInput.parser(for: data)
        parser.delegate = delegate
        guard parser.parse(), delegate.failure == nil else {
            throw delegate.failure ?? FeedAdapterError.malformedFeed
        }
        guard delegate.rootElement != nil else { throw FeedAdapterError.malformedFeed }
        return delegate.makeFeed()
    }

    // MARK: - OPML parsing

    public static func parseOPML(_ data: Data) throws -> [OpmlFeed] {
        try XMLInput.check(data, malformed: .malformedOPML)
        let delegate = OPMLParserDelegate()
        let parser = XMLInput.parser(for: data)
        parser.delegate = delegate
        guard parser.parse(), delegate.failure == nil else {
            throw delegate.failure ?? FeedAdapterError.malformedOPML
        }
        guard delegate.rootElement != nil else { throw FeedAdapterError.malformedOPML }
        return delegate.feeds
    }
}

// MARK: - XML hardening

private enum XMLInput {
    static let maximumBytes = 4 * 1024 * 1024

    static func check(_ data: Data, malformed: FeedAdapterError) throws {
        guard data.count <= maximumBytes else {
            throw FeedAdapterError.inputTooLarge
        }
        let text = String(decoding: data, as: UTF8.self).uppercased()
        guard !text.contains("<!DOCTYPE"), !text.contains("<!ENTITY") else {
            throw FeedAdapterError.externalEntityForbidden
        }
    }

    static func parser(for data: Data) -> XMLParser {
        let parser = XMLParser(data: data)
        // External DTDs/entities are never resolved; delegate callbacks are a second barrier.
        parser.shouldResolveExternalEntities = false
        return parser
    }
}

private class SecureXMLDelegate: NSObject, XMLParserDelegate, @unchecked Sendable {
    private(set) var failure: FeedAdapterError?
    var rootElement: String?
    var parseError: FeedAdapterError { .malformedFeed }
    private var textStack: [String] = []

    func reject(_ error: FeedAdapterError, parser: XMLParser) {
        guard failure == nil else { return }
        failure = error
        parser.abortParsing()
    }

    func pushText() { textStack.append("") }

    func appendText(_ text: String) {
        guard !textStack.isEmpty else { return }
        textStack[textStack.count - 1] += text
    }

    @discardableResult
    func popText() -> String {
        textStack.isEmpty ? "" : textStack.removeLast()
    }

    func parser(_ parser: XMLParser, foundCharacters string: String) {
        appendText(string)
    }

    func parser(_ parser: XMLParser, foundExternalEntityDeclarationWithName name: String,
                publicID: String?, systemID: String?) {
        reject(.externalEntityForbidden, parser: parser)
    }

    func parser(_ parser: XMLParser, foundInternalEntityDeclarationWithName name: String,
                value: String?) {
        reject(.externalEntityForbidden, parser: parser)
    }

    func parser(_ parser: XMLParser, resolveExternalEntityName name: String,
                systemID: String?) -> Data? {
        reject(.externalEntityForbidden, parser: parser)
        return nil
    }

    func parser(_ parser: XMLParser, parseErrorOccurred parseError: Error) {
        if failure == nil { failure = self.parseError }
    }

    static func localName(_ raw: String) -> String {
        guard let index = raw.lastIndex(of: ":") else { return raw }
        return String(raw[raw.index(after: index)...])
    }

    static func attribute(_ key: String, in attributes: [String: String]) -> String? {
        if let value = attributes[key] { return value }
        for (name, value) in attributes where name.caseInsensitiveCompare(key) == .orderedSame {
            return value
        }
        return nil
    }

    static func safeHTTPURL(_ raw: String) -> URL? {
        let value = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        let forbidden = CharacterSet.whitespacesAndNewlines.union(.controlCharacters)
        guard !value.isEmpty,
              value.rangeOfCharacter(from: forbidden) == nil,
              !value.contains("\\"),
              let components = URLComponents(string: value),
              let scheme = components.scheme?.lowercased(),
              scheme == "http" || scheme == "https",
              let host = components.percentEncodedHost, !host.isEmpty,
              components.user == nil, components.password == nil,
              components.percentEncodedUser == nil, components.percentEncodedPassword == nil,
              components.port.map({ (1...65535).contains($0) }) ?? true,
              components.url != nil else { return nil }
        return components.url
    }
}

private struct EpisodeBuilder {
    var id: String?
    var title: String?
    var publishedAt: String?
    var durationSeconds: Int?
    var audio: [(priority: Int, url: URL)] = []

    mutating func addAudio(_ url: URL, priority: Int) {
        guard !audio.contains(where: { $0.url == url }) else { return }
        audio.append((priority, url))
    }

    func makeEpisode() -> ParsedEpisode? {
        // An episode without a safe http(s) enclosure is dropped, never emitted with a bad URL.
        guard let audio = audio.sorted(by: { $0.priority > $1.priority }).first?.url else { return nil }
        let identifier: String
        if let id, !id.isEmpty {
            identifier = id
        } else if let title, !title.isEmpty {
            identifier = title
        } else {
            identifier = audio.absoluteString
        }
        return ParsedEpisode(id: identifier, title: title ?? "", audioUrl: audio,
                             publishedAt: publishedAt, durationSeconds: durationSeconds)
    }
}

private final class FeedParserDelegate: SecureXMLDelegate, @unchecked Sendable {
    private(set) var episodes: [ParsedEpisode] = []
    private var feedTitle = ""
    private var feedDescription: String?
    private var channelLink: String?
    private var atomID: String?
    private var inChannel = false
    private var current: EpisodeBuilder?

    func makeFeed() -> ParsedFeed {
        let identifier: String
        if let atomID, !atomID.isEmpty {
            identifier = atomID
        } else if let channelLink, !channelLink.isEmpty {
            identifier = channelLink
        } else {
            identifier = feedTitle
        }
        return ParsedFeed(id: identifier, title: feedTitle,
                          description: feedDescription, episodes: episodes)
    }

    func parser(_ parser: XMLParser, didStartElement elementName: String,
                         namespaceURI: String?, qualifiedName qName: String?,
                         attributes attributeDict: [String: String] = [:]) {
        let name = Self.localName(elementName)
        if rootElement == nil {
            rootElement = name
            guard name == "rss" || name == "feed" else {
                reject(.malformedFeed, parser: parser)
                return
            }
        }
        pushText()
        switch name {
        case "channel":
            inChannel = true
        case "item", "entry":
            guard episodes.count < 500 else {
                reject(.itemLimitExceeded, parser: parser)
                return
            }
            current = EpisodeBuilder()
        case "enclosure":
            if let raw = Self.attribute("url", in: attributeDict),
               let audio = Self.safeHTTPURL(raw) {
                current?.addAudio(audio, priority: 2)
            }
        case "link":
            guard let href = Self.attribute("href", in: attributeDict) else { break }
            let rel = Self.attribute("rel", in: attributeDict)?.lowercased()
            let type = Self.attribute("type", in: attributeDict)?.lowercased()
            if current != nil {
                if rel == "enclosure" || (type?.hasPrefix("audio/") ?? false) {
                    if let audio = Self.safeHTTPURL(href) { current?.addAudio(audio, priority: 2) }
                } else if rel == nil && type == nil {
                    if let audio = Self.safeHTTPURL(href) { current?.addAudio(audio, priority: 1) }
                }
            } else if inChannel || rootElement == "feed",
                      rel == nil || rel == "alternate",
                      channelLink == nil,
                      let url = Self.safeHTTPURL(href) {
                channelLink = url.absoluteString
            }
        default:
            break
        }
    }

    func parser(_ parser: XMLParser, didEndElement elementName: String,
                         namespaceURI: String?, qualifiedName qName: String?) {
        let name = Self.localName(elementName)
        let text = popText().trimmingCharacters(in: .whitespacesAndNewlines)
        switch name {
        case "title":
            if current != nil {
                current?.title = text
            } else if feedTitle.isEmpty {
                feedTitle = text
            }
        case "description", "subtitle":
            if current == nil, feedDescription == nil, !text.isEmpty { feedDescription = text }
        case "link":
            if inChannel, current == nil, channelLink == nil, let url = Self.safeHTTPURL(text) {
                channelLink = url.absoluteString
            }
        case "id", "guid":
            if current != nil {
                current?.id = text
            } else if name == "id", atomID == nil, !text.isEmpty {
                atomID = text
            }
        case "pubDate", "published", "updated":
            if current != nil { current?.publishedAt = text.isEmpty ? nil : text }
        case "duration":
            if current != nil { current?.durationSeconds = Self.duration(in: text) }
        case "item", "entry":
            if let episode = current?.makeEpisode() { episodes.append(episode) }
            current = nil
        case "channel":
            inChannel = false
        default:
            break
        }
    }

    private static func duration(in raw: String) -> Int? {
        let value = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !value.isEmpty else { return nil }
        let parts = value.split(separator: ":", omittingEmptySubsequences: false)
        guard (1...3).contains(parts.count) else { return nil }
        var total = 0
        for part in parts {
            guard !part.isEmpty, part.utf8.allSatisfy({ (48...57).contains($0) }),
                  let number = Int(part) else { return nil }
            total = total * 60 + number
        }
        return total
    }
}

private final class OPMLParserDelegate: SecureXMLDelegate, @unchecked Sendable {
    private(set) var feeds: [OpmlFeed] = []
    private var seen: Set<String> = []

    override var parseError: FeedAdapterError { .malformedOPML }

    func parser(_ parser: XMLParser, didStartElement elementName: String,
                         namespaceURI: String?, qualifiedName qName: String?,
                         attributes attributeDict: [String: String] = [:]) {
        let name = Self.localName(elementName)
        if rootElement == nil {
            rootElement = name
            guard name == "opml" else {
                reject(.malformedOPML, parser: parser)
                return
            }
        }
        pushText()
        guard name == "outline",
              let raw = Self.attribute("xmlUrl", in: attributeDict),
              let url = Self.safeHTTPURL(raw),
              seen.insert(Self.key(for: url)).inserted else { return }
        guard feeds.count < 500 else {
            reject(.itemLimitExceeded, parser: parser)
            return
        }
        let title = (Self.attribute("title", in: attributeDict)
                     ?? Self.attribute("text", in: attributeDict) ?? "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        feeds.append(OpmlFeed(title: title, url: url))
    }

    func parser(_ parser: XMLParser, didEndElement elementName: String,
                         namespaceURI: String?, qualifiedName qName: String?) {
        popText()
    }

    private static func key(for url: URL) -> String {
        guard let components = URLComponents(url: url, resolvingAgainstBaseURL: false) else {
            return url.absoluteString
        }
        var canonical = components
        canonical.scheme = components.scheme?.lowercased()
        canonical.percentEncodedHost = components.percentEncodedHost?.lowercased()
        canonical.fragment = nil
        return canonical.string ?? url.absoluteString
    }
}
