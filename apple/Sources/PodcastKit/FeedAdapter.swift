import CoreFoundation
import Foundation
#if canImport(FoundationXML)
import FoundationXML
#endif
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// How a single feed source authenticates. Never stored inside a feed URL.
public enum CredentialKind: String, Codable, Hashable, Sendable {
    case none, basic, bearer, apiKey, digest
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
        case .digest:
            // HTTP Digest is challenge-only: never pre-send a static Authorization header.
            return [:]
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
        String(raw.filter { scalar in
            guard let value = scalar.unicodeScalars.first?.value else { return false }
            return value >= 0x20 && value != 0x7f
        })
    }
}

/// Per-source transport policy. Secrets live only in the platform Keychain, never in subscription JSON.
public struct SourceTransport: Equatable, Codable, Sendable {
    public let requireHttps: Bool
    public let customCaPem: String
    public let clientCertPem: String
    public let clientKeyPem: String
    public let clientKeyPassword: String
    public let digest: Bool

    public init(requireHttps: Bool = false, customCaPem: String = "", clientCertPem: String = "",
                clientKeyPem: String = "", clientKeyPassword: String = "", digest: Bool = false) {
        self.requireHttps = requireHttps
        self.customCaPem = customCaPem
        self.clientCertPem = clientCertPem
        self.clientKeyPem = clientKeyPem
        self.clientKeyPassword = clientKeyPassword
        self.digest = digest
    }

    public enum ValidationError: LocalizedError, Equatable, Sendable {
        case invalidCertificatePEM
        case incompleteClientCertificate
        case invalidPrivateKeyPEM
        case passwordRequired

        public var errorDescription: String? {
            switch self {
            case .invalidCertificatePEM: return "证书 PEM 格式无效。"
            case .incompleteClientCertificate: return "客户端证书与私钥必须同时填写。"
            case .invalidPrivateKeyPEM: return "客户端私钥 PEM 格式无效。"
            case .passwordRequired: return "加密私钥需要填写密码。"
            }
        }
    }

    /// Validates PEM shapes and key/certificate pairing before anything is persisted.
    public func validate() throws {
        if !customCaPem.isEmpty, Self.pemBody(customCaPem, label: "CERTIFICATE") == nil {
            throw ValidationError.invalidCertificatePEM
        }
        let hasCert = !clientCertPem.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        let hasKey = !clientKeyPem.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        guard hasCert == hasKey else { throw ValidationError.incompleteClientCertificate }
        if hasCert {
            guard Self.pemBody(clientCertPem, label: "CERTIFICATE") != nil else {
                throw ValidationError.invalidCertificatePEM
            }
            guard let label = Self.privateKeyLabel(clientKeyPem),
                  Self.pemBody(clientKeyPem, label: label) != nil else {
                throw ValidationError.invalidPrivateKeyPEM
            }
            if label == "ENCRYPTED PRIVATE KEY", clientKeyPassword.isEmpty {
                throw ValidationError.passwordRequired
            }
        }
    }

    /// A source that requires HTTPS must never open an http:// URL.
    public func allows(_ url: URL) -> Bool {
        !requireHttps || url.scheme?.lowercased() == "https"
    }

    /// Redirects must not downgrade HTTPS when the source requires it.
    public func allowsRedirect(from original: URL?, to target: URL) -> Bool {
        guard allows(target) else { return false }
        guard let original, original.scheme?.lowercased() == "https" else { return true }
        return target.scheme?.lowercased() == "https"
    }

    /// Authentication headers and client certificates must be dropped across hosts, ports or downgrades.
    public static func shouldStripCredentials(from original: URL?, to target: URL) -> Bool {
        guard let original else { return true }
        let sameHost = original.host?.lowercased() == target.host?.lowercased()
        let samePort = original.port == target.port
        let downgraded = original.scheme?.lowercased() == "https" && target.scheme?.lowercased() != "https"
        return !sameHost || !samePort || downgraded
    }

    private static let privateKeyLabels = ["PRIVATE KEY", "RSA PRIVATE KEY", "EC PRIVATE KEY", "ENCRYPTED PRIVATE KEY"]

    private static func privateKeyLabel(_ pem: String) -> String? {
        privateKeyLabels.first { pemBody(pem, label: $0) != nil }
    }

    /// Extracts a base64 PEM body, tolerating legacy header lines (e.g. Proc-Type).
    private static func pemBody(_ pem: String, label: String) -> String? {
        let begin = "-----BEGIN \(label)-----"
        let end = "-----END \(label)-----"
        guard let start = pem.range(of: begin),
              let finish = pem.range(of: end, range: start.upperBound..<pem.endIndex) else { return nil }
        let lines = pem[start.upperBound..<finish.lowerBound].split(whereSeparator: \.isNewline)
        let base64 = lines.map(String.init)
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty && !$0.contains(":") }
            .joined()
        guard !base64.isEmpty, let data = Data(base64Encoded: base64), !data.isEmpty else { return nil }
        return base64
    }
}

/// A parsed WWW-Authenticate Digest challenge.
public struct DigestChallenge: Equatable, Sendable {
    public let realm: String
    public let nonce: String
    public let qop: String?
    public let opaque: String?
    public let algorithm: String?
    public let stale: String?

    public init(realm: String, nonce: String, qop: String? = nil, opaque: String? = nil,
                algorithm: String? = nil, stale: String? = nil) {
        self.realm = realm
        self.nonce = nonce
        self.qop = qop
        self.opaque = opaque
        self.algorithm = algorithm
        self.stale = stale
    }
}

/// Pure RFC 7616 Digest computation, used after a 401 challenge — never for a pre-emptive header.
public enum DigestAuthorization {
    /// Parses the Digest parameters out of a (possibly multi-scheme) WWW-Authenticate value.
    public static func parse(_ header: String) -> DigestChallenge? {
        let fields = splitFields(header)
        var params: [String] = []
        var found = false
        for field in fields {
            let trimmed = field.trimmingCharacters(in: .whitespaces)
            if found {
                params.append(trimmed)
                continue
            }
            if trimmed.caseInsensitiveCompare("Digest") == .orderedSame {
                found = true
            } else if trimmed.lowercased().hasPrefix("digest ") {
                found = true
                params.append(String(trimmed.dropFirst("digest ".count)))
            }
        }
        guard found else { return nil }
        var realm: String?
        var nonce: String?
        var qop: String?
        var opaque: String?
        var algorithm: String?
        var stale: String?
        for param in params {
            guard let equals = param.firstIndex(of: "=") else { break }
            let key = String(param[..<equals]).trimmingCharacters(in: .whitespaces).lowercased()
            var value = String(param[param.index(after: equals)...]).trimmingCharacters(in: .whitespaces)
            if value.count >= 2, value.hasPrefix("\""), value.hasSuffix("\"") {
                value = String(value.dropFirst().dropLast())
            }
            switch key {
            case "realm": realm = value
            case "nonce": nonce = value
            case "qop": qop = value.split(separator: ",")
                .map { $0.trimmingCharacters(in: .whitespaces).lowercased() }
                .first(where: { $0 == "auth" }) ?? value.split(separator: ",")
                .map { $0.trimmingCharacters(in: .whitespaces) }.first
            case "opaque": opaque = value
            case "algorithm": algorithm = value
            case "stale": stale = value
            default: break
            }
        }
        guard let realm, let nonce, !realm.isEmpty, !nonce.isEmpty else { return nil }
        return DigestChallenge(realm: realm, nonce: nonce, qop: qop, opaque: opaque,
                               algorithm: algorithm, stale: stale)
    }

    /// Builds the challenge response. cnonce is injectable for deterministic tests.
    public static func authorizationHeader(challenge: DigestChallenge, username: String, password: String,
                                           method: String, uri: String, cnonce: String? = nil) -> String? {
        guard !username.isEmpty, !challenge.realm.isEmpty, !challenge.nonce.isEmpty else { return nil }
        let nonceCount = "00000001"
        let clientNonce = cnonce ?? randomNonce()
        let ha1 = MD5Hash.hex("\(username):\(challenge.realm):\(password)")
        let ha2 = MD5Hash.hex("\(method):\(uri)")
        let response: String
        if let qop = challenge.qop?.lowercased(), qop == "auth" || qop == "auth-int" {
            response = MD5Hash.hex("\(ha1):\(challenge.nonce):\(nonceCount):\(clientNonce):\(qop):\(ha2)")
        } else {
            response = MD5Hash.hex("\(ha1):\(challenge.nonce):\(ha2)")
        }
        var parts = [
            "username=\"\(escape(username))\"",
            "realm=\"\(escape(challenge.realm))\"",
            "nonce=\"\(escape(challenge.nonce))\"",
            "uri=\"\(escape(uri))\"",
            "response=\"\(response)\"",
        ]
        if let algorithm = challenge.algorithm, !algorithm.isEmpty { parts.append("algorithm=\(algorithm)") }
        if let opaque = challenge.opaque, !opaque.isEmpty { parts.append("opaque=\"\(escape(opaque))\"") }
        if let qop = challenge.qop, !qop.isEmpty {
            parts.append("qop=\(qop)")
            parts.append("nc=\(nonceCount)")
            parts.append("cnonce=\"\(escape(clientNonce))\"")
        }
        return "Digest " + parts.joined(separator: ", ")
    }

    private static func escape(_ raw: String) -> String {
        raw.replacingOccurrences(of: "\\", with: "\\\\")
            .replacingOccurrences(of: "\"", with: "\\\"")
    }

    private static func randomNonce() -> String {
        func hex(_ value: UInt64) -> String {
            String(value, radix: 16).padding(toLength: 16, withPad: "0", startingAt: 0)
        }
        var generator = SystemRandomNumberGenerator()
        return hex(UInt64.random(in: UInt64.min...UInt64.max, using: &generator))
            + hex(UInt64.random(in: UInt64.min...UInt64.max, using: &generator))
    }

    private static func splitFields(_ raw: String) -> [String] {
        var fields: [String] = []
        var current = ""
        var quoted = false
        var escaped = false
        for character in raw {
            if escaped { current.append(character); escaped = false; continue }
            if character == "\\", quoted { current.append(character); escaped = true; continue }
            if character == "\"" { quoted.toggle(); current.append(character); continue }
            if character == ",", !quoted { fields.append(current); current = ""; continue }
            current.append(character)
        }
        if !current.isEmpty { fields.append(current) }
        return fields
    }
}

/// Minimal portable MD5 (CryptoKit is unavailable on Linux).
private enum MD5Hash {
    private static let shifts: [UInt32] = [
        7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
        5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
        4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
        6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21,
    ]
    private static let constants: [UInt32] = [
        0xd76aa478, 0xe8c7b756, 0x242070db, 0xc1bdceee, 0xf57c0faf, 0x4787c62a, 0xa8304613, 0xfd469501,
        0x698098d8, 0x8b44f7af, 0xffff5bb1, 0x895cd7be, 0x6b901122, 0xfd987193, 0xa679438e, 0x49b40821,
        0xf61e2562, 0xc040b340, 0x265e5a51, 0xe9b6c7aa, 0xd62f105d, 0x02441453, 0xd8a1e681, 0xe7d3fbc8,
        0x21e1cde6, 0xc33707d6, 0xf4d50d87, 0x455a14ed, 0xa9e3e905, 0xfcefa3f8, 0x676f02d9, 0x8d2a4c8a,
        0xfffa3942, 0x8771f681, 0x6d9d6122, 0xfde5380c, 0xa4beea44, 0x4bdecfa9, 0xf6bb4b60, 0xbebfbc70,
        0x289b7ec6, 0xeaa127fa, 0xd4ef3085, 0x04881d05, 0xd9d4d039, 0xe6db99e5, 0x1fa27cf8, 0xc4ac5665,
        0xf4292244, 0x432aff97, 0xab9423a7, 0xfc93a039, 0x655b59c3, 0x8f0ccc92, 0xffeff47d, 0x85845dd1,
        0x6fa87e4f, 0xfe2ce6e0, 0xa3014314, 0x4e0811a1, 0xf7537e82, 0xbd3af235, 0x2ad7d2bb, 0xeb86d391,
    ]

    static func hex(_ message: String) -> String {
        var bytes = Array(message.utf8)
        let bitLength = UInt64(bytes.count) * 8
        bytes.append(0x80)
        while bytes.count % 64 != 56 { bytes.append(0) }
        for shift in stride(from: 0, to: 64, by: 8) {
            bytes.append(UInt8((bitLength >> UInt64(shift)) & 0xff))
        }
        var a: UInt32 = 0x67452301
        var b: UInt32 = 0xefcdab89
        var c: UInt32 = 0x98badcfe
        var d: UInt32 = 0x10325476
        for chunk in stride(from: 0, to: bytes.count, by: 64) {
            var words = [UInt32](repeating: 0, count: 16)
            for index in 0..<16 {
                let offset = chunk + index * 4
                words[index] = UInt32(bytes[offset])
                    | (UInt32(bytes[offset + 1]) << 8)
                    | (UInt32(bytes[offset + 2]) << 16)
                    | (UInt32(bytes[offset + 3]) << 24)
            }
            var aa = a, bb = b, cc = c, dd = d
            for index in 0..<64 {
                let function: UInt32
                let word: Int
                switch index {
                case 0..<16: function = (bb & cc) | (~bb & dd); word = index
                case 16..<32: function = (dd & bb) | (~dd & cc); word = (5 * index + 1) % 16
                case 32..<48: function = bb ^ cc ^ dd; word = (3 * index + 5) % 16
                default: function = cc ^ (bb | ~dd); word = (7 * index) % 16
                }
                let sum = aa &+ function &+ constants[index] &+ words[word]
                aa = dd
                dd = cc
                cc = bb
                bb = bb &+ ((sum << shifts[index]) | (sum >> (32 - shifts[index])))
            }
            a = a &+ aa
            b = b &+ bb
            c = c &+ cc
            d = d &+ dd
        }
        return [a, b, c, d].map { value in
            var text = ""
            for shift in stride(from: 0, to: 32, by: 8) {
                let byte = Int((value >> UInt32(shift)) & 0xff)
                text += String(byte >> 4, radix: 16) + String(byte & 0x0f, radix: 16)
            }
            return text
        }.joined()
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

    // MARK: - Feed and OPML parsing

    public enum SubscriptionDocument: Equatable, Sendable {
        case feed(ParsedFeed)
        case opml([OpmlFeed])
    }

    /// Select the parser by the XML root, not text in a comment or description.
    public static func parseSubscriptionDocument(_ data: Data) throws -> SubscriptionDocument {
        let input = try XMLInput(data, malformed: .malformedFeed)
        if input.rootName == "opml" { return .opml(try parse(input, with: OPMLParserDelegate()).feeds) }
        return .feed(try parse(input, with: FeedParserDelegate()).makeFeed())
    }

    public static func parseFeed(_ data: Data) throws -> ParsedFeed {
        try parse(XMLInput(data, malformed: .malformedFeed), with: FeedParserDelegate()).makeFeed()
    }

    public static func parseOPML(_ data: Data) throws -> [OpmlFeed] {
        try parse(XMLInput(data, malformed: .malformedOPML), with: OPMLParserDelegate()).feeds
    }

    private static func parse<Delegate: SecureXMLDelegate>(_ input: XMLInput, with delegate: Delegate) throws -> Delegate {
        let parser = XMLParser(data: input.data)
        // External DTDs/entities are never resolved; delegate callbacks are a second barrier.
        parser.shouldResolveExternalEntities = false
        parser.externalEntityResolvingPolicy = .never
        parser.delegate = delegate
        guard parser.parse(), delegate.failure == nil, delegate.rootElement != nil else {
            throw delegate.failure ?? delegate.parseError
        }
        return delegate
    }
}

// MARK: - XML hardening

private struct XMLInput {
    private static let declarationStart = try! NSRegularExpression(pattern: #"\A<\?xml[ \t\r\n]"#)
    private static let declaration = try! NSRegularExpression(pattern:
        #"\A<\?xml[ \t\r\n]+version[ \t\r\n]*=[ \t\r\n]*(['"])1\.[0-9]+\1(?:[ \t\r\n]+encoding[ \t\r\n]*=[ \t\r\n]*(['"])([A-Za-z][A-Za-z0-9._-]*)\2)?(?:[ \t\r\n]+standalone[ \t\r\n]*=[ \t\r\n]*(['"])(?:yes|no)\4)?[ \t\r\n]*\?>"#)
    private static let nameEnd = CharacterSet(charactersIn: " \t\r\n/>")
    let data: Data
    let rootName: String?

    init(_ source: Data, malformed: FeedAdapterError) throws {
        guard source.count <= 4 * 1024 * 1024 else { throw FeedAdapterError.inputTooLarge }
        var text = try Self.decode(source, malformed: malformed)
        let range = NSRange(text.startIndex..., in: text)
        if Self.declarationStart.firstMatch(in: text, range: range) != nil {
            guard let declaration = Self.declaration.firstMatch(in: text, range: range) else { throw malformed }
            if let encoding = Range(declaration.range(at: 3), in: text) {
                // XMLParser must consume the same characters checked below.
                text.replaceSubrange(encoding, with: "UTF-8")
            }
        }
        rootName = try Self.scan(text as NSString, malformed: malformed)
        data = Data(text.utf8)
    }

    private static func decode(_ data: Data, malformed: FeedAdapterError) throws -> String {
        let prefix = Array(data.prefix(4))
        let encoding: String.Encoding
        if prefix.starts(with: [0, 0, 0xFE, 0xFF]) || prefix.starts(with: [0, 0, 0]) {
            encoding = .utf32BigEndian
        } else if prefix.starts(with: [0xFF, 0xFE, 0, 0]) || prefix.suffix(3).elementsEqual([0, 0, 0]) {
            encoding = .utf32LittleEndian
        } else if prefix.starts(with: [0xFE, 0xFF]) || prefix.first == 0 {
            encoding = .utf16BigEndian
        } else if prefix.starts(with: [0xFF, 0xFE]) || (prefix.count >= 2 && prefix[1] == 0) {
            encoding = .utf16LittleEndian
        } else if !prefix.starts(with: [0xEF, 0xBB, 0xBF]), data.starts(with: "<?xml".utf8),
                  let end = data.range(of: Data("?>".utf8)),
                  let head = String(data: data[..<end.upperBound], encoding: .ascii),
                  let match = declaration.firstMatch(in: head, range: NSRange(head.startIndex..., in: head)),
                  let range = Range(match.range(at: 3), in: head) {
            let cfEncoding = CFStringConvertIANACharSetNameToEncoding(String(head[range]) as CFString)
            guard cfEncoding != kCFStringEncodingInvalidId else { throw malformed }
            encoding = String.Encoding(rawValue: CFStringConvertEncodingToNSStringEncoding(cfEncoding))
        } else {
            encoding = .utf8
        }
        guard var text = String(data: data, encoding: encoding) else { throw malformed }
        if text.unicodeScalars.first?.value == 0xFEFF { text.unicodeScalars.removeFirst() }
        return text
    }

    private static func scan(_ text: NSString, malformed: FeedAdapterError) throws -> String? {
        var next = 0
        var root: String?
        while next < text.length {
            let start = text.range(of: "<", options: .literal,
                                   range: NSRange(location: next, length: text.length - next)).location
            guard start != NSNotFound else { break }
            let remaining = NSRange(location: start, length: text.length - start)
            func starts(_ token: String, options: NSString.CompareOptions = []) -> Bool {
                text.range(of: token, options: options.union([.anchored, .literal]), range: remaining).location == start
            }
            if starts("<!DOCTYPE", options: .caseInsensitive) || starts("<!ENTITY", options: .caseInsensitive) {
                throw FeedAdapterError.externalEntityForbidden
            }
            let section: (opening: Int, closing: String)?
            if starts("<!--") { section = (4, "-->") }
            else if starts("<?") { section = (2, "?>") }
            else if starts("<![CDATA[") { section = (9, "]]>") }
            else { section = nil }
            if let section {
                let content = start + section.opening
                let end = text.range(of: section.closing, options: .literal,
                                     range: NSRange(location: content, length: text.length - content))
                guard end.location != NSNotFound else { throw malformed }
                next = NSMaxRange(end)
            } else {
                next = start + 1
                if root == nil {
                    let range = NSRange(location: next, length: text.length - next)
                    let end = text.rangeOfCharacter(from: nameEnd, options: .literal, range: range).location
                    if end != NSNotFound {
                        root = SecureXMLDelegate.localName(text.substring(with: NSRange(location: next, length: end - next)))
                    }
                }
            }
        }
        return root
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

    func parser(_ parser: XMLParser, foundCDATA CDATABlock: Data) {
        if let text = String(data: CDATABlock, encoding: .utf8) { appendText(text) }
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
