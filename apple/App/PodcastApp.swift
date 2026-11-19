import AVFoundation
#if canImport(MediaPlayer)
import MediaPlayer
#endif
import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
#if canImport(Security)
import Security
#endif
import PodcastKit
import SwiftUI

@main
@MainActor
struct PodcastApp: App {
    @StateObject private var library = LibrarySession()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            LibraryView(library: library)
                .onChange(of: scenePhase) { phase in
                    if phase == .background { library.playback.pause() }
                }
        }
    }
}

// MARK: - Local, Codable library (no account, no credentials)

private struct StoredEpisode: Codable, Identifiable, Equatable, Sendable {
    let id: String
    let title: String
    let audioUrl: String
    let publishedAt: String?
    let durationSeconds: Int?

    init(id: String, title: String, audioUrl: String, publishedAt: String?, durationSeconds: Int?) {
        self.id = id
        self.title = title
        self.audioUrl = audioUrl
        self.publishedAt = publishedAt
        self.durationSeconds = durationSeconds
    }

    init(_ episode: ParsedEpisode) {
        self.init(id: episode.id, title: episode.title, audioUrl: episode.audioUrl.absoluteString,
                  publishedAt: episode.publishedAt, durationSeconds: episode.durationSeconds)
    }
}

private struct StoredFeed: Codable, Identifiable, Equatable, Sendable {
    let id: String
    let sourceURL: String
    var title: String
    var author: String
    var summary: String?
    var episodes: [StoredEpisode]
    var refreshedAt: Date?

    init(id: String, sourceURL: String, title: String, author: String, summary: String?,
         episodes: [StoredEpisode], refreshedAt: Date? = Date()) {
        self.id = id
        self.sourceURL = sourceURL
        self.title = title
        self.author = author
        self.summary = summary
        self.episodes = episodes
        self.refreshedAt = refreshedAt
    }
}

/// A single JSON file in the app's documents directory. Only public feed metadata is written.
private struct LocalFeedStore {
    let fileURL: URL

    init(fileName: String = "podcast-library.json") {
        let directory = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first
            ?? URL(fileURLWithPath: NSTemporaryDirectory(), isDirectory: true)
        fileURL = directory.appendingPathComponent(fileName, isDirectory: false)
    }

    func load() -> [StoredFeed] {
        guard let data = try? Data(contentsOf: fileURL) else { return [] }
        return (try? JSONDecoder().decode([StoredFeed].self, from: data)) ?? []
    }

    func save(_ feeds: [StoredFeed]) throws {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        let data = try encoder.encode(feeds)
        try data.write(to: fileURL, options: .atomic)
    }
}


// MARK: - Credential and transport storage (Keychain on Apple platforms, memory fallback elsewhere)

/// Everything sensitive for one feed. Never part of the JSON subscription file.
private struct StoredFeedSecrets: Codable {
    var credentials: SourceCredentials
    var transport: SourceTransport
}

#if canImport(Security)
private enum FeedSecretStore {
    private static let service = "com.podcastapp.feed-credentials"

    static func load(for feedID: String) -> StoredFeedSecrets {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: feedID,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess,
              let data = item as? Data else {
            return StoredFeedSecrets(credentials: SourceCredentials(), transport: SourceTransport())
        }
        // New records carry transport; legacy records only carry credentials.
        if let secrets = try? JSONDecoder().decode(StoredFeedSecrets.self, from: data) { return secrets }
        if let credentials = try? JSONDecoder().decode(SourceCredentials.self, from: data) {
            return StoredFeedSecrets(credentials: credentials, transport: SourceTransport())
        }
        return StoredFeedSecrets(credentials: SourceCredentials(), transport: SourceTransport())
    }

    static func save(_ secrets: StoredFeedSecrets, for feedID: String) {
        let base: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: feedID,
        ]
        let hasSecrets = secrets.credentials.kind != .none || secrets.transport != SourceTransport()
        guard hasSecrets, let data = try? JSONEncoder().encode(secrets) else {
            SecItemDelete(base as CFDictionary)
            return
        }
        let attributes: [String: Any] = [
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
        ]
        var add = base
        for (key, value) in attributes { add[key] = value }
        if SecItemAdd(add as CFDictionary, nil) == errSecDuplicateItem {
            SecItemUpdate(base as CFDictionary, attributes as CFDictionary)
        }
    }

    static func remove(for feedID: String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: feedID,
        ]
        SecItemDelete(query as CFDictionary)
    }
}
#else
private enum FeedSecretStore {
    private static let lock = NSLock()
    private static var memory: [String: StoredFeedSecrets] = [:]

    static func load(for feedID: String) -> StoredFeedSecrets {
        lock.lock()
        defer { lock.unlock() }
        return memory[feedID] ?? StoredFeedSecrets(credentials: SourceCredentials(), transport: SourceTransport())
    }

    static func save(_ secrets: StoredFeedSecrets, for feedID: String) {
        lock.lock()
        defer { lock.unlock() }
        if secrets.credentials.kind == .none, secrets.transport == SourceTransport() {
            memory.removeValue(forKey: feedID)
        } else {
            memory[feedID] = secrets
        }
    }

    static func remove(for feedID: String) {
        lock.lock()
        defer { lock.unlock() }
        memory.removeValue(forKey: feedID)
    }
}
#endif

// MARK: - Fixed, localized errors (never echo user input or server strings)

private enum FeedAddError: LocalizedError {
    case emptyInput
    case invalidReference
    case requestFailed
    case badStatus
    case tooLarge
    case malformedFeed
    case malformedOPML
    case missingFeedURL
    case webNeedsRSS
    case httpsRequired
    case invalidTransport

    var errorDescription: String? {
        switch self {
        case .emptyInput: return "请输入播客源地址。"
        case .invalidReference: return "播客源地址无效或不受支持。"
        case .requestFailed: return "无法读取播客源，请检查网络后重试。"
        case .badStatus: return "播客源请求失败，请检查地址或稍后重试。"
        case .tooLarge: return "播客源内容超过大小限制。"
        case .malformedFeed: return "播客源内容格式不正确。"
        case .malformedOPML: return "OPML 订阅列表格式不正确。"
        case .missingFeedURL: return "Apple Podcasts 查询结果中没有公开的 RSS 地址。"
        case .webNeedsRSS: return "需要节目公开 RSS 地址。该平台的分享链接无法直接解析为订阅源。"
        case .httpsRequired: return "该来源要求 HTTPS，已拒绝 HTTP 地址或降级跳转。"
        case .invalidTransport: return "传输加密设置无效，请检查证书与私钥。"
        }
    }
}

private func safeMessage(_ error: Error, fallback: String) -> String {
    (error as? LocalizedError)?.errorDescription ?? fallback
}

// MARK: - Direct HTTP(S) fetching via URLSession

/// Per-session delegate: HTTPS policy, redirect sanitizing, custom CA, mTLS and Digest challenges.
private final class FeedSessionDelegate: NSObject, URLSessionDelegate, URLSessionTaskDelegate, @unchecked Sendable {
    private let transport: SourceTransport
    private let credentials: SourceCredentials
    private let protectedFields: Set<String>
    private let originalURL: URL?
    private let clientCredential: URLCredential?
#if canImport(Security)
    private let customAnchors: [SecCertificate]
#endif

    init(transport: SourceTransport, credentials: SourceCredentials, originalURL: URL?) {
        self.transport = transport
        self.credentials = credentials
        self.originalURL = originalURL
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
        guard let target = request.url,
              transport.allowsRedirect(from: task.originalRequest?.url, to: target) else {
            completionHandler(nil)
            return
        }
        var sanitized = request
        if SourceTransport.shouldStripCredentials(from: task.originalRequest?.url, to: target) {
            for field in protectedFields { sanitized.setValue(nil, forHTTPHeaderField: field) }
            sanitized.setValue(nil, forHTTPHeaderField: "Authorization")
        }
        completionHandler(sanitized)
    }

    func urlSession(_ session: URLSession, didReceive challenge: URLAuthenticationChallenge,
                    completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void) {
        let host = challenge.protectionSpace.host.lowercased()
        let originalHost = originalURL?.host?.lowercased()
        let crossHost = originalHost != nil && originalHost != host
        switch challenge.protectionSpace.authenticationMethod {
        case NSURLAuthenticationMethodServerTrust:
#if canImport(Security)
            if !customAnchors.isEmpty, !crossHost, let trust = challenge.protectionSpace.serverTrust {
                // Custom anchors are added to, not substituted for, the system trust store.
                // anchorCertificatesOnly=false keeps full system validation in force.
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
            if !crossHost, let clientCredential {
                completionHandler(.useCredential, clientCredential)
            } else {
                completionHandler(.cancelAuthenticationChallenge, nil)
            }
        case NSURLAuthenticationMethodHTTPDigest:
            let usesDigest = credentials.kind == .digest || transport.digest
            if !crossHost, usesDigest, !credentials.username.isEmpty, !credentials.secret.isEmpty {
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
#if os(macOS)
        guard !certPEM.isEmpty, !keyPEM.isEmpty,
              let certificate = certificates(from: certPEM).first else { return nil }
        var parameters = SecItemImportExportKeyParameters(version: UInt32(0), flags: [],
            passphrase: password.isEmpty ? nil : Unmanaged.passUnretained(password as CFString),
            alertTitle: nil, alertPrompt: nil, accessRef: nil, keyUsage: nil, keyAttributes: nil)
        var imported: CFArray?
        let status = SecItemImport(Data(keyPEM.utf8) as CFData, nil, nil, nil, [], &parameters, nil, &imported)
        guard status == errSecSuccess, let keys = imported as? [SecKey], let privateKey = keys.first,
              let identity = SecIdentityCreate(nil, certificate, privateKey) else { return nil }
        return URLCredential(identity: identity, certificates: nil, persistence: .forSession)
#else
        // iOS cannot build a SecIdentity from a separate key and certificate. Accept a
        // base64 PKCS#12 bundle (certificate + encrypted key) in the certificate field.
        let trimmed = certPEM.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let p12 = Data(base64Encoded: trimmed), !p12.isEmpty else { return nil }
        let options: [String: Any] = [kSecImportExportPassphrase as String: password]
        var items: CFArray?
        guard SecPKCS12Import(p12 as CFData, options as CFDictionary, &items) == errSecSuccess,
              let imported = items as? [[String: Any]],
              let identityValue = imported.first?[kSecImportItemIdentity as String] else { return nil }
        let identity = identityValue as! SecIdentity
        return URLCredential(identity: identity, certificates: nil, persistence: .forSession)
#endif
    }
#endif
}

private enum FeedHTTP {
    static let maximumFeedBytes = 4 * 1024 * 1024
    static let maximumLookupBytes = 512 * 1024

    private static func makeSession(transport: SourceTransport, credentials: SourceCredentials,
                                    originalURL: URL) -> URLSession {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.urlCache = nil
        configuration.httpCookieStorage = nil
        configuration.httpShouldSetCookies = false
        configuration.urlCredentialStorage = nil
        return URLSession(configuration: configuration,
                          delegate: FeedSessionDelegate(transport: transport, credentials: credentials,
                                                        originalURL: originalURL),
                          delegateQueue: nil)
    }

    static func data(from url: URL, credentials: SourceCredentials, transport: SourceTransport,
                     limit: Int) async throws -> Data {
        guard transport.allows(url) else { throw FeedAddError.httpsRequired }
        let session = makeSession(transport: transport, credentials: credentials, originalURL: url)
        defer { session.finishTasksAndInvalidate() }
        var request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 30)
        request.httpShouldHandleCookies = false
        request.setValue("application/rss+xml, application/xml, text/xml, application/json;q=0.9, */*;q=0.5",
                         forHTTPHeaderField: "Accept")
        // Basic/Bearer/API Key only; Digest has no static header and is answered on challenge.
        for (field, value) in credentials.httpHeaders { request.setValue(value, forHTTPHeaderField: field) }
        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await session.data(for: request)
        } catch {
            try Task.checkCancellation()
            throw FeedAddError.requestFailed
        }
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
            throw FeedAddError.badStatus
        }
        guard data.count <= limit else { throw FeedAddError.tooLarge }
        return data
    }
}

private struct LookupResponse: Decodable {
    struct Item: Decodable {
        let feedUrl: String?
        let collectionName: String?
        let artistName: String?
    }
    let results: [Item]
}

@MainActor
private final class LibrarySession: ObservableObject {
    @Published private(set) var feeds: [StoredFeed] = []
    @Published private(set) var selection: String?
    @Published private(set) var isFetching = false
    @Published private(set) var addError: String?
    @Published private(set) var libraryError: String?

    private let store = LocalFeedStore()
    let playback = EpisodePlayback()

    var selectedFeed: StoredFeed? {
        feeds.first { $0.id == selection }
    }

    init() {
        feeds = store.load()
    }

    func select(_ feedID: String?) {
        guard selection != feedID else { return }
        if let feedID {
            guard feeds.contains(where: { $0.id == feedID }) else { return }
        }
        selection = feedID
    }

    func removeFeed(_ feedID: String) {
        guard let index = feeds.firstIndex(where: { $0.id == feedID }) else { return }
        feeds.remove(at: index)
        if selection == feedID { selection = feeds.first?.id }
        FeedSecretStore.remove(for: feedID)
        persist()
    }

    func play(_ episode: StoredEpisode, feedID: String) {
        guard selection == feedID, let feed = selectedFeed,
              feed.episodes.contains(where: { $0.id == episode.id }) else { return }
        playback.play(episode)
    }

    @discardableResult
    func addSource(_ input: String, credentials: SourceCredentials, transport: SourceTransport) async -> Bool {
        guard !isFetching else { return false }
        do { try transport.validate() } catch {
            addError = FeedAddError.invalidTransport.errorDescription
            return false
        }
        let value = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !value.isEmpty else {
            addError = FeedAddError.emptyInput.errorDescription
            return false
        }
        let reference: FeedReference
        do {
            reference = try PlatformFeedAdapter.normalize(value, credentials: credentials)
        } catch {
            addError = safeMessage(error, fallback: FeedAddError.invalidReference.errorDescription ?? "")
            return false
        }
        if case .directFeed(let feedURL, _) = reference, !transport.allows(feedURL) {
            addError = FeedAddError.httpsRequired.errorDescription
            return false
        }
        if case .webMetadata(let webURL, _) = reference, !transport.allows(webURL) {
            addError = FeedAddError.httpsRequired.errorDescription
            return false
        }
        switch reference {
        case .webMetadata:
            // No web page is fetched: these platforms need a public RSS URL.
            addError = FeedAddError.webNeedsRSS.errorDescription
            return false
        case .appleLookup(let lookup):
            return await perform {
                // The lookup host is unrelated to the feed host; never forward source credentials to it.
                let data = try await FeedHTTP.data(from: lookup, credentials: SourceCredentials(),
                                                   transport: SourceTransport(requireHttps: transport.requireHttps),
                                                   limit: FeedHTTP.maximumLookupBytes)
                let result: LookupResponse
                do { result = try JSONDecoder().decode(LookupResponse.self, from: data) }
                catch { throw FeedAddError.malformedFeed }
                let first = result.results.first
                guard let feedURL = first?.feedUrl.flatMap({ Self.safeHTTPURL($0) }) else {
                    throw FeedAddError.missingFeedURL
                }
                // Re-normalize so credentials embedded in the resolved RSS URL are lifted out.
                let resolved: FeedReference
                do { resolved = try PlatformFeedAdapter.normalize(feedURL.absoluteString) }
                catch { throw FeedAddError.missingFeedURL }
                guard case .directFeed(let resolvedURL, let resolvedCredentials) = resolved else {
                    throw FeedAddError.missingFeedURL
                }
                guard transport.allows(resolvedURL) else { throw FeedAddError.httpsRequired }
                // Lookup host is unrelated: never forward credentials or mTLS identity.
                let resolvedTransport = SourceTransport(requireHttps: transport.requireHttps,
                                                        customCaPem: transport.customCaPem)
                try await ingest(resolvedURL, credentials: resolvedCredentials, transport: resolvedTransport,
                                 author: first?.artistName ?? "", titleHint: first?.collectionName)
            }
        case .directFeed(let feedURL, let feedCredentials):
            return await perform {
                try await ingest(feedURL, credentials: feedCredentials, transport: transport,
                                 author: "", titleHint: nil)
            }
        }
    }

    private func perform(_ work: @MainActor () async throws -> Void) async -> Bool {
        isFetching = true
        addError = nil
        defer { isFetching = false }
        do {
            try Task.checkCancellation()
            try await work()
            return true
        } catch is CancellationError {
            return false
        } catch {
            addError = safeMessage(error, fallback: "无法添加播客源，请重试。")
            return false
        }
    }

    private func ingest(_ url: URL, credentials: SourceCredentials, transport: SourceTransport,
                        author: String, titleHint: String?) async throws {
        let data = try await FeedHTTP.data(from: url, credentials: credentials, transport: transport,
                                           limit: FeedHTTP.maximumFeedBytes)
        if Self.looksLikeOPML(data) {
            let outlines: [OpmlFeed]
            do { outlines = try PlatformFeedAdapter.parseOPML(data) }
            catch { throw Self.adapterError(error) }
            guard !outlines.isEmpty else { throw FeedAddError.malformedOPML }
            for outline in outlines {
                try Task.checkCancellation()
                // Each outline keeps only credentials derived from its own URL.
                guard transport.allows(outline.url),
                      let reference = try? PlatformFeedAdapter.normalize(outline.url.absoluteString),
                      case .directFeed(let feedURL, let feedCredentials) = reference else { continue }
                // A different host never inherits mTLS identity or digest credentials.
                let sameHost = outline.url.host?.lowercased() == url.host?.lowercased()
                let outlineTransport = SourceTransport(
                    requireHttps: transport.requireHttps,
                    customCaPem: transport.customCaPem,
                    clientCertPem: sameHost ? transport.clientCertPem : "",
                    clientKeyPem: sameHost ? transport.clientKeyPem : "",
                    clientKeyPassword: sameHost ? transport.clientKeyPassword : "",
                    digest: sameHost && transport.digest)
                let feedData = try await FeedHTTP.data(from: feedURL, credentials: feedCredentials,
                                                       transport: outlineTransport,
                                                       limit: FeedHTTP.maximumFeedBytes)
                let parsed: ParsedFeed
                do { parsed = try PlatformFeedAdapter.parseFeed(feedData) }
                catch { throw Self.adapterError(error) }
                ingestSingle(feedURL, parsed: parsed, credentials: feedCredentials,
                             transport: outlineTransport, author: "", titleHint: outline.title)
            }
        } else {
            let parsed: ParsedFeed
            do { parsed = try PlatformFeedAdapter.parseFeed(data) }
            catch { throw Self.adapterError(error) }
            ingestSingle(url, parsed: parsed, credentials: credentials, transport: transport,
                         author: author, titleHint: titleHint)
        }
    }

    private func ingestSingle(_ url: URL, parsed: ParsedFeed, credentials: SourceCredentials,
                              transport: SourceTransport, author: String, titleHint: String?) {
        let title: String
        if !parsed.title.isEmpty {
            title = parsed.title
        } else if let hint = titleHint, !hint.isEmpty {
            title = hint
        } else {
            title = url.host ?? "播客"
        }
        let feed = StoredFeed(id: Self.feedID(for: url), sourceURL: url.absoluteString, title: title,
                              author: author, summary: parsed.description,
                              episodes: parsed.episodes.map { StoredEpisode($0) })
        if let index = feeds.firstIndex(where: { $0.id == feed.id }) {
            feeds[index] = feed
        } else {
            feeds.append(feed)
        }
        selection = feed.id
        FeedSecretStore.save(StoredFeedSecrets(credentials: credentials, transport: transport), for: feed.id)
        persist()
    }

    private func persist() {
        do {
            try store.save(feeds)
            libraryError = nil
        } catch {
            libraryError = "无法保存本地订阅，请检查设备存储空间。"
        }
    }

    private static func looksLikeOPML(_ data: Data) -> Bool {
        let head = String(decoding: data.prefix(4096), as: UTF8.self)
        return head.range(of: "<opml", options: .caseInsensitive) != nil
    }

    private static func adapterError(_ error: Error) -> FeedAddError {
        guard let error = error as? FeedAdapterError else { return .malformedFeed }
        switch error {
        case .inputTooLarge, .itemLimitExceeded: return .tooLarge
        case .malformedOPML: return .malformedOPML
        case .invalidReference: return .invalidReference
        case .externalEntityForbidden, .malformedFeed: return .malformedFeed
        }
    }

    private static func feedID(for url: URL) -> String {
        guard var components = URLComponents(url: url, resolvingAgainstBaseURL: false) else {
            return url.absoluteString
        }
        components.scheme = components.scheme?.lowercased()
        components.percentEncodedHost = components.percentEncodedHost?.lowercased()
        components.fragment = nil
        return components.string ?? url.absoluteString
    }

    private static func safeHTTPURL(_ raw: String) -> URL? {
        let value = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        let forbidden = CharacterSet.whitespacesAndNewlines.union(.controlCharacters)
        guard !value.isEmpty,
              value.rangeOfCharacter(from: forbidden) == nil,
              !value.contains("\\"),
              var components = URLComponents(string: value),
              let scheme = components.scheme?.lowercased(),
              scheme == "http" || scheme == "https",
              let host = components.percentEncodedHost, !host.isEmpty,
              components.user == nil, components.password == nil,
              components.percentEncodedUser == nil, components.percentEncodedPassword == nil,
              components.port.map({ (1...65535).contains($0) }) ?? true,
              components.url != nil else { return nil }
        components.scheme = scheme
        components.percentEncodedHost = host.lowercased()
        components.fragment = nil
        return components.url
    }
}

// MARK: - Add source

@MainActor
private struct AddSourceView: View {
    @ObservedObject var library: LibrarySession
    @Environment(\.dismiss) private var dismiss
    @State private var input = ""
    @State private var authKind: CredentialKind = .none
    @State private var username = ""
    // Transient text-field state only: cleared on submit, never copied into library state or JSON.
    @State private var secret = ""
    @State private var apiKeyHeader = ""
    @State private var requireHttps = false
    @State private var useDigest = false
    @State private var caPEM = ""
    @State private var clientCertPEM = ""
    @State private var clientKeyPEM = ""
    @State private var clientKeyPassword = ""

    // Standard SwiftUI focus handling only: no custom keyboard, no third-party input control.
    @FocusState private var focusedField: FocusField?

    private enum FocusField: Hashable {
        case sourceURL
        case username
        case secret
        case apiKeyHeader
        case caPEM
        case clientCertPEM
        case clientKeyPEM
        case clientKeyPassword
    }

    private func submit() {
        focusedField = nil
        let credentials = SourceCredentials(kind: authKind, username: username,
                                            secret: secret, header: apiKeyHeader)
        let transport = SourceTransport(requireHttps: requireHttps, customCaPem: caPEM,
                                        clientCertPem: clientCertPEM, clientKeyPem: clientKeyPEM,
                                        clientKeyPassword: clientKeyPassword, digest: useDigest)
        secret = ""
        clientKeyPEM = ""
        clientKeyPassword = ""
        Task { @MainActor in
            if await library.addSource(input, credentials: credentials, transport: transport) { dismiss() }
        }
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("播客源地址") {
                    inputRow("RSS / Atom / OPML 或分享链接") {
                        TextField("https://example.com/feed.xml", text: $input)
                            .keyboardType(.URL)
                            .textContentType(.URL)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                            .focused($focusedField, equals: .sourceURL)
                            .submitLabel(.go)
                            .onSubmit(submit)
                    }
                }
                .disabled(library.isFetching)
                Section("认证（可选）") {
                    Picker("认证方式", selection: $authKind) {
                        Text("无").tag(CredentialKind.none)
                        Text("Basic").tag(CredentialKind.basic)
                        Text("Bearer").tag(CredentialKind.bearer)
                        Text("API Key").tag(CredentialKind.apiKey)
                        Text("Digest").tag(CredentialKind.digest)
                    }
                    switch authKind {
                    case .none:
                        EmptyView()
                    case .basic:
                        inputRow("用户名") {
                            TextField("用户名", text: $username)
                                .textContentType(.username)
                                .textInputAutocapitalization(.never)
                                .autocorrectionDisabled()
                                .focused($focusedField, equals: .username)
                                .submitLabel(.next)
                                .onSubmit { focusedField = .secret }
                        }
                        inputRow("密码") {
                            SecureField("密码", text: $secret)
                                .textContentType(.password)
                                .focused($focusedField, equals: .secret)
                                .submitLabel(.done)
                                .onSubmit(submit)
                        }
                    case .bearer:
                        inputRow("Bearer 令牌") {
                            SecureField("令牌", text: $secret)
                                .textContentType(.password)
                                .focused($focusedField, equals: .secret)
                                .submitLabel(.done)
                                .onSubmit(submit)
                        }
                    case .digest:
                        inputRow("用户名") {
                            TextField("用户名", text: $username)
                                .textContentType(.username)
                                .textInputAutocapitalization(.never)
                                .autocorrectionDisabled()
                                .focused($focusedField, equals: .username)
                                .submitLabel(.next)
                                .onSubmit { focusedField = .secret }
                        }
                        inputRow("密码") {
                            SecureField("密码", text: $secret)
                                .textContentType(.password)
                                .focused($focusedField, equals: .secret)
                                .submitLabel(.done)
                                .onSubmit(submit)
                        }
                    case .apiKey:
                        inputRow("API Key 请求头名称") {
                            TextField("X-API-Key", text: $apiKeyHeader)
                                .textInputAutocapitalization(.never)
                                .autocorrectionDisabled()
                                .focused($focusedField, equals: .apiKeyHeader)
                                .submitLabel(.next)
                                .onSubmit { focusedField = .secret }
                        }
                        inputRow("API Key") {
                            SecureField("API Key", text: $secret)
                                .textContentType(.password)
                                .focused($focusedField, equals: .secret)
                                .submitLabel(.done)
                                .onSubmit(submit)
                        }
                    }
                }
                .disabled(library.isFetching)
                Section("传输加密（可选）") {
                    Toggle("仅允许 HTTPS（可选，默认关闭）", isOn: $requireHttps)
                    Toggle("HTTP Digest 认证（401 挑战后再计算）", isOn: $useDigest)
                    inputRow("自定义 CA PEM") {
                        TextEditor(text: $caPEM).frame(minHeight: 60)
                            .font(.footnote.monospaced())
                            .focused($focusedField, equals: .caPEM)
                            .submitLabel(.done)
                            .onSubmit { focusedField = nil }
                    }
                    inputRow("客户端证书 PEM / iOS PKCS#12 Base64") {
                        TextEditor(text: $clientCertPEM).frame(minHeight: 60)
                            .font(.footnote.monospaced())
                            .focused($focusedField, equals: .clientCertPEM)
                            .submitLabel(.done)
                            .onSubmit { focusedField = nil }
                    }
                    inputRow("客户端私钥 PEM（仅存钥匙串）") {
                        SecureField("私钥", text: $clientKeyPEM)
                            .textContentType(.password)
                            .focused($focusedField, equals: .clientKeyPEM)
                            .submitLabel(.done)
                            .onSubmit { focusedField = nil }
                    }
                    inputRow("私钥密码（仅存钥匙串）") {
                        SecureField("密码", text: $clientKeyPassword)
                            .textContentType(.password)
                            .focused($focusedField, equals: .clientKeyPassword)
                            .submitLabel(.done)
                            .onSubmit { focusedField = nil }
                    }
                }
                .disabled(library.isFetching)
                Section {
                    if library.isFetching {
                        ProgressView("正在读取播客源…")
                    } else {
                        Button("添加播客源", action: submit)
                        .disabled(input.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    }
                    if let message = library.addError {
                        Text(message).foregroundStyle(.secondary)
                    }
                    if let message = library.libraryError {
                        Text(message).foregroundStyle(.secondary)
                    }
                }
                DisclosureGroup("支持的来源与限制") {
                    Text("公开 RSS / Atom 地址与 OPML 订阅列表 URL 通过 URLSession 直接读取；Apple Podcasts 分享链接先请求官方 iTunes Lookup JSON，再读取其中的 RSS。")
                    Text("Spotify、小宇宙、喜马拉雅分享链接需要节目公开 RSS 地址；不会抓取第三方网页。")
                    Text("订阅与单集保存在本机文档目录的 JSON 文件中；认证凭据按订阅 ID 存入系统钥匙串，不写入 JSON，也不会在跨主机跳转时转发。feed 默认允许 http 与 https，建议优先使用 https；仅开启上方开关时才禁止 http。")
                }
            }
            .scrollDismissesKeyboard(.interactively)
            .toolbar {
                ToolbarItemGroup(placement: .keyboard) {
                    Spacer()
                    Button("完成") { focusedField = nil }
                }
            }
            .navigationTitle("添加播客源")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
            }
        }
        .onDisappear { secret = ""; clientKeyPEM = ""; clientKeyPassword = "" }
    }

    private func inputRow<Field: View>(_ title: LocalizedStringKey, @ViewBuilder field: () -> Field) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title)
                .font(.subheadline.weight(.medium))
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityHidden(true)
            field()
                .labelsHidden()
                .frame(minHeight: 44)
        }
    }
}

// MARK: - Library

@MainActor
private struct LibraryView: View {
    @ObservedObject var library: LibrarySession
    @Environment(\.dynamicTypeSize) private var typeSize
    @State private var showingAddSource = false

    private var selection: Binding<String?> {
        Binding(get: { library.selection }, set: { library.select($0) })
    }

    var body: some View {
        NavigationSplitView {
            sidebar
        } detail: {
            detail
        }
        .navigationSplitViewStyle(.balanced)
        .safeAreaInset(edge: .bottom, spacing: 0) {
            PlaybackBar(playback: library.playback)
        }
        .sheet(isPresented: $showingAddSource) {
            AddSourceView(library: library)
        }
    }

    private var sidebar: some View {
        List(selection: selection) {
            subscriptionRows
            if let message = library.libraryError {
                Text(message).font(.footnote).foregroundStyle(.secondary)
            }
        }
        .listStyle(.sidebar)
        .navigationTitle("订阅")
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Button {
                    showingAddSource = true
                } label: {
                    Label("添加播客源", systemImage: "plus")
                }
                .accessibilityLabel("添加播客源")
            }
        }
    }

    @ViewBuilder
    private var subscriptionRows: some View {
        if library.feeds.isEmpty {
            Text("还没有订阅。点按右上角的“+”添加播客源。")
                .foregroundStyle(.secondary)
        }
        ForEach(library.feeds) { feed in
            NavigationLink(value: feed.id) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(feed.title)
                    if !feed.author.isEmpty {
                        Text(feed.author).font(.subheadline).foregroundStyle(.secondary)
                    }
                }
                .fixedSize(horizontal: false, vertical: true)
            }
            .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                Button(role: .destructive) {
                    library.removeFeed(feed.id)
                } label: {
                    Label("删除订阅", systemImage: "trash")
                }
            }
        }
    }

    @ViewBuilder
    private var detail: some View {
        if let feed = library.selectedFeed {
            List {
                if typeSize.isAccessibilitySize {
                    Text(feed.title)
                        .font(.title2.bold())
                        .fixedSize(horizontal: false, vertical: true)
                        .accessibilityAddTraits(.isHeader)
                }
                if let summary = feed.summary, !summary.isEmpty {
                    Text(summary).font(.subheadline).foregroundStyle(.secondary)
                }
                episodeRows(feed: feed)
            }
            .navigationTitle(typeSize.isAccessibilitySize ? "单集" : feed.title)
            .navigationBarTitleDisplayMode(.inline)
        } else {
            Text("选择一个订阅以查看单集")
                .foregroundStyle(.secondary)
                .navigationTitle("单集")
        }
    }

    @ViewBuilder
    private func episodeRows(feed: StoredFeed) -> some View {
        if feed.episodes.isEmpty {
            Text("该播客源没有可播放的单集。").foregroundStyle(.secondary)
        }
        ForEach(feed.episodes) { episode in
            Button {
                library.play(episode, feedID: feed.id)
            } label: {
                Label {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(episode.title).fixedSize(horizontal: false, vertical: true)
                        if let published = episode.publishedAt, !published.isEmpty {
                            Text(published)
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                                .lineLimit(1)
                        }
                    }
                } icon: {
                    Image(systemName: "play.circle")
                }
            }
            .disabled(episode.audioUrl.isEmpty)
        }
    }
}

// MARK: - Playback (AVPlayer; HTTPS-only enclosure check is unchanged)

@MainActor
private final class EpisodePlayback: ObservableObject {
    private let player = AVPlayer()
    private var playerStatus: NSKeyValueObservation?
    private var itemStatus: NSKeyValueObservation?
    private var rangesStatus: NSKeyValueObservation?
    private var positionObserver: Any?
    private var endObserver: NSObjectProtocol?
    private var failureObserver: NSObjectProtocol?
    private var routeObserver: NSObjectProtocol?
    private var interruptionObserver: NSObjectProtocol?
    private var shouldResumeAfterInterruption = false
    private var audioSessionActive = false

    @Published private(set) var itemID = UUID()
    @Published private(set) var title: String?
    @Published private(set) var status = ""
    @Published private(set) var wantsPlayback = false
    @Published private(set) var canPlay = false
    @Published private(set) var position = 0.0
    @Published private(set) var seekRange: ClosedRange<Double>?
    private var ended = false

    init() {
        routeObserver = NotificationCenter.default.addObserver(
            forName: AVAudioSession.routeChangeNotification, object: nil, queue: .main
        ) { [weak self] note in
            Task { @MainActor [weak self] in self?.handleRouteChange(note) }
        }
        interruptionObserver = NotificationCenter.default.addObserver(
            forName: AVAudioSession.interruptionNotification, object: nil, queue: .main
        ) { [weak self] note in
            Task { @MainActor [weak self] in self?.handleInterruption(note) }
        }
        configureRemoteCommands()
    }

    deinit {
        if let routeObserver { NotificationCenter.default.removeObserver(routeObserver) }
        if let interruptionObserver { NotificationCenter.default.removeObserver(interruptionObserver) }
    }

    func play(_ episode: StoredEpisode) {
        reset()
        title = episode.title
        guard let components = URLComponents(string: episode.audioUrl),
              (components.scheme?.lowercased() == "https" || components.scheme?.lowercased() == "http"), let host = components.host, !host.isEmpty,
              components.user == nil, components.password == nil, let url = components.url else {
            status = "该单集没有可播放的 HTTP(S) 音频地址。"
            return
        }
        let item = AVPlayerItem(url: url)
        let id = itemID
        player.replaceCurrentItem(with: item)
        canPlay = true
        // Callbacks may already be queued when an episode changes.
        playerStatus = player.observe(\.timeControlStatus, options: [.new]) { [weak self] _, _ in
            Task { @MainActor [weak self] in self?.update(id: id) }
        }
        itemStatus = item.observe(\.status, options: [.new]) { [weak self] _, _ in
            Task { @MainActor [weak self] in self?.update(id: id) }
        }
        rangesStatus = item.observe(\.seekableTimeRanges, options: [.new]) { [weak self] _, _ in
            Task { @MainActor [weak self] in self?.update(id: id) }
        }
        positionObserver = player.addPeriodicTimeObserver(forInterval: CMTime(seconds: 0.25, preferredTimescale: 600),
                                                          queue: .main) { [weak self] _ in
            Task { @MainActor [weak self] in self?.update(id: id) }
        }
        endObserver = NotificationCenter.default.addObserver(forName: AVPlayerItem.didPlayToEndTimeNotification,
                                                              object: item, queue: .main) { [weak self] _ in
            Task { @MainActor [weak self] in
                guard let self, self.itemID == id, self.canPlay else { return }
                self.ended = true
                self.pause()
                self.update(id: id)
            }
        }
        failureObserver = NotificationCenter.default.addObserver(forName: AVPlayerItem.failedToPlayToEndTimeNotification,
                                                                  object: item, queue: .main) { [weak self] _ in
            Task { @MainActor [weak self] in
                guard let self, self.itemID == id else { return }
                self.fail()
            }
        }
        resume()
    }

    func toggle(id: UUID) {
        guard id == itemID, canPlay else { return }
        if wantsPlayback { pause() } else { resume() }
    }

    func pause() {
        guard wantsPlayback else { return }
        player.pause()
        wantsPlayback = false
        deactivateAudioSession()
        publishNowPlaying()
        update(id: itemID)
    }

    func seek(to seconds: Double, id: UUID) {
        guard id == itemID, canPlay, let seekRange, seekRange.contains(seconds) else { return }
        ended = false
        player.seek(to: CMTime(seconds: seconds, preferredTimescale: 600))
    }

    func reset() {
        itemID = UUID()
        playerStatus = nil
        itemStatus = nil
        rangesStatus = nil
        if let positionObserver { player.removeTimeObserver(positionObserver) }
        if let endObserver { NotificationCenter.default.removeObserver(endObserver) }
        if let failureObserver { NotificationCenter.default.removeObserver(failureObserver) }
        positionObserver = nil
        endObserver = nil
        failureObserver = nil
        player.pause()
        player.currentItem?.cancelPendingSeeks()
        player.replaceCurrentItem(with: nil)
        deactivateAudioSession()
        title = nil
        status = ""
        wantsPlayback = false
        canPlay = false
        ended = false
        position = 0
        seekRange = nil
        publishNowPlaying()
    }

    private func resume() {
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playback, mode: .spokenAudio, options: [])
            try session.setActive(true)
            audioSessionActive = true
            if ended {
                ended = false
                player.seek(to: .zero)
            }
            wantsPlayback = true
            player.play()
            update(id: itemID)
        } catch {
            fail()
        }
    }

    private func deactivateAudioSession() {
        guard audioSessionActive else { return }
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        audioSessionActive = false
    }

    private func fail() {
        canPlay = false
        wantsPlayback = false
        player.pause()
        deactivateAudioSession()
        seekRange = nil
        status = "无法播放。请重新点选单集重试。"
    }

    private func update(id: UUID) {
        guard itemID == id, canPlay, let item = player.currentItem else { return }
        if item.status == .failed { fail(); return }

        let elapsed = player.currentTime().seconds
        if elapsed.isFinite, elapsed >= 0, position != elapsed { position = elapsed }
        let duration = item.duration.seconds
        var range: ClosedRange<Double>?
        if item.status == .readyToPlay, duration.isFinite, duration > 0 {
            let available = item.seekableTimeRanges.compactMap { value -> ClosedRange<Double>? in
                let timeRange = value.timeRangeValue
                let start = max(timeRange.start.seconds, 0)
                let end = min(timeRange.end.seconds, duration)
                return start.isFinite && end.isFinite && end > start ? start...end : nil
            }
            range = available.first(where: { $0.contains(position) }) ?? available.first
        }
        if seekRange != range { seekRange = range }
        let nextStatus: String
        if ended {
            nextStatus = "已播完"
        } else if !wantsPlayback {
            nextStatus = "已暂停"
        } else if player.timeControlStatus == .playing {
            nextStatus = "播放中"
        } else {
            nextStatus = "正在载入音频…"
        }
        if status != nextStatus { status = nextStatus }
        publishNowPlaying(item: item)
    }

    private func handleRouteChange(_ notification: Notification) {
        guard let raw = notification.userInfo?[AVAudioSessionRouteChangeReasonKey] as? NSNumber,
              AVAudioSession.RouteChangeReason(rawValue: raw.uintValue) == .oldDeviceUnavailable else { return }
        if wantsPlayback {
            pause()
            status = "音频输出已断开，播放已暂停。"
        }
    }

    private func handleInterruption(_ notification: Notification) {
        guard let rawType = notification.userInfo?[AVAudioSessionInterruptionTypeKey] as? NSNumber,
              let type = AVAudioSession.InterruptionType(rawValue: rawType.uintValue) else { return }
        switch type {
        case .began:
            shouldResumeAfterInterruption = wantsPlayback
            if wantsPlayback { pause() }
        case .ended:
            let rawOptions = notification.userInfo?[AVAudioSessionInterruptionOptionKey] as? NSNumber
            let options = rawOptions.map { AVAudioSession.InterruptionOptions(rawValue: $0.uintValue) } ?? []
            if shouldResumeAfterInterruption && options.contains(.shouldResume), canPlay {
                resume()
            }
            shouldResumeAfterInterruption = false
        @unknown default:
            shouldResumeAfterInterruption = false
        }
    }

    private func publishNowPlaying(item: AVPlayerItem? = nil) {
#if canImport(MediaPlayer)
        guard title != nil else {
            MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
            return
        }
        let source = item ?? player.currentItem
        var info: [String: Any] = [
            MPMediaItemPropertyTitle: title ?? "",
            MPNowPlayingInfoPropertyPlaybackRate: wantsPlayback ? 1.0 : 0.0,
        ]
        if let duration = source?.duration.seconds, duration.isFinite, duration > 0 {
            info[MPMediaItemPropertyPlaybackDuration] = duration
        }
        let elapsed = player.currentTime().seconds
        if elapsed.isFinite, elapsed >= 0 { info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = elapsed }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
#endif
    }

    private func configureRemoteCommands() {
#if canImport(MediaPlayer)
        let center = MPRemoteCommandCenter.shared()
        center.playCommand.addTarget { [weak self] _ in
            guard let self else { return MPRemoteCommandHandlerStatus.commandFailed }
            Task { @MainActor in
                guard self.canPlay, !self.wantsPlayback else { return }
                self.resume()
            }
            return MPRemoteCommandHandlerStatus.success
        }
        center.pauseCommand.addTarget { [weak self] _ in
            guard let self else { return MPRemoteCommandHandlerStatus.commandFailed }
            Task { @MainActor in self.pause() }
            return MPRemoteCommandHandlerStatus.success
        }
        center.togglePlayPauseCommand.addTarget { [weak self] _ in
            guard let self else { return MPRemoteCommandHandlerStatus.commandFailed }
            Task { @MainActor in
                guard self.canPlay else { return }
                if self.wantsPlayback { self.pause() } else { self.resume() }
            }
            return MPRemoteCommandHandlerStatus.success
        }
#endif
    }
}

@MainActor
private struct PlaybackBar: View {
    @ObservedObject var playback: EpisodePlayback
    @Environment(\.dynamicTypeSize) private var typeSize
    @Environment(\.verticalSizeClass) private var heightClass
    @State private var showingPlayer = false

    private var compact: Bool { heightClass == .compact || typeSize.isAccessibilitySize }

    var body: some View {
        if let title = playback.title {
            controls(title: title, itemID: playback.itemID)
                .id(playback.itemID)
                .sheet(isPresented: $showingPlayer) {
                    if let currentTitle = playback.title {
                        PlayerSheet(playback: playback, title: currentTitle, itemID: playback.itemID)
                    }
                }
                .onChange(of: playback.itemID) { _ in showingPlayer = false }
        }
    }

    private func controls(title: String, itemID: UUID) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 8) {
                Button {
                    showingPlayer = true
                } label: {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(title).font(.headline).lineLimit(compact ? 1 : 2)
                        if !compact {
                            Text(playback.status)
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                                .lineLimit(1)
                        }
                    }
                    .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Text(title))
                .accessibilityHint(Text("打开播放器以查看完整标题和进度"))

                if playback.canPlay {
                    Button {
                        playback.toggle(id: itemID)
                    } label: {
                        Image(systemName: playback.wantsPlayback ? "pause.fill" : "play.fill")
                            .font(.title3)
                            .frame(minWidth: 44, minHeight: 44)
                            .contentShape(Rectangle())
                    }
                    .accessibilityLabel(playback.wantsPlayback ? Text("暂停") : Text("播放"))
                }
            }
            if !compact, let range = playback.seekRange {
                SeekSlider(playback: playback, itemID: itemID, range: range)
            }
        }
        .padding(.horizontal)
        .padding(.vertical, 6)
        .background(.regularMaterial)
    }
}

@MainActor
private struct PlayerSheet: View {
    @ObservedObject var playback: EpisodePlayback
    @Environment(\.dismiss) private var dismiss
    let title: String
    let itemID: UUID

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    Text(title)
                        .font(.title2.bold())
                        .fixedSize(horizontal: false, vertical: true)
                        .accessibilityAddTraits(.isHeader)
                    Text(playback.status).foregroundStyle(.secondary)
                    if playback.canPlay {
                        Button {
                            playback.toggle(id: itemID)
                        } label: {
                            Label(playback.wantsPlayback ? "暂停" : "播放",
                                  systemImage: playback.wantsPlayback ? "pause.fill" : "play.fill")
                                .frame(minHeight: 44)
                        }
                        .buttonStyle(.borderedProminent)
                    }
                    if let range = playback.seekRange {
                        SeekSlider(playback: playback, itemID: itemID, range: range)
                    }
                }
                .frame(maxWidth: 620, alignment: .leading)
                .padding()
                .frame(maxWidth: .infinity, alignment: .center)
            }
            .navigationTitle("正在播放")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("完成") { dismiss() }
                }
            }
        }
    }
}

@MainActor
private struct SeekSlider: View {
    @ObservedObject var playback: EpisodePlayback
    let itemID: UUID
    let range: ClosedRange<Double>
    @State private var dragPosition: Double?

    private var displayedPosition: Double {
        min(max(dragPosition ?? playback.position, range.lowerBound), range.upperBound)
    }

    var body: some View {
        Slider(value: Binding(get: { displayedPosition }, set: { value in
            if dragPosition != nil { dragPosition = value } else { playback.seek(to: value, id: itemID) }
        }), in: range, onEditingChanged: { editing in
            if editing {
                dragPosition = displayedPosition
            } else if dragPosition != nil {
                let target = displayedPosition
                dragPosition = nil
                playback.seek(to: target, id: itemID)
            }
        }) {
            Text("播放进度")
        }
        .accessibilityValue(Text(Duration.seconds(displayedPosition).formatted(.time(pattern: .minuteSecond))))
    }
}
