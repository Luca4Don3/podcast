import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
#if canImport(Security)
import Security
#endif

// MARK: - Public model types

/// A single DIRECT child returned by a WebDAV PROPFIND Depth:1 listing.
public struct WebDavEntry: Equatable, Sendable {
    public let name: String
    /// Path relative to the client root (no leading slash, no trailing slash).
    public let path: String
    public let directory: Bool
    public let size: Int64?
    public let modifiedAt: Date?
    public let contentType: String?

    public init(name: String, path: String, directory: Bool, size: Int64? = nil,
                modifiedAt: Date? = nil, contentType: String? = nil) {
        self.name = name
        self.path = path
        self.directory = directory
        self.size = size
        self.modifiedAt = modifiedAt
        self.contentType = contentType
    }
}

/// A stored WebDAV source configuration. Secrets are never part of `baseUrl`.
public struct WebDavSource: Equatable, Codable, Sendable {
    public let id: String
    public let provider: WebDavProvider
    public let label: String
    public let baseUrl: String
    public let rootPath: String

    public init(id: String, provider: WebDavProvider, label: String, baseUrl: String, rootPath: String) {
        self.id = id
        self.provider = provider
        self.label = label
        self.baseUrl = baseUrl
        self.rootPath = rootPath
    }
}

public enum WebDavProvider: String, Codable, Sendable {
    case webdav
    case jianguoyun
    case zspace
    case feilian
}

/// Fixed, localized errors. Server-controlled strings are never echoed.
public enum WebDavError: LocalizedError, Equatable, Sendable {
    case invalidBaseURL
    case invalidPath
    case httpsRequired
    case requestFailed
    case badStatus(Int)
    case tooLarge
    case itemLimitExceeded
    case malformedResponse
    case externalEntityForbidden

    public var errorDescription: String? {
        switch self {
        case .invalidBaseURL: return "WebDAV 地址无效：仅允许 http(s)，且不能包含账号、密码或凭据查询参数。"
        case .invalidPath: return "WebDAV 路径无效。"
        case .httpsRequired: return "该来源要求 HTTPS，已拒绝 HTTP 地址或降级跳转。"
        case .requestFailed: return "无法完成 WebDAV 请求，请检查网络后重试。"
        case .badStatus(let status): return "WebDAV 请求失败（HTTP \(status)）。"
        case .tooLarge: return "WebDAV 响应超过大小限制。"
        case .itemLimitExceeded: return "WebDAV 目录条目超过 500 条上限。"
        case .malformedResponse: return "WebDAV 响应格式不正确。"
        case .externalEntityForbidden: return "WebDAV 响应包含不允许的 XML 实体声明。"
        }
    }
}

/// Result for the configured endpoint over the system's current route, not a VPN status.
public enum WebDavConnectionCheck: Equatable, Sendable {
    case ready, authenticationRequired, forbidden, redirect, notWebDav, badResponse
}

// MARK: - URL hardening

internal enum WebDavURLPolicy {
    static let maximumResponseBytes = 8 * 1024 * 1024

    private static let forbidden = CharacterSet.whitespacesAndNewlines.union(.controlCharacters)
    private static let unreserved = CharacterSet(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~")
    private static let credentialQueryKeys: Set<String> = [
        "token", "access_token", "access-token", "api_key", "apikey", "api-key", "key",
        "password", "passwd", "secret", "auth", "authorization", "sig", "signature",
        "session", "sessionid", "cookie",
    ]

    /// Splits an already percent-encoded path into safe decoded segments.
    /// Encoded separators (%2F, %5C), dots and control characters are rejected.
    static func pathSegments(_ raw: String) throws -> [String] {
        var result: [String] = []
        for piece in raw.split(separator: "/", omittingEmptySubsequences: true) {
            let part = String(piece)
            guard let decoded = part.removingPercentEncoding,
                  !decoded.isEmpty, decoded != ".", decoded != "..",
                  !decoded.contains("/"), !decoded.contains("\\"),
                  decoded.rangeOfCharacter(from: .controlCharacters) == nil,
                  decoded.utf8.allSatisfy({ $0 != 0x7f }) else {
                throw WebDavError.invalidPath
            }
            result.append(decoded)
        }
        return result
    }

    /// Validates a source base URL and returns its origin (path cleared, trailing slash).
    static func canonicalOrigin(_ input: URL) throws -> URL {
        guard var components = URLComponents(url: input, resolvingAgainstBaseURL: false),
              let scheme = components.scheme?.lowercased(), scheme == "http" || scheme == "https",
              let host = components.percentEncodedHost, !host.isEmpty, !host.contains("%"),
              components.user == nil, components.password == nil,
              components.percentEncodedUser == nil, components.percentEncodedPassword == nil,
              components.percentEncodedQuery == nil, components.percentEncodedFragment == nil,
              components.port.map({ (1...65535).contains($0) }) ?? true,
              let absolute = components.url,
              absolute.absoluteString.rangeOfCharacter(from: forbidden) == nil,
              !absolute.absoluteString.contains("\\") else {
            throw WebDavError.invalidBaseURL
        }
        _ = try pathSegments(components.percentEncodedPath)
        components.scheme = scheme
        components.percentEncodedHost = host.lowercased()
        components.port = scheme == "https" ? (components.port ?? 443) : (components.port ?? 80)
        components.path = "/"
        components.percentEncodedQuery = nil
        components.percentEncodedFragment = nil
        guard let url = components.url else { throw WebDavError.invalidBaseURL }
        return url
    }

    /// Validates a user-supplied relative path and returns safe decoded segments.
    static func relativeSegments(_ raw: String) throws -> [String] {
        guard !raw.contains("://"), !raw.hasPrefix("//"),
              !raw.contains("?"), !raw.contains("#"),
              raw.rangeOfCharacter(from: forbidden) == nil else {
            throw WebDavError.invalidPath
        }
        return try pathSegments(raw)
    }

    /// Appends decoded segments to an origin, percent-encoding each segment.
    static func append(_ segments: [String], to origin: URL, trailingSlash: Bool) throws -> URL {
        guard var components = URLComponents(url: origin, resolvingAgainstBaseURL: false),
              isSafeHTTPURL(origin), let scheme = components.scheme?.lowercased() else {
            throw WebDavError.invalidBaseURL
        }
        let encoded = segments.map { $0.addingPercentEncoding(withAllowedCharacters: unreserved) ?? "" }
        var path = "/" + encoded.joined(separator: "/")
        if trailingSlash || encoded.isEmpty { path += "/" }
        components.percentEncodedPath = path
        components.percentEncodedQuery = nil
        components.percentEncodedFragment = nil
        components.user = nil
        components.password = nil
        components.percentEncodedUser = nil
        components.percentEncodedPassword = nil
        guard let url = components.url, (scheme == "http" || scheme == "https") else {
            throw WebDavError.invalidBaseURL
        }
        return url
    }

    static func hasCredentialQuery(_ url: URL) -> Bool {
        guard let items = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems else { return false }
        return items.contains { credentialQueryKeys.contains($0.name.lowercased()) }
    }

    static func isSafeHTTPURL(_ url: URL) -> Bool {
        guard let components = URLComponents(url: url, resolvingAgainstBaseURL: false),
              let scheme = components.scheme?.lowercased(), scheme == "http" || scheme == "https",
              let host = components.percentEncodedHost, !host.isEmpty,
              components.user == nil, components.password == nil,
              components.percentEncodedUser == nil, components.percentEncodedPassword == nil,
              components.port.map({ (1...65535).contains($0) }) ?? true,
              components.url != nil else { return false }
        let text = url.absoluteString
        return !text.contains("\\") && text.rangeOfCharacter(from: forbidden) == nil
    }

    /// Redirects must stay http(s), keep the origin free of embedded secrets and respect requireHttps.
    static func isSafeRedirectTarget(_ target: URL, from original: URL?, transport: SourceTransport) -> Bool {
        guard isSafeHTTPURL(target), !hasCredentialQuery(target),
              transport.allowsRedirect(from: original, to: target) else { return false }
        return true
    }

    /// Exposed to the copied delegate so cross origin is judged with normalized ports.
    static func normalizedOrigin(_ url: URL?) -> URL? {
        guard let url else { return nil }
        return try? canonicalOrigin(url)
    }
}

// MARK: - Secure multistatus XML parsing

internal enum WebDavXMLParser {
    static let maximumEntries = 500

    static func parse(_ data: Data, requestSegments: [String], prefixSegments: [String]) throws -> [WebDavEntry] {
        guard data.count <= WebDavURLPolicy.maximumResponseBytes else { throw WebDavError.tooLarge }
        let upper = String(decoding: data, as: UTF8.self).uppercased()
        guard !upper.contains("<!DOCTYPE"), !upper.contains("<!ENTITY") else {
            throw WebDavError.externalEntityForbidden
        }
        let delegate = Delegate(requestSegments: requestSegments, prefixSegments: prefixSegments)
        let parser = XMLParser(data: data)
        parser.shouldResolveExternalEntities = false
        parser.delegate = delegate
        guard parser.parse(), delegate.failure == nil else {
            throw delegate.failure ?? WebDavError.malformedResponse
        }
        guard delegate.sawRoot else { throw WebDavError.malformedResponse }
        return delegate.entries
    }

    private final class Delegate: NSObject, XMLParserDelegate, @unchecked Sendable {
        private struct Frame {
            let name: String
            var text: String
        }
        private struct Props {
            var href: String?
            var displayName: String?
            var isDirectory = false
            var size: Int64?
            var modifiedAt: Date?
            var contentType: String?
        }

        private(set) var entries: [WebDavEntry] = []
        private(set) var failure: WebDavError?
        private(set) var sawRoot = false
        private var frames: [Frame] = []
        private var current: Props?
        private var pending: Props?
        private var propstatOK = true
        private let requestSegments: [String]
        private let prefixSegments: [String]

        init(requestSegments: [String], prefixSegments: [String]) {
            self.requestSegments = requestSegments
            self.prefixSegments = prefixSegments
        }

        private func reject(_ error: WebDavError, parser: XMLParser) {
            guard failure == nil else { return }
            failure = error
            parser.abortParsing()
        }

        private func parentName() -> String? {
            let index = frames.count - 2
            return index >= 0 ? frames[index].name : nil
        }

        private func hasAncestor(_ name: String) -> Bool {
            frames.dropLast().contains { $0.name == name }
        }

        func parser(_ parser: XMLParser, didStartElement elementName: String,
                    namespaceURI: String?, qualifiedName qName: String?,
                    attributes attributeDict: [String: String] = [:]) {
            let name = Self.localName(elementName)
            frames.append(Frame(name: name, text: ""))
            if !sawRoot {
                sawRoot = true
                guard name == "multistatus" else {
                    reject(.malformedResponse, parser: parser)
                    return
                }
            }
            switch name {
            case "response":
                guard entries.count < WebDavXMLParser.maximumEntries else {
                    reject(.itemLimitExceeded, parser: parser)
                    return
                }
                current = Props()
            case "propstat":
                pending = Props()
                propstatOK = true
            default:
                break
            }
        }

        func parser(_ parser: XMLParser, foundCharacters string: String) {
            guard !frames.isEmpty else { return }
            frames[frames.count - 1].text += string
        }

        func parser(_ parser: XMLParser, didEndElement elementName: String,
                    namespaceURI: String?, qualifiedName qName: String?) {
            let name = Self.localName(elementName)
            guard let frame = frames.last else { return }
            let text = frame.text.trimmingCharacters(in: .whitespacesAndNewlines)
            let parent = parentName()
            switch name {
            case "href":
                if parent == "response" { current?.href = text }
            case "status":
                if parent == "propstat" { propstatOK = Self.isSuccessStatus(text) }
            case "displayname":
                if current != nil, pending != nil, hasAncestor("prop") { pending?.displayName = text }
            case "getcontentlength":
                if current != nil, pending != nil, hasAncestor("prop"), let size = Int64(text), size >= 0 {
                    pending?.size = size
                }
            case "getlastmodified":
                if current != nil, pending != nil, hasAncestor("prop") { pending?.modifiedAt = Self.date(text) }
            case "getcontenttype":
                if current != nil, pending != nil, hasAncestor("prop"), !text.isEmpty { pending?.contentType = text }
            case "collection":
                if parent == "resourcetype", hasAncestor("prop") { pending?.isDirectory = true }
            case "propstat":
                if propstatOK, let pending, let current {
                    var merged = current
                    if let value = pending.displayName { merged.displayName = value }
                    if pending.isDirectory { merged.isDirectory = true }
                    if let value = pending.size { merged.size = value }
                    if let value = pending.modifiedAt { merged.modifiedAt = value }
                    if let value = pending.contentType { merged.contentType = value }
                    self.current = merged
                }
                self.pending = nil
                propstatOK = true
            case "response":
                if let current, let entry = Self.makeEntry(current, requestSegments: requestSegments,
                                                          prefixSegments: prefixSegments) {
                    entries.append(entry)
                }
                self.current = nil
            default:
                break
            }
            frames.removeLast()
        }

        func parser(_ parser: XMLParser, foundExternalEntityDeclarationWithName name: String,
                    publicID: String?, systemID: String?) {
            reject(.externalEntityForbidden, parser: parser)
        }

        func parser(_ parser: XMLParser, foundInternalEntityDeclarationWithName name: String, value: String?) {
            reject(.externalEntityForbidden, parser: parser)
        }

        func parser(_ parser: XMLParser, resolveExternalEntityName name: String, systemID: String?) -> Data? {
            reject(.externalEntityForbidden, parser: parser)
            return nil
        }

        func parser(_ parser: XMLParser, parseErrorOccurred parseError: Error) {
            if failure == nil { failure = .malformedResponse }
        }

        private static func localName(_ raw: String) -> String {
            guard let index = raw.lastIndex(of: ":") else { return raw }
            return String(raw[raw.index(after: index)...])
        }

        private static func isSuccessStatus(_ raw: String) -> Bool {
            let pieces = raw.split(whereSeparator: { $0 == " " || $0 == "\t" })
            guard pieces.count >= 2, let code = Int(pieces[1]) else { return false }
            return (200..<300).contains(code)
        }

        private static func date(_ raw: String) -> Date? {
            let value = raw.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !value.isEmpty else { return nil }
            let rfc = DateFormatter()
            rfc.locale = Locale(identifier: "en_US_POSIX")
            rfc.dateFormat = "EEE, dd MMM yyyy HH:mm:ss zzz"
            if let parsed = rfc.date(from: value) { return parsed }
            let iso = ISO8601DateFormatter()
            iso.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
            if let parsed = iso.date(from: value) { return parsed }
            iso.formatOptions = .withInternetDateTime
            return iso.date(from: value)
        }

        private static func makeEntry(_ props: Props, requestSegments: [String],
                                      prefixSegments: [String]) -> WebDavEntry? {
            guard let href = props.href, !href.isEmpty else { return nil }
            let encodedPath: String
            if let absolute = URLComponents(string: href), absolute.scheme != nil {
                guard WebDavURLPolicy.isSafeHTTPURL(href.isEmpty ? URL(string: href)! : URL(string: href)!) else { return nil }
                let scheme = absolute.scheme?.lowercased()
                let targetPort = absolute.port ?? (scheme == "https" ? 443 : 80)
                // Caller verifies the host/port against the source origin before this point.
                encodedPath = absolute.percentEncodedPath
                _ = targetPort
            } else {
                encodedPath = href
            }
            let segments = (try? WebDavURLPolicy.pathSegments(encodedPath)) ?? []
            guard segments.count == requestSegments.count + 1,
                  Array(segments.prefix(requestSegments.count)) == requestSegments else { return nil }
            let last = segments[segments.count - 1]
            guard segments.count > prefixSegments.count else { return nil }
            let relative = Array(segments.dropFirst(prefixSegments.count))
            let path = relative.joined(separator: "/")
            guard !path.isEmpty else { return nil }
            let display = props.displayName?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            let validDisplay = !display.isEmpty && display != "." && display != ".."
                && !display.contains("/") && !display.contains("\\")
                && display.rangeOfCharacter(from: .controlCharacters) == nil
            let name = validDisplay ? display : last
            let isDirectory = props.isDirectory || href.hasSuffix("/")
            return WebDavEntry(name: name, path: path, directory: isDirectory,
                               size: props.size, modifiedAt: props.modifiedAt, contentType: props.contentType)
        }
    }
}

// MARK: - Session delegate (internal copy of the App's private policy)

/// Mirrors FeedSessionDelegate: requireHttps, redirect sanitizing, custom CA, mTLS, Digest.
/// Enhancements: redirects are limited to http(s) without userinfo/secret query items,
/// and credentials/client certificates are permanently dropped on any origin change.
internal final class WebDavSessionDelegate: NSObject, URLSessionDelegate, URLSessionTaskDelegate, @unchecked Sendable {
    private let transport: SourceTransport
    private let credentials: SourceCredentials
    private let protectedFields: Set<String>
    private let originalURL: URL?
    private let clientCredential: URLCredential?
    private let lock = NSLock()
    private var currentURL: URL?
    private var stripped = false
#if canImport(Security)
    private let customAnchors: [SecCertificate]
#endif

    private let followRedirects: Bool

    init(transport: SourceTransport, credentials: SourceCredentials, originalURL: URL?, followRedirects: Bool) {
        self.followRedirects = followRedirects
        self.transport = transport
        self.credentials = credentials
        self.originalURL = originalURL
        self.currentURL = originalURL
        self.protectedFields = Set(credentials.httpHeaders.keys)
#if canImport(Security)
        self.customAnchors = Self.certificates(from: transport.customCaPem)
        self.clientCredential = Self.clientCredential(certPEM: transport.clientCertPem,
                                                     keyPEM: transport.clientKeyPem,
                                                     password: transport.clientKeyPassword)
#else
        self.clientCredential = nil
#endif
    }

    func urlSession(_ session: URLSession, task: URLSessionTask,
                    willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest,
                    completionHandler: @escaping (URLRequest?) -> Void) {
        guard followRedirects, let target = request.url,
              WebDavURLPolicy.isSafeRedirectTarget(target, from: task.originalRequest?.url,
                                                   transport: transport) else {
            completionHandler(nil)
            return
        }
        var sanitized = request
        if shouldStrip(to: target) {
            for field in protectedFields { sanitized.setValue(nil, forHTTPHeaderField: field) }
            sanitized.setValue(nil, forHTTPHeaderField: "Authorization")
        }
        completionHandler(sanitized)
    }

    func urlSession(_ session: URLSession, didReceive challenge: URLAuthenticationChallenge,
                    completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void) {
        let trusted = isTrustedConnection(challenge)
        switch challenge.protectionSpace.authenticationMethod {
        case NSURLAuthenticationMethodServerTrust:
#if canImport(Security)
            if !customAnchors.isEmpty, trusted, let trust = challenge.protectionSpace.serverTrust {
                // Custom anchors are added to, not substituted for, the system trust store.
                SecTrustSetAnchorCertificates(trust, customAnchors as CFArray)
                SecTrustSetAnchorCertificatesOnly(trust, false)
                var error: CFError?
                if SecTrustEvaluateWithError(trust, &error) {
                    completionHandler(.useCredential, URLCredential(trust: trust))
                } else {
                    completionHandler(.cancelAuthenticationChallenge, nil)
                }
                return
            }
#endif
            completionHandler(.performDefaultHandling, nil)
        case NSURLAuthenticationMethodClientCertificate:
            if trusted, let clientCredential {
                completionHandler(.useCredential, clientCredential)
            } else {
                completionHandler(.cancelAuthenticationChallenge, nil)
            }
        case NSURLAuthenticationMethodHTTPDigest:
            let usesDigest = credentials.kind == .digest || transport.digest
            if trusted, usesDigest, !credentials.username.isEmpty, !credentials.secret.isEmpty {
                // No static header: URLSession derives the Digest response from this
                // credential only after the server answers 401 with a challenge.
                completionHandler(.useCredential,
                                  URLCredential(user: credentials.username,
                                                password: credentials.secret,
                                                persistence: .forSession))
            } else {
                completionHandler(.performDefaultHandling, nil)
            }
        default:
            completionHandler(.performDefaultHandling, nil)
        }
    }

    private func shouldStrip(to target: URL) -> Bool {
        lock.lock()
        let source = currentURL ?? originalURL
        currentURL = target
        let alreadyStripped = stripped
        lock.unlock()
        let strip = SourceTransport.shouldStripCredentials(from: WebDavURLPolicy.normalizedOrigin(source),
                                                           to: WebDavURLPolicy.normalizedOrigin(target) ?? target)
        if strip {
            lock.lock()
            stripped = true
            lock.unlock()
        }
        return alreadyStripped || strip
    }

    private func isTrustedConnection(_ challenge: URLAuthenticationChallenge) -> Bool {
        lock.lock()
        let origin = originalURL
        let current = currentURL
        let lost = stripped
        lock.unlock()
        guard !lost, let origin, let current else { return false }
        guard current.host?.lowercased() == challenge.protectionSpace.host.lowercased() else { return false }
        return WebDavURLPolicy.normalizedOrigin(origin) == WebDavURLPolicy.normalizedOrigin(current)
    }

#if canImport(Security)
    private static func certificates(from pem: String) -> [SecCertificate] {
        let begin = "-----BEGIN CERTIFICATE-----"
        let end = "-----END CERTIFICATE-----"
        var rest = pem
        var result: [SecCertificate] = []
        while let start = rest.range(of: begin),
              let finish = rest.range(of: end, range: start.upperBound..<rest.endIndex) {
            let body = rest[start.upperBound..<finish.lowerBound]
            let base64 = body.split(whereSeparator: \.isNewline).map(String.init)
                .map { $0.trimmingCharacters(in: .whitespaces) }
                .filter { !$0.isEmpty && !$0.contains(":") }.joined()
            if let data = Data(base64Encoded: base64),
               let certificate = SecCertificateCreateWithData(nil, data as CFData) {
                result.append(certificate)
            }
            rest = String(rest[finish.upperBound...])
        }
        return result
    }

    private static func clientCredential(certPEM: String, keyPEM: String, password: String) -> URLCredential? {
        // iOS and macOS test runners do not expose SecIdentityCreate consistently.
        // A PKCS#12 bundle (certificate + encrypted key) is the portable path.
        _ = keyPEM
        let trimmed = certPEM.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let p12 = Data(base64Encoded: trimmed), !p12.isEmpty else { return nil }
        let options: [String: Any] = [kSecImportExportPassphrase as String: password]
        var items: CFArray?
        guard SecPKCS12Import(p12 as CFData, options as CFDictionary, &items) == errSecSuccess,
              let imported = items as? [[String: Any]],
              let identityValue = imported.first?[kSecImportItemIdentity as String] else { return nil }
        let identity = identityValue as! SecIdentity
        return URLCredential(identity: identity, certificates: nil, persistence: .forSession)
    }
#endif
}

internal enum WebDavHTTP {
    static func makeSession(transport: SourceTransport, credentials: SourceCredentials,
                            originalURL: URL, followRedirects: Bool = true) -> URLSession {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.urlCache = nil
        configuration.httpCookieStorage = nil
        configuration.httpShouldSetCookies = false
        configuration.urlCredentialStorage = nil
        return URLSession(configuration: configuration,
                          delegate: WebDavSessionDelegate(transport: transport, credentials: credentials,
                                                          originalURL: originalURL, followRedirects: followRedirects),
                          delegateQueue: nil)
    }
}

// MARK: - Client

public final class WebDavClient {
    internal typealias Sender = @Sendable (URLRequest) async throws -> (Data, HTTPURLResponse)

    private struct Configuration {
        let origin: URL
        let prefixSegments: [String]

        init(baseURL: URL) throws {
            let origin = try WebDavURLPolicy.canonicalOrigin(baseURL)
            guard var components = URLComponents(url: baseURL, resolvingAgainstBaseURL: false) else {
                throw WebDavError.invalidBaseURL
            }
            let baseSegments = try WebDavURLPolicy.pathSegments(components.percentEncodedPath)
            components.percentEncodedPath = "/"
            _ = components
            self.origin = origin
            self.prefixSegments = baseSegments
        }
    }

    private let origin: URL
    private let prefixSegments: [String]
    private let credentials: SourceCredentials
    private let transport: SourceTransport
    private let configurationError: WebDavError?
    private let sender: Sender
    private let probeSender: Sender

    public convenience init(baseURL: URL, credentials: SourceCredentials, transport: SourceTransport) {
        self.init(baseURL: baseURL, credentials: credentials, transport: transport,
                  sender: Self.defaultSender(transport: transport, credentials: credentials),
                  probeSender: Self.defaultSender(transport: transport, credentials: credentials, probe: true))
    }

    internal init(baseURL: URL, credentials: SourceCredentials, transport: SourceTransport,
                  sender: @escaping Sender, probeSender: Sender? = nil) {
        let resolved: Result<Configuration, WebDavError>
        do {
            resolved = .success(try Configuration(baseURL: baseURL))
        } catch let error as WebDavError {
            resolved = .failure(error)
        } catch {
            resolved = .failure(.invalidBaseURL)
        }
        switch resolved {
        case .success(let configuration):
            self.origin = configuration.origin
            self.prefixSegments = configuration.prefixSegments
            self.configurationError = nil
        case .failure(let error):
            // Placeholder origin; every public method throws the stored error instead.
            self.origin = URL(string: "https://invalid.invalid/")!
            self.prefixSegments = []
            self.configurationError = error
        }
        self.credentials = credentials
        self.transport = transport
        self.sender = sender
        self.probeSender = probeSender ?? sender
    }

    /// PROPFIND Depth:1. Returns direct children of `path` (empty path = root).
    public func list(path: String = "") async throws -> [WebDavEntry] {
        if let configurationError { throw configurationError }
        guard transport.allows(origin), WebDavURLPolicy.isSafeHTTPURL(origin) else { throw WebDavError.httpsRequired }
        let relative = try WebDavURLPolicy.relativeSegments(path)
        let requestSegments = prefixSegments + relative
        let url = try WebDavURLPolicy.append(requestSegments, to: origin, trailingSlash: true)
        var request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 30)
        request.httpMethod = "PROPFIND"
        request.httpShouldHandleCookies = false
        request.setValue("1", forHTTPHeaderField: "Depth")
        request.setValue("application/xml; charset=utf-8", forHTTPHeaderField: "Content-Type")
        request.setValue("application/xml, text/xml;q=0.9", forHTTPHeaderField: "Accept")
        request.httpBody = Data(Self.propfindBody.utf8)
        applyCredentials(to: &request)
        let (data, response) = try await sender(request)
        guard response.statusCode == 207 || response.statusCode == 200 else {
            throw WebDavError.badStatus(response.statusCode)
        }
        return try WebDavXMLParser.parse(data, requestSegments: requestSegments,
                                        prefixSegments: prefixSegments)
    }

    /// Probe only the configured collection. Never follows a browser sign-in redirect.
    public func testConnection(path: String = "") async throws -> WebDavConnectionCheck {
        if let configurationError { throw configurationError }
        guard transport.allows(origin) else { throw WebDavError.httpsRequired }
        let segments = prefixSegments + (try WebDavURLPolicy.relativeSegments(path))
        let url = try WebDavURLPolicy.append(segments, to: origin, trailingSlash: true)
        var request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 15)
        request.httpMethod = "PROPFIND"
        request.httpShouldHandleCookies = false
        request.setValue("0", forHTTPHeaderField: "Depth")
        request.setValue("application/xml; charset=utf-8", forHTTPHeaderField: "Content-Type")
        request.httpBody = Data(Self.propfindBody.utf8)
        applyCredentials(to: &request)
        let (data, response) = try await probeSender(request)
        switch response.statusCode {
        case 401, 407: return .authenticationRequired
        case 403: return .forbidden
        case 300...399: return .redirect
        case 404, 405, 501: return .notWebDav
        case 200, 207:
            guard (try? WebDavXMLParser.parse(data, requestSegments: segments,
                                             prefixSegments: prefixSegments)) != nil else { return .notWebDav }
            return .ready
        default: return .badResponse
        }
    }

    /// A credential-free http(s) URL for the given path. Never embeds userinfo or tokens.
    public func fileURL(path: String) throws -> URL {
        if let configurationError { throw configurationError }
        guard transport.allows(origin), WebDavURLPolicy.isSafeHTTPURL(origin) else { throw WebDavError.httpsRequired }
        let relative = try WebDavURLPolicy.relativeSegments(path)
        return try WebDavURLPolicy.append(prefixSegments + relative, to: origin, trailingSlash: false)
    }

    /// Read-only range probe: HEAD then GET bytes=0-0; only 206 returns true.
    public func probeRange(path: String) async throws -> Bool {
        if let configurationError { throw configurationError }
        let url = try fileURL(path: path)
        var head = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 30)
        head.httpMethod = "HEAD"
        head.httpShouldHandleCookies = false
        applyCredentials(to: &head)
        let (_, headResponse) = try await sender(head)
        guard (200..<300).contains(headResponse.statusCode) else {
            throw WebDavError.badStatus(headResponse.statusCode)
        }
        var get = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 30)
        get.httpMethod = "GET"
        get.httpShouldHandleCookies = false
        get.setValue("bytes=0-0", forHTTPHeaderField: "Range")
        applyCredentials(to: &get)
        let (_, getResponse) = try await sender(get)
        return getResponse.statusCode == 206
    }

    private func applyCredentials(to request: inout URLRequest) {
        for (field, value) in credentials.httpHeaders {
            request.setValue(value, forHTTPHeaderField: field)
        }
    }

    private static let propfindBody = """
    <?xml version="1.0" encoding="utf-8"?>
    <propfind xmlns="DAV:"><prop><href/><resourcetype/><getcontentlength/><getlastmodified/><getcontenttype/><displayname/></prop></propfind>
    """

    private static func defaultSender(transport: SourceTransport, credentials: SourceCredentials,
                                      probe: Bool = false) -> Sender {
        { request in
            guard let url = request.url else { throw WebDavError.requestFailed }
            guard transport.allows(url), WebDavURLPolicy.isSafeHTTPURL(url) else { throw WebDavError.httpsRequired }
            let session = WebDavHTTP.makeSession(transport: transport, credentials: credentials,
                                                 originalURL: url, followRedirects: !probe)
            defer { session.invalidateAndCancel() }
            do {
#if !canImport(FoundationNetworking)
                if probe {
                    let (bytes, response) = try await session.bytes(for: request)
                    guard let http = response as? HTTPURLResponse else { throw WebDavError.malformedResponse }
                    guard http.statusCode == 200 || http.statusCode == 207 else { return (Data(), http) }
                    var data = Data()
                    for try await byte in bytes {
                        guard data.count < 128 * 1024 else { throw WebDavError.tooLarge }
                        data.append(byte)
                    }
                    return (data, http)
                }
#endif
                let (data, response) = try await session.data(for: request)
                guard let http = response as? HTTPURLResponse else { throw WebDavError.malformedResponse }
                return (data, http)
            } catch let error as WebDavError {
                throw error
            } catch {
                try Task.checkCancellation()
                throw WebDavError.requestFailed
            }
        }
    }
}
