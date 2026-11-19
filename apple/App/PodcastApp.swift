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
    var cachePolicy: FeedCachePolicy? // Missing in older libraries: no caching.

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

// MARK: - Network storage metadata (public fields only)

/// Non-sensitive metadata for one network storage source. Stored in a dedicated JSON file.
/// Credentials, tokens, client certificates and resolved file URLs are NEVER written here;
/// they are keyed by `id` in `FeedSecretStore` instead.
private struct StoredNetworkSource: Codable, Identifiable, Equatable, Sendable {
    let id: String
    var provider: String
    var label: String
    var baseUrl: String
    var rootPath: String

    var webDavSource: WebDavSource? {
        guard let kind = WebDavProvider(rawValue: provider) else { return nil }
        return WebDavSource(id: id, provider: kind, label: label, baseUrl: baseUrl, rootPath: rootPath)
    }
}

/// A separate file from the feed library. Only source metadata is persisted here.
private struct NetworkStorageStore {
    let fileURL: URL

    init(fileName: String = "podcast-network-storage.json") {
        let directory = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first
            ?? URL(fileURLWithPath: NSTemporaryDirectory(), isDirectory: true)
        fileURL = directory.appendingPathComponent(fileName, isDirectory: false)
    }

    func load() -> [StoredNetworkSource] {
        guard let data = try? Data(contentsOf: fileURL) else { return [] }
        return (try? JSONDecoder().decode([StoredNetworkSource].self, from: data)) ?? []
    }

    func save(_ sources: [StoredNetworkSource]) throws {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        let data = try encoder.encode(sources)
        try data.write(to: fileURL, options: .atomic)
    }
}

// MARK: - Direct SMB source (public metadata only)

/// One SMB source. Only non-sensitive connection metadata is persisted; the password
/// and account never enter this JSON file or LibrarySession/UI state.
private struct StoredSMBSource: Codable, Identifiable, Equatable, Sendable {
    let id: String
    var label: String
    var host: String
    var port: Int
    var share: String
    var rootPath: String
    var transport: SMBTransport

    var endpoint: SMBEndpoint? { try? SMBEndpoint(host: host, port: port, share: share) }
    var root: SMBPath? { try? SMBPath(relative: rootPath) }
}

/// Independent metadata file, separate from feeds and WebDAV storage.
private struct SMBSourceStore {
    let fileURL: URL

    init(fileName: String = "podcast-smb-sources.json") {
        let directory = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first
            ?? URL(fileURLWithPath: NSTemporaryDirectory(), isDirectory: true)
        fileURL = directory.appendingPathComponent(fileName, isDirectory: false)
    }

    func load() -> [StoredSMBSource] {
        guard let data = try? Data(contentsOf: fileURL) else { return [] }
        return (try? JSONDecoder().decode([StoredSMBSource].self, from: data)) ?? []
    }

    func save(_ sources: [StoredSMBSource]) throws {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        try encoder.encode(sources).write(to: fileURL, options: .atomic)
    }
}

/// Credentials are keyed by source.id in the device Keychain (never in JSON).
private enum SMBSecretStore {
    private struct Secret: Codable { let user: String; let domain: String; let password: String }

#if canImport(Security)
    private static func query(for id: String) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: "com.podcastapp.smb-source-credentials.v1",
         kSecAttrAccount as String: id,
         kSecAttrSynchronizable as String: false]
    }

    static func load(for id: String) throws -> SMBCredentials {
        var query = Self.query(for: id)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        if status == errSecItemNotFound { throw SMBSourceError.missingCredentials }
        guard status == errSecSuccess, let data = item as? Data,
              let secret = try? JSONDecoder().decode(Secret.self, from: data) else {
            throw SMBSourceError.credentialStore
        }
        return try SMBCredentials(user: secret.user, domain: secret.domain, password: secret.password)
    }

    static func save(_ credentials: SMBCredentials, for id: String) throws {
        let base = Self.query(for: id)
        let data = try JSONEncoder().encode(Secret(user: credentials.user, domain: credentials.domain,
                                                    password: credentials.password))
        let attributes: [String: Any] = [kSecValueData as String: data,
                                         kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlockedThisDeviceOnly]
        var add = base
        for (key, value) in attributes { add[key] = value }
        let status = SecItemAdd(add as CFDictionary, nil)
        guard status == errSecSuccess || status == errSecDuplicateItem else { throw SMBSourceError.credentialStore }
        if status == errSecDuplicateItem {
            guard SecItemUpdate(base as CFDictionary, attributes as CFDictionary) == errSecSuccess else {
                throw SMBSourceError.credentialStore
            }
        }
    }

    static func remove(for id: String) {
        _ = SecItemDelete(Self.query(for: id) as CFDictionary)
    }
#else
    private static let lock = NSLock()
    private static var memory: [String: SMBCredentials] = [:]

    static func load(for id: String) throws -> SMBCredentials {
        lock.lock(); defer { lock.unlock() }
        guard let credentials = memory[id] else { throw SMBSourceError.missingCredentials }
        return credentials
    }

    static func save(_ credentials: SMBCredentials, for id: String) throws {
        lock.lock(); memory[id] = credentials; lock.unlock()
    }

    static func remove(for id: String) {
        lock.lock(); memory.removeValue(forKey: id); lock.unlock()
    }
#endif
}

@MainActor
private final class SMBStorageSession: ObservableObject {
    @Published private(set) var sources: [StoredSMBSource] = []
    @Published private(set) var entries: [SMBEntry] = []
    @Published private(set) var path = ""
    @Published private(set) var isBrowsing = false
    @Published private(set) var error: String?

    private let store = SMBSourceStore()

    init() { sources = store.load() }

    func source(_ id: String) -> StoredSMBSource? { sources.first { $0.id == id } }

    /// Test the share with a real list request before persisting anything.
    func add(_ source: StoredSMBSource, credentials: SMBCredentials) async throws {
        guard !source.id.isEmpty, let endpoint = source.endpoint, let root = source.root else {
            throw SMBSourceError.invalidSource
        }
        let client = try SMBClient(endpoint: endpoint, credentials: credentials, transport: source.transport)
        _ = try await client.list(root)
        try Task.checkCancellation()
        try SMBSecretStore.save(credentials, for: source.id)
        let updated = sources + [source]
        do {
            try store.save(updated)
        } catch {
            SMBSecretStore.remove(for: source.id)
            throw error
        }
        sources = updated
        error = nil
    }

    func remove(_ id: String) {
        guard let source = source(id) else { return }
        do {
            try store.save(sources.filter { $0.id != id })
            SMBSecretStore.remove(for: source.id)
            sources.removeAll { $0.id == id }
            error = nil
        } catch {
            self.error = SMBSourceError.credentialStore.localizedDescription
        }
    }

    func reset() {
        entries = []
        path = ""
        error = nil
    }

    func setError(_ message: String) { error = message }

    static func join(_ base: String, _ child: String) -> String {
        let parts = (base.isEmpty ? [] : base.split(separator: "/").map(String.init))
            + (child.isEmpty ? [] : child.split(separator: "/").map(String.init))
        return parts.joined(separator: "/")
    }

    func browse(sourceID: String, path target: String) async {
        guard let source = source(sourceID), let root = source.root, let endpoint = source.endpoint else {
            error = SMBSourceError.invalidSource.localizedDescription
            return
        }
        isBrowsing = true
        error = nil
        defer { isBrowsing = false }
        do {
            guard let relative = try? SMBPath(relative: Self.join(root.wirePath, target)) else {
                throw SMBSourceError.invalidPath
            }
            let credentials = try SMBSecretStore.load(for: source.id)
            let client = try SMBClient(endpoint: endpoint, credentials: credentials, transport: source.transport)
            let listed = try await client.list(relative)
            try Task.checkCancellation()
            entries = listed
            path = target
        } catch is CancellationError {
            return
        } catch {
            entries = []
            self.error = safeMessage(error, fallback: SMBSourceError.transport.localizedDescription)
        }
    }

    func navigateUp(sourceID: String) {
        let parts = path.split(separator: "/", omittingEmptySubsequences: true).map(String.init)
        Task { @MainActor in await browse(sourceID: sourceID, path: parts.dropLast().joined(separator: "/")) }
    }

    func refresh(sourceID: String) {
        Task { @MainActor in await browse(sourceID: sourceID, path: path) }
    }

    func open(_ entry: SMBEntry, sourceID: String) {
        guard entry.isDirectory else { return }
        Task { @MainActor in await browse(sourceID: sourceID, path: Self.join(path, entry.name)) }
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

/// Fixed, localized errors for the optional network storage section.
private enum NetworkStorageError: LocalizedError {
    case emptyLabel
    case invalidBaseURL
    case invalidRootPath
    case invalidTransport
    case listFailed
    case notFound

    var errorDescription: String? {
        switch self {
        case .emptyLabel: return "请输入显示名。"
        case .invalidBaseURL: return "地址无效：仅接受不含用户名/密码、查询参数或片段的 http(s) 地址。"
        case .invalidRootPath: return "根路径无效：请使用普通目录路径，且不要包含 .. 或空格。"
        case .invalidTransport: return "传输加密设置无效，请检查证书与私钥。"
        case .listFailed: return "无法读取该目录，请检查地址、认证和权限后重试。"
        case .notFound: return "找不到该网络存储来源。"
        }
    }
}

private func safeMessage(_ error: Error, fallback: String) -> String {
    (error as? LocalizedError)?.errorDescription ?? fallback
}

// MARK: - Direct HTTP(S) fetching via URLSession

/// Per-session delegate: HTTPS policy, redirect sanitizing, custom CA, mTLS and Digest challenges.
class FeedSessionDelegate: NSObject, URLSessionDelegate, URLSessionTaskDelegate, @unchecked Sendable {
    private let transport: SourceTransport
    private let credentials: SourceCredentials
    private let protectedFields: Set<String>
    private let originalURL: URL?
    private let sameOriginOnly: Bool
    private let clientCredential: URLCredential?
#if canImport(Security)
    private let customAnchors: [SecCertificate]
#endif

    init(transport: SourceTransport, credentials: SourceCredentials, originalURL: URL?, sameOriginOnly: Bool = false) {
        self.transport = transport
        self.credentials = credentials
        self.originalURL = originalURL
        self.sameOriginOnly = sameOriginOnly
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

    static func ephemeralConfiguration() -> URLSessionConfiguration {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.urlCache = nil
        configuration.httpCookieStorage = nil
        configuration.httpShouldSetCookies = false
        configuration.urlCredentialStorage = nil
        return configuration
    }

    func urlSession(_ session: URLSession, task: URLSessionTask,
                    willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest,
                    completionHandler: @escaping (URLRequest?) -> Void) {
        guard let target = request.url,
              transport.allowsRedirect(from: task.originalRequest?.url, to: target),
              !sameOriginOnly || FeedCacheMedia.sameOrigin(originalURL, target) else {
            completionHandler(nil)
            return
        }
        var sanitized = request
        if !FeedCacheMedia.sameOrigin(originalURL, target) {
            for field in protectedFields { sanitized.setValue(nil, forHTTPHeaderField: field) }
            sanitized.setValue(nil, forHTTPHeaderField: "Authorization")
        }
        completionHandler(sanitized)
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didReceive challenge: URLAuthenticationChallenge,
                    completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void) {
        urlSession(session, didReceive: challenge, completionHandler: completionHandler)
    }

    func urlSession(_ session: URLSession, didReceive challenge: URLAuthenticationChallenge,
                    completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void) {
        let space = challenge.protectionSpace
        let scheme = originalURL?.scheme?.lowercased()
        let port = originalURL?.port ?? (scheme == "https" ? 443 : 80)
        let crossOrigin = originalURL?.host?.lowercased() != space.host.lowercased() ||
            scheme != space.protocol?.lowercased() || port != space.port || space.proxyType != nil
        switch challenge.protectionSpace.authenticationMethod {
        case NSURLAuthenticationMethodServerTrust:
#if canImport(Security)
            if !customAnchors.isEmpty, !crossOrigin, let trust = challenge.protectionSpace.serverTrust {
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
            if !crossOrigin, let clientCredential {
                completionHandler(.useCredential, clientCredential)
            } else {
                completionHandler(.cancelAuthenticationChallenge, nil)
            }
        case NSURLAuthenticationMethodHTTPDigest:
            let usesDigest = credentials.kind == .digest || transport.digest
            if !crossOrigin, usesDigest, challenge.previousFailureCount == 0,
               !credentials.username.isEmpty, !credentials.secret.isEmpty {
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
        URLSession(configuration: FeedSessionDelegate.ephemeralConfiguration(),
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
    @Published private(set) var feedRefreshStatus: [String: FeedRefreshStatus] = [:]
    private var feedRefreshTasks: [String: Task<Void, Never>] = [:]
    @Published private(set) var storageSources: [StoredNetworkSource] = []
    @Published private(set) var storageSelection: String?
    @Published private(set) var storageEntries: [WebDavEntry] = []
    @Published private(set) var storagePath = ""
    @Published private(set) var isBrowsingStorage = false
    @Published private(set) var storageError: String?

    private let store = LocalFeedStore()
    private let storageStore = NetworkStorageStore()
    let playback = EpisodePlayback()
    let oneDrive = OneDriveSession()
    let smb = SMBStorageSession()
    static let oneDriveSelection = "provider:onedrive"
    static let smbSelectionPrefix = "smb:"
    @Published private(set) var smbSelection: String?
    var selectedSMBSourceID: String? { smbSelection }

    var selectedFeed: StoredFeed? {
        feeds.first { $0.id == selection }
    }

    init() {
        feeds = store.load()
        storageSources = storageStore.load()
    }

    private static let storageSelectionPrefix = "storage:"

    var selectedStorageSourceID: String? { storageSelection }

    private func storageSource(_ id: String) -> StoredNetworkSource? {
        storageSources.first { $0.id == id }
    }

    func select(_ itemID: String?) {
        guard selection != itemID else { return }
        if let itemID {
            if itemID.hasPrefix(Self.storageSelectionPrefix) {
                let sourceID = String(itemID.dropFirst(Self.storageSelectionPrefix.count))
                guard storageSources.contains(where: { $0.id == sourceID }) else { return }
                storageSelection = sourceID
                smbSelection = nil
            } else if itemID.hasPrefix(Self.smbSelectionPrefix) {
                let sourceID = String(itemID.dropFirst(Self.smbSelectionPrefix.count))
                guard smb.source(sourceID) != nil else { return }
                storageSelection = nil
                smbSelection = sourceID
                smb.reset()
            } else if itemID == Self.oneDriveSelection {
                storageSelection = nil
                smbSelection = nil
            } else {
                guard feeds.contains(where: { $0.id == itemID }) else { return }
                storageSelection = nil
                smbSelection = nil
            }
        } else {
            storageSelection = nil
            smbSelection = nil
        }
        selection = itemID
        storagePath = ""
        storageEntries = []
        storageError = nil
        if let id = selectedStorageSourceID {
            Task { @MainActor in await browseStorage(sourceID: id, path: "") }
        } else if let id = smbSelection {
            Task { @MainActor in await smb.browse(sourceID: id, path: "") }
        }
    }

    func removeFeed(_ feedID: String) {
        guard let index = feeds.firstIndex(where: { $0.id == feedID }) else { return }
        var updated = feeds
        updated.remove(at: index)
        guard saveFeeds(updated) else { return }
        if selection == feedID { selection = feeds.first?.id }
        FeedSecretStore.remove(for: feedID)
        let task = feedRefreshTasks[feedID]
        task?.cancel()
        Task { @MainActor in
            await task?.value
            guard !feeds.contains(where: { $0.id == feedID }) else { return }
            feedRefreshStatus.removeValue(forKey: feedID)
            do { try FeedAudioCache(feedID: feedID).prune(keeping: []) }
            catch { libraryError = "无法清理已移除订阅的离线缓存。" }
        }
    }

    // MARK: - Network storage sources

    @discardableResult
    func addNetworkSource(provider: WebDavProvider, label: String, baseURL: String, rootPath: String,
                          credentials: SourceCredentials, transport: SourceTransport) async -> Bool {
        do { try transport.validate() } catch {
            addError = NetworkStorageError.invalidTransport.errorDescription
            return false
        }
        let name = label.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty else {
            addError = NetworkStorageError.emptyLabel.errorDescription
            return false
        }
        guard let base = Self.safeStorageBaseURL(baseURL, requireHttps: transport.requireHttps) else {
            addError = NetworkStorageError.invalidBaseURL.errorDescription
            return false
        }
        guard let root = Self.safeRootPath(rootPath) else {
            addError = NetworkStorageError.invalidRootPath.errorDescription
            return false
        }
        let source = StoredNetworkSource(id: UUID().uuidString, provider: provider.rawValue,
                                         label: name, baseUrl: base, rootPath: root)
        storageSources.append(source)
        storageSelection = source.id
        selection = Self.storageSelectionPrefix + source.id
        storagePath = ""
        storageEntries = []
        storageError = nil
        // Secrets and transport material live only in the Keychain, keyed by source.id.
        FeedSecretStore.save(StoredFeedSecrets(credentials: credentials, transport: transport), for: source.id)
        persistStorage()
        await browseStorage(sourceID: source.id, path: "")
        return true
    }

    func removeNetworkSource(_ id: String) {
        guard let index = storageSources.firstIndex(where: { $0.id == id }) else { return }
        storageSources.remove(at: index)
        FeedSecretStore.remove(for: id)
        if storageSelection == id {
            storageSelection = nil
            selection = nil
            storageEntries = []
            storagePath = ""
            storageError = nil
        }
        persistStorage()
    }

    /// Strict validation: no userinfo, no query/fragment (no credentials in the stored URL).
    fileprivate static func safeStorageBaseURL(_ raw: String, requireHttps: Bool) -> String? {
        let value = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        let forbidden = CharacterSet.whitespacesAndNewlines.union(.controlCharacters)
        guard !value.isEmpty, value.rangeOfCharacter(from: forbidden) == nil,
              !value.contains("\\"),
              var components = URLComponents(string: value),
              let scheme = components.scheme?.lowercased(),
              scheme == "http" || scheme == "https",
              !(requireHttps && scheme != "https"),
              let host = components.percentEncodedHost, !host.isEmpty,
              components.user == nil, components.password == nil,
              components.percentEncodedUser == nil, components.percentEncodedPassword == nil,
              components.query == nil, components.percentEncodedQuery == nil,
              components.fragment == nil,
              components.port.map({ (1...65535).contains($0) }) ?? true,
              components.url != nil else { return nil }
        components.scheme = scheme
        components.percentEncodedHost = host.lowercased()
        return components.url?.absoluteString
    }

    fileprivate static func safeRootPath(_ raw: String) -> String? {
        let value = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if value.isEmpty || value == "/" { return "" }
        let forbidden = CharacterSet.whitespacesAndNewlines.union(.controlCharacters)
        guard !value.contains("\\"), value.rangeOfCharacter(from: forbidden) == nil else { return nil }
        let segments = value.split(separator: "/", omittingEmptySubsequences: true).map(String.init)
        guard !segments.isEmpty, segments.allSatisfy({ $0 != "." && $0 != ".." }) else { return nil }
        return segments.joined(separator: "/")
    }

    /// baseURL + rootPath, used only by WebDavClient; the resolved file URL never enters UI state.
    private static func clientBaseURL(for source: StoredNetworkSource) -> URL? {
        storageClientBaseURL(for: source)
    }

    private static func joinStoragePath(_ base: String, _ child: String) -> String {
        let b = base.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        let c = child.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        guard !c.isEmpty else { return b }
        // Some servers report paths relative to the source root, not to the listed folder.
        if !b.isEmpty, c == b || c.hasPrefix(b + "/") { return c }
        return b.isEmpty ? c : b + "/" + c
    }

    func browseStorage(sourceID: String, path: String) async {
        guard let source = storageSource(sourceID), let base = Self.clientBaseURL(for: source) else {
            storageError = NetworkStorageError.notFound.errorDescription
            return
        }
        isBrowsingStorage = true
        storageError = nil
        defer { isBrowsingStorage = false }
        let secrets = FeedSecretStore.load(for: source.id)
        do {
            let client = WebDavClient(baseURL: base, credentials: secrets.credentials,
                                      transport: secrets.transport)
            let entries = try await client.list(path: path)
            storageEntries = entries.sorted { lhs, rhs in
                if lhs.directory != rhs.directory { return lhs.directory }
                return lhs.name.localizedCaseInsensitiveCompare(rhs.name) == .orderedAscending
            }
            storagePath = path
        } catch {
            storageEntries = []
            storageError = NetworkStorageError.listFailed.errorDescription
        }
    }

    func refreshStorage() {
        guard let id = selectedStorageSourceID else { return }
        Task { @MainActor in await browseStorage(sourceID: id, path: storagePath) }
    }

    func navigateStorageUp() {
        guard let id = selectedStorageSourceID, !storagePath.isEmpty else { return }
        let segments = storagePath.split(separator: "/", omittingEmptySubsequences: true).map(String.init)
        let parent = segments.dropLast().joined(separator: "/")
        Task { @MainActor in await browseStorage(sourceID: id, path: parent) }
    }

    func openStorageDirectory(_ entry: WebDavEntry) {
        guard let id = selectedStorageSourceID else { return }
        let child = Self.joinStoragePath(storagePath, entry.path)
        Task { @MainActor in await browseStorage(sourceID: id, path: child) }
    }

    func playStorageEntry(_ entry: WebDavEntry, sourceID: String) {
        guard let source = storageSource(sourceID), selectedStorageSourceID == sourceID else { return }
        playback.playStorage(source: source, entry: entry,
                             path: Self.joinStoragePath(storagePath, entry.path))
    }

    private func persistStorage() {
        do {
            try storageStore.save(storageSources)
            libraryError = nil
        } catch {
            libraryError = "无法保存网络存储设置，请检查设备存储空间。"
        }
    }

    // MARK: - Direct SMB sources

    @discardableResult
    func addSMBSource(label: String, host: String, port: String, share: String, root: String,
                      user: String, domain: String, password: String,
                      transport: SMBTransport) async -> Bool {
        do {
            let cleanPort = port.trimmingCharacters(in: .whitespacesAndNewlines)
            guard let portValue = Int(cleanPort), (1...65535).contains(portValue) else {
                throw SMBSourceError.invalidSource
            }
            let name = label.trimmingCharacters(in: .whitespacesAndNewlines)
            let endpoint = try SMBEndpoint(host: host, port: portValue, share: share)
            let path = try SMBPath(relative: root)
            let credentials = try SMBCredentials(user: user, domain: domain, password: password)
            let source = StoredSMBSource(id: UUID().uuidString, label: name.isEmpty ? endpoint.share : name,
                                         host: endpoint.host, port: Int(endpoint.port), share: endpoint.share,
                                         rootPath: path.wirePath, transport: transport)
            try await smb.add(source, credentials: credentials)
            try Task.checkCancellation()
            smbSelection = source.id
            storageSelection = nil
            selection = Self.smbSelectionPrefix + source.id
            await smb.browse(sourceID: source.id, path: "")
            return true
        } catch is CancellationError {
            return false
        } catch {
            smb.setError(safeMessage(error, fallback: SMBSourceError.invalidSource.localizedDescription))
            return false
        }
    }

    func removeSMBSource(_ id: String) {
        smb.remove(id)
        if smbSelection == id {
            smbSelection = nil
            selection = nil
            smb.reset()
        }
    }

    func playSMBEntry(_ entry: SMBEntry, sourceID: String) {
        guard let source = smb.source(sourceID), smbSelection == sourceID else { return }
        playback.playSMB(source: source, entry: entry, path: SMBStorageSession.join(smb.path, entry.name))
    }

    func play(_ episode: StoredEpisode, feedID: String) {
        guard selection == feedID, let feed = selectedFeed, feed.episodes.contains(episode) else { return }
        var localURL: URL?
        if (feed.cachePolicy ?? FeedCachePolicy.none) != .none,
           let item = cacheItem(episode), let cache = try? FeedAudioCache(feedID: feedID) {
            localURL = cache.localURL(for: item)
        }
        playback.play(episode, localURL: localURL)
    }

    enum FeedRefreshStatus {
        case fetching, downloading(Int, Int), message(String)
    }

    func isRefreshingFeed(_ id: String) -> Bool { feedRefreshTasks[id] != nil }

    func cancelFeedRefresh(_ id: String) { feedRefreshTasks[id]?.cancel() }

    func setFeedCachePolicy(_ policy: FeedCachePolicy, for id: String) {
        guard !isRefreshingFeed(id), let index = feeds.firstIndex(where: { $0.id == id }),
              (feeds[index].cachePolicy ?? FeedCachePolicy.none) != policy else { return }
        var updated = feeds
        updated[index].cachePolicy = policy
        guard saveFeeds(updated) else { return }
        if policy == .none {
            do {
                try FeedAudioCache(feedID: id).prune(keeping: [])
                feedRefreshStatus[id] = .message("已清除离线缓存。")
            } catch {
                feedRefreshStatus[id] = .message("无法清理缓存；可刷新订阅后重试。")
            }
        } else {
            refreshFeed(id)
        }
    }

    func refreshFeed(_ id: String) {
        guard !isRefreshingFeed(id), let feed = feeds.first(where: { $0.id == id }) else { return }
        feedRefreshStatus[id] = .fetching
        feedRefreshTasks[id] = Task { @MainActor in
            defer { feedRefreshTasks.removeValue(forKey: id) }
            do {
                guard let url = Self.safeHTTPURL(feed.sourceURL) else { throw FeedAddError.invalidReference }
                let secrets = FeedSecretStore.load(for: id)
                let data = try await FeedHTTP.data(from: url, credentials: secrets.credentials,
                                                   transport: secrets.transport, limit: FeedHTTP.maximumFeedBytes)
                let parsed = try PlatformFeedAdapter.parseFeed(data)
                try Task.checkCancellation()
                guard let index = feeds.firstIndex(where: { $0.id == id }) else { return }
                var updated = feeds
                if !parsed.title.isEmpty { updated[index].title = parsed.title }
                updated[index].summary = parsed.description
                updated[index].episodes = parsed.episodes.map(StoredEpisode.init)
                updated[index].refreshedAt = Date()
                guard saveFeeds(updated) else {
                    feedRefreshStatus[id] = .message("无法保存订阅清单，已保留现有缓存。")
                    return
                }
                await refreshAudioCache(updated[index], url: url, secrets: secrets)
            } catch {
                feedRefreshStatus[id] = .message(Task.isCancelled ? "已取消刷新。" : "无法刷新订阅，已保留现有缓存。")
            }
        }
    }

    private func cacheItem(_ episode: StoredEpisode) -> FeedAudioCache.Item? {
        guard let url = URL(string: episode.audioUrl) else { return nil }
        return FeedAudioCache.Item(episodeID: episode.id, mediaURL: url)
    }

    private func refreshAudioCache(_ feed: StoredFeed, url: URL, secrets: StoredFeedSecrets) async {
        let id = feed.id
        let policy = feed.cachePolicy ?? FeedCachePolicy.none
        do {
            try Task.checkCancellation()
            let cache = try FeedAudioCache(feedID: id)
            let candidates = feed.episodes.compactMap { episode -> (item: FeedAudioCache.Item, date: String?)? in
                guard let item = cacheItem(episode), secrets.transport.allows(item.url) else { return nil }
                return (item, episode.publishedAt)
            }
            let items = FeedAudioCache.plan(candidates, policy: policy)
            if !items.isEmpty { try cache.prepare() }
            var ready = 0
            for item in items {
                try Task.checkCancellation()
                feedRefreshStatus[id] = .downloading(ready, items.count)
                do {
                    if cache.localURL(for: item) == nil {
                        try await SubscriptionAudioDownload.save(item, in: cache, feedURL: url,
                            credentials: secrets.credentials, transport: secrets.transport)
                    }
                    ready += 1
                } catch {
                    try Task.checkCancellation()
                }
            }
            try Task.checkCancellation()
            if ready == items.count { try cache.prune(keeping: items) }
            if policy == .none {
                feedRefreshStatus[id] = .message("订阅已刷新。")
            } else if ready == items.count {
                feedRefreshStatus[id] = .message("订阅已刷新，可离线播放 \(ready) 集。")
            } else {
                feedRefreshStatus[id] = .message("已缓存 \(ready)/\(items.count) 集；部分音频不可缓存，已保留现有缓存。")
            }
        } catch {
            feedRefreshStatus[id] = .message(Task.isCancelled ? "已取消下载，完整缓存已保留。" : "无法更新离线缓存。")
        }
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
        var feed = StoredFeed(id: Self.feedID(for: url), sourceURL: url.absoluteString, title: title,
                              author: author, summary: parsed.description,
                              episodes: parsed.episodes.map { StoredEpisode($0) })
        if let index = feeds.firstIndex(where: { $0.id == feed.id }) {
            cancelFeedRefresh(feed.id)
            feed.cachePolicy = feeds[index].cachePolicy
            feeds[index] = feed
        } else {
            feeds.append(feed)
        }
        selection = feed.id
        FeedSecretStore.save(StoredFeedSecrets(credentials: credentials, transport: transport), for: feed.id)
        persist()
    }

    private func persist() { _ = saveFeeds(feeds) }

    @discardableResult
    private func saveFeeds(_ updated: [StoredFeed]) -> Bool {
        do {
            try store.save(updated)
            feeds = updated
            libraryError = nil
            return true
        } catch {
            libraryError = "无法保存本地订阅，请检查设备存储空间。"
            return false
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
    @State private var showingAddStorage = false
    @State private var showingAddSMB = false

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
        .sheet(isPresented: $showingAddStorage) {
            AddNetworkStorageView(library: library)
        }
        .sheet(isPresented: $showingAddSMB) {
            AddSMBView(library: library)
        }
    }

    private var sidebar: some View {
        List(selection: selection) {
            subscriptionRows
            storageRows
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
                Button {
                    showingAddStorage = true
                } label: {
                    Label("添加网络存储", systemImage: "externaldrive.badge.plus")
                }
                .accessibilityLabel("添加网络存储")
                Button {
                    showingAddSMB = true
                } label: {
                    Label("添加 SMB", systemImage: "externaldrive.connected.to.line.below.badge.plus")
                }
                .accessibilityLabel("添加 SMB")
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
    private var storageRows: some View {
        Section("网络存储") {
            ForEach(library.smb.sources) { source in
                NavigationLink(value: "smb:" + source.id) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(source.label)
                        Text("SMB 直连").font(.subheadline).foregroundStyle(.secondary)
                    }
                    .fixedSize(horizontal: false, vertical: true)
                }
                .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                    Button(role: .destructive) { library.removeSMBSource(source.id) } label: {
                        Label("移除来源", systemImage: "trash")
                    }
                }
            }
            NavigationLink(value: LibrarySession.oneDriveSelection) {
                Label("OneDrive", systemImage: "cloud")
            }
            ForEach(library.storageSources) { source in
                NavigationLink(value: "storage:" + source.id) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(source.label)
                        Text(source.webDavSource?.provider.displayName ?? "WebDAV")
                            .font(.subheadline).foregroundStyle(.secondary)
                    }
                    .fixedSize(horizontal: false, vertical: true)
                }
                .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                    Button(role: .destructive) { library.removeNetworkSource(source.id) } label: {
                        Label("移除来源", systemImage: "trash")
                    }
                }
            }
        }
    }

    @ViewBuilder
    private var detail: some View {
        if library.selection == LibrarySession.oneDriveSelection {
            OneDriveBrowserView(session: library.oneDrive, playback: library.playback)
        } else if let sourceID = library.selectedSMBSourceID {
            SMBBrowserView(library: library, sourceID: sourceID)
        } else if let sourceID = library.selectedStorageSourceID {
            StorageBrowserView(library: library, sourceID: sourceID)
        } else if let feed = library.selectedFeed {
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
                cacheControls(feed: feed)
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

    private func cacheControls(feed: StoredFeed) -> some View {
        let busy = library.isRefreshingFeed(feed.id)
        return Section("离线缓存") {
            Picker("缓存策略", selection: Binding(
                get: { feed.cachePolicy ?? FeedCachePolicy.none },
                set: { library.setFeedCachePolicy($0, for: feed.id) }
            )) {
                Text("不缓存").tag(FeedCachePolicy.none)
                Text("最新 5 集").tag(FeedCachePolicy.latestFive)
                Text("最新 10 集").tag(FeedCachePolicy.latestTen)
                Text("全部").tag(FeedCachePolicy.all)
            }
            .disabled(busy)
            if busy {
                Button("取消", role: .cancel) { library.cancelFeedRefresh(feed.id) }
            } else {
                Button((feed.cachePolicy ?? FeedCachePolicy.none) == .none ? "刷新订阅" : "刷新缓存") {
                    library.refreshFeed(feed.id)
                }
            }
            if let status = library.feedRefreshStatus[feed.id] {
                switch status {
                case .fetching:
                    ProgressView("正在刷新订阅…")
                case let .downloading(ready, total):
                    ProgressView(value: Double(ready), total: Double(total)) {
                        Text("已缓存 \(ready)/\(total) 集")
                    }
                case let .message(message):
                    Text(message).font(.footnote).foregroundStyle(.secondary)
                }
            }
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

private extension WebDavProvider {
    var displayName: String {
        switch self {
        case .webdav: return "WebDAV"
        case .jianguoyun: return "坚果云 WebDAV"
        case .zspace: return "极空间 WebDAV"
        case .feilian: return "飞连企业网络（WebDAV）"
        }
    }
}

// MARK: - Add network storage

@MainActor
private struct AddNetworkStorageView: View {
    @ObservedObject var library: LibrarySession
    @Environment(\.dismiss) private var dismiss
    @State private var provider: WebDavProvider = .webdav
    @State private var label = ""
    @State private var baseURL = ""
    @State private var rootPath = ""
    @State private var checkTask: Task<Void, Never>?
    @State private var checkResult: String?

    // Same input-state shape as AddSourceView, feeding SourceCredentials / SourceTransport.
    @State private var authKind: CredentialKind = .basic
    @State private var username = ""
    @State private var secret = ""
    @State private var apiKeyHeader = ""
    @State private var requireHttps = false
    @State private var useDigest = false
    @State private var caPEM = ""
    @State private var clientCertPEM = ""
    @State private var clientKeyPEM = ""
    @State private var clientKeyPassword = ""

    @FocusState private var focusedField: Field?

    private enum Field: Hashable {
        case label, baseURL, rootPath, username, secret, apiKeyHeader
        case caPEM, clientCertPEM, clientKeyPEM, clientKeyPassword
    }

    private func applyProvider(_ value: WebDavProvider) {
        switch value {
        case .jianguoyun:
            if baseURL.isEmpty { baseURL = "https://dav.jianguoyun.com/dav/" }
            authKind = .basic
        case .zspace:
            authKind = .basic
        case .feilian:
            baseURL = ""
            username = ""
            secret = ""
            authKind = .none
        case .webdav:
            break
        }
        checkResult = nil
    }

    private func checkEnterpriseWebDav() {
        guard checkTask == nil else { return }
        focusedField = nil
        let credentials = SourceCredentials(kind: authKind, username: username, secret: secret, header: apiKeyHeader)
        let transport = SourceTransport(requireHttps: requireHttps, customCaPem: caPEM,
                                        clientCertPem: clientCertPEM, clientKeyPem: clientKeyPEM,
                                        clientKeyPassword: clientKeyPassword, digest: useDigest)
        do { try transport.validate() } catch {
            checkResult = "证书或传输设置无效。"
            return
        }
        guard let base = LibrarySession.safeStorageBaseURL(baseURL, requireHttps: requireHttps),
              let root = LibrarySession.safeRootPath(rootPath),
              let url = storageClientBaseURL(for: StoredNetworkSource(id: "", provider: provider.rawValue,
                                                                     label: "", baseUrl: base, rootPath: root)) else {
            checkResult = "地址或根路径无效，请填写企业分配的 WebDAV 地址。"
            return
        }
        checkResult = nil
        checkTask = Task { @MainActor in
            defer { checkTask = nil }
            do {
                let result = try await WebDavClient(baseURL: url, credentials: credentials, transport: transport).testConnection()
                try Task.checkCancellation()
                switch result {
                case .ready: checkResult = "已收到有效 WebDAV 响应；这不是飞连登录状态。"
                case .authenticationRequired: checkResult = "已到达服务，但需要该 WebDAV 来源自己的凭据。"
                case .forbidden: checkResult = "已到达服务，但访问被拒绝，请核对企业策略和目录权限。"
                case .redirect: checkResult = "服务要求跳转（可能是网页统一登录），请使用直连 WebDAV 地址。"
                case .notWebDav: checkResult = "未收到有效 WebDAV 响应，请核对路径，不要填写网页登录页。"
                case .badResponse: checkResult = "服务返回异常状态，请检查企业网关或 WebDAV 服务。"
                }
            } catch is CancellationError {
                return
            } catch {
                checkResult = "无法完成连接，请检查飞连连接、分流、企业 DNS、证书和目标服务。"
            }
        }
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
            if await library.addNetworkSource(provider: provider, label: label, baseURL: baseURL,
                                              rootPath: rootPath, credentials: credentials,
                                              transport: transport) {
                dismiss()
            }
        }
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("网络存储（可选）") {
                    Picker("来源类型", selection: $provider) {
                        Text("通用 WebDAV").tag(WebDavProvider.webdav)
                        Text("坚果云").tag(WebDavProvider.jianguoyun)
                        Text("极空间").tag(WebDavProvider.zspace)
                        Text(WebDavProvider.feilian.displayName).tag(WebDavProvider.feilian)
                    }
                    .onChange(of: provider) { newValue in applyProvider(newValue) }
                    switch provider {
                    case .jianguoyun:
                        Text("坚果云官方 WebDAV 地址是 https://dav.jianguoyun.com/dav/ 。密码请填写在坚果云 WebDAV 设置中生成的应用密码，而不是网页登录密码。")
                            .font(.footnote).foregroundStyle(.secondary)
                    case .zspace:
                        Text("请先在自己的极空间 NAS 设置中开启 WebDAV 服务，再填写设备的实际 HTTPS 地址。本应用不使用任何私有 API。")
                            .font(.footnote).foregroundStyle(.secondary)
                    case .feilian:
                        Text("先在飞连客户端完成企业登录，再填写管理员提供的内网 WebDAV 地址。本应用使用系统路由，不接受飞连密码或浏览器 SSO 页面。")
                            .font(.footnote).foregroundStyle(.secondary)
                    case .webdav:
                        Text("填写支持 WebDAV 的服务器地址；仅使用标准的 PROPFIND / GET 只读请求。")
                            .font(.footnote).foregroundStyle(.secondary)
                    }
                    inputRow("显示名") {
                        TextField("例如：家庭 NAS", text: $label)
                            .textInputAutocapitalization(.never)
                            .focused($focusedField, equals: .label)
                    }
                    inputRow("地址") {
                        TextField("https://dav.example.com/", text: $baseURL)
                            .keyboardType(.URL)
                            .textContentType(.URL)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                            .focused($focusedField, equals: .baseURL)
                            .submitLabel(.next)
                            .onSubmit { focusedField = .rootPath }
                    }
                    inputRow("根路径（可选）") {
                        TextField("/", text: $rootPath)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                            .focused($focusedField, equals: .rootPath)
                    }
                }
                .disabled(library.isFetching || checkTask != nil)
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
                    case .basic, .digest:
                        inputRow("用户名") {
                            TextField("用户名", text: $username)
                                .textContentType(.username)
                                .textInputAutocapitalization(.never)
                                .autocorrectionDisabled()
                                .focused($focusedField, equals: .username)
                                .submitLabel(.next)
                                .onSubmit { focusedField = .secret }
                        }
                        inputRow(provider == .jianguoyun ? "应用密码" : "密码") {
                            SecureField("密码或令牌", text: $secret)
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
                    case .apiKey:
                        inputRow("请求头名称") {
                            TextField("X-API-Key", text: $apiKeyHeader)
                                .textInputAutocapitalization(.never)
                                .autocorrectionDisabled()
                                .focused($focusedField, equals: .apiKeyHeader)
                        }
                        inputRow("API Key") {
                            SecureField("API Key", text: $secret)
                                .textContentType(.password)
                                .focused($focusedField, equals: .secret)
                        }
                    }
                }
                .disabled(library.isFetching || checkTask != nil)
                Section("传输加密（可选）") {
                    Toggle("仅允许 HTTPS", isOn: $requireHttps)
                    Toggle("HTTP Digest 认证（401 挑战后再计算）", isOn: $useDigest)
                    inputRow("自定义 CA PEM") {
                        TextEditor(text: $caPEM).frame(minHeight: 60)
                            .font(.footnote.monospaced())
                            .focused($focusedField, equals: .caPEM)
                    }
                    inputRow("客户端证书 PEM / iOS PKCS#12 Base64") {
                        TextEditor(text: $clientCertPEM).frame(minHeight: 60)
                            .font(.footnote.monospaced())
                            .focused($focusedField, equals: .clientCertPEM)
                    }
                    inputRow("客户端私钥 PEM（仅存钥匙串）") {
                        SecureField("私钥", text: $clientKeyPEM)
                            .textContentType(.password)
                            .focused($focusedField, equals: .clientKeyPEM)
                    }
                    inputRow("私钥密码（仅存钥匙串）") {
                        SecureField("密码", text: $clientKeyPassword)
                            .textContentType(.password)
                            .focused($focusedField, equals: .clientKeyPassword)
                    }
                }
                .disabled(library.isFetching || checkTask != nil)
                Section {
                    if library.isFetching {
                        ProgressView("正在读取目录…")
                    } else {
                        Button("添加网络存储", action: submit)
                            .disabled(label.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ||
                                      baseURL.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    }
                    if provider == .feilian {
                        Button(checkTask == nil ? "测试企业 WebDAV（不保存）" : "正在测试…", action: checkEnterpriseWebDav)
                            .disabled(checkTask != nil || baseURL.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                        if let checkResult { Text("上次测试：" + checkResult).foregroundStyle(.secondary) }
                    }
                    if let message = library.addError {
                        Text(message).foregroundStyle(.secondary)
                    }
                    if let message = library.libraryError {
                        Text(message).foregroundStyle(.secondary)
                    }
                }
                DisclosureGroup("隐私说明") {
                    Text("显示名、地址和根路径保存在本机单独的 JSON 文件；用户名、密码/令牌与证书按来源 ID 存入系统钥匙串。真实文件地址和凭据不会写入订阅文件、JSON 或界面状态。")
                }
            }
            .scrollDismissesKeyboard(.interactively)
            .toolbar {
                ToolbarItemGroup(placement: .keyboard) {
                    Spacer()
                    Button("完成") { focusedField = nil }
                }
            }
            .navigationTitle("网络存储")
            .onDisappear { checkTask?.cancel() }
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
            }
        }
        .onDisappear { secret = ""; clientKeyPEM = ""; clientKeyPassword = "" }
    }

    private func inputRow<Content: View>(_ title: LocalizedStringKey,
                                         @ViewBuilder field: () -> Content) -> some View {
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

// MARK: - Network storage browser

@MainActor
private struct StorageBrowserView: View {
    @ObservedObject var library: LibrarySession
    let sourceID: String

    private static let audioExtensions: Set<String> = [
        "mp3", "m4a", "m4b", "aac", "wav", "flac", "ogg", "opus", "aiff", "aif", "wma"]

    private var source: StoredNetworkSource? {
        library.storageSources.first { $0.id == sourceID }
    }

    var body: some View {
        List {
            if source != nil {
                Section {
                    HStack(spacing: 8) {
                        Image(systemName: "folder")
                        Text("当前目录：/\(library.storagePath)")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    HStack(spacing: 16) {
                        Button { library.navigateStorageUp() } label: {
                            Label("返回上级", systemImage: "arrow.up")
                        }
                        .disabled(library.storagePath.isEmpty || library.isBrowsingStorage)
                        Button { library.refreshStorage() } label: {
                            Label("刷新", systemImage: "arrow.clockwise")
                        }
                        .disabled(library.isBrowsingStorage)
                    }
                }
                Section("内容") {
                    if library.isBrowsingStorage {
                        ProgressView("正在读取目录…")
                    }
                    if let message = library.storageError {
                        Text(message).font(.footnote).foregroundStyle(.secondary)
                    }
                    if !library.isBrowsingStorage && library.storageEntries.isEmpty &&
                        library.storageError == nil {
                        Text("该目录为空。").foregroundStyle(.secondary)
                    }
                    ForEach(library.storageEntries, id: \.path) { entry in
                        row(entry)
                    }
                }
            }
        }
        .navigationTitle(source?.label ?? "网络存储")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            if library.storageSelection != sourceID {
                library.select("storage:" + sourceID)
            }
        }
    }

    @ViewBuilder
    private func row(_ entry: WebDavEntry) -> some View {
        if entry.directory {
            Button { library.openStorageDirectory(entry) } label: {
                Label(entry.name, systemImage: "folder")
            }
            .disabled(library.isBrowsingStorage)
        } else if Self.isAudio(entry) {
            Button { library.playStorageEntry(entry, sourceID: sourceID) } label: {
                Label {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(entry.name).fixedSize(horizontal: false, vertical: true)
                        if let size = entry.size, size > 0 {
                            Text(Self.sizeText(size)).font(.footnote).foregroundStyle(.secondary)
                        }
                    }
                } icon: {
                    Image(systemName: "play.circle")
                }
            }
        } else {
            Label(entry.name, systemImage: "doc").foregroundStyle(.secondary)
        }
    }

    private static func isAudio(_ entry: WebDavEntry) -> Bool {
        audioExtensions.contains((entry.name as NSString).pathExtension.lowercased())
    }

    private static func sizeText(_ bytes: Int64) -> String {
        let formatter = ByteCountFormatter()
        formatter.countStyle = .file
        return formatter.string(fromByteCount: bytes)
    }
}

// MARK: - Add direct SMB source

@MainActor
private struct AddSMBView: View {
    @ObservedObject var library: LibrarySession
    @Environment(\.dismiss) private var dismiss
    @State private var label = ""
    @State private var host = ""
    @State private var port = "445"
    @State private var share = ""
    @State private var root = ""
    @State private var username = ""
    @State private var domain = ""
    @State private var password = ""
    @State private var transport: SMBTransport = .encrypted
    @State private var busy = false
    @FocusState private var focused: Field?

    private enum Field: Hashable { case label, host, port, share, root, username, domain, password }

    private func submit() {
        guard !busy else { return }
        focused = nil
        let secret = password
        password = ""
        Task { @MainActor in
            busy = true
            defer { busy = false }
            if await library.addSMBSource(label: label, host: host, port: port, share: share, root: root,
                                          user: username, domain: domain, password: secret,
                                          transport: transport) {
                dismiss()
            }
        }
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("SMB 2/3 直连（只读）") {
                    inputRow("显示名") { TextField("例如：家庭 NAS", text: $label).focused($focused, equals: .label) }
                    inputRow("主机") {
                        TextField("nas.local", text: $host)
                            .keyboardType(.URL).textContentType(.URL)
                            .textInputAutocapitalization(.never).autocorrectionDisabled()
                            .focused($focused, equals: .host)
                    }
                    inputRow("TCP 端口") { TextField("445", text: $port).keyboardType(.numberPad).focused($focused, equals: .port) }
                    inputRow("共享名") {
                        TextField("Podcasts", text: $share)
                            .textInputAutocapitalization(.never).autocorrectionDisabled()
                            .focused($focused, equals: .share)
                    }
                    inputRow("根目录（可选）") {
                        TextField("播客/访谈", text: $root)
                            .textInputAutocapitalization(.never).autocorrectionDisabled()
                            .focused($focused, equals: .root)
                    }
                }
                .disabled(busy)
                Section("只读账户") {
                    inputRow("用户名") {
                        TextField("用户名", text: $username)
                            .textContentType(.username)
                            .textInputAutocapitalization(.never).autocorrectionDisabled()
                            .focused($focused, equals: .username)
                    }
                    inputRow("域 / 工作组（可选）") {
                        TextField("WORKGROUP", text: $domain)
                            .textInputAutocapitalization(.never).autocorrectionDisabled()
                            .focused($focused, equals: .domain)
                    }
                    inputRow("密码") {
                        SecureField("密码", text: $password)
                            .textContentType(.password)
                            .focused($focused, equals: .password)
                            .submitLabel(.done).onSubmit(submit)
                    }
                }
                .disabled(busy)
                Section("传输策略") {
                    Picker("SMB 传输", selection: $transport) {
                        Text("要求 SMB3 加密（推荐）").tag(SMBTransport.encrypted)
                        Text("要求 SMB2/3 签名").tag(SMBTransport.signed)
                        Text("仅 TLS（不兼容直连）").tag(SMBTransport.tlsOnly)
                    }
                    if transport == .tlsOnly {
                        Text("仅 TLS 不兼容 SMB 直连：签名和 SMB3 加密都不是 TLS。请改选加密或签名。")
                            .font(.footnote).foregroundStyle(.red)
                    } else {
                        Text("签名保护完整性但不加密；请仅在受信任局域网或 VPN 使用专用只读账户。")
                            .font(.footnote).foregroundStyle(.secondary)
                    }
                }
                .disabled(busy)
                Section {
                    if busy {
                        ProgressView("正在验证共享…")
                    } else {
                        Button("测试并添加 SMB", action: submit)
                            .disabled(transport == .tlsOnly || host.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ||
                                      share.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ||
                                      username.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ||
                                      password.isEmpty)
                    }
                    if let message = library.smb.error { Text(message).foregroundStyle(.secondary) }
                }
                DisclosureGroup("隐私说明") {
                    Text("显示名、主机、端口、共享名和根目录保存在独立的本机 JSON 文件；用户名、域和密码按来源 ID 存入本机钥匙串，不同步到 iCloud。真实文件地址不会写入 JSON 或播放器状态。")
                        .font(.footnote).foregroundStyle(.secondary)
                }
            }
            .scrollDismissesKeyboard(.interactively)
            .toolbar {
                ToolbarItemGroup(placement: .keyboard) {
                    Spacer()
                    Button("完成") { focused = nil }
                }
            }
            .navigationTitle("添加 SMB")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
            }
        }
        .onDisappear { password = "" }
    }

    private func inputRow<Content: View>(_ title: LocalizedStringKey,
                                         @ViewBuilder field: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title).font(.subheadline.weight(.medium))
                .fixedSize(horizontal: false, vertical: true).accessibilityHidden(true)
            field().labelsHidden().frame(minHeight: 44)
        }
    }
}

// MARK: - Direct SMB browser

@MainActor
private struct SMBBrowserView: View {
    @ObservedObject var library: LibrarySession
    let sourceID: String

    private var source: StoredSMBSource? { library.smb.source(sourceID) }

    var body: some View {
        List {
            if source != nil {
                Section {
                    HStack(spacing: 8) {
                        Image(systemName: "folder")
                        Text("当前目录：/\(library.smb.path)")
                            .font(.footnote).foregroundStyle(.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    HStack(spacing: 16) {
                        Button { library.smb.navigateUp(sourceID: sourceID) } label: {
                            Label("返回上级", systemImage: "arrow.up")
                        }
                        .disabled(library.smb.path.isEmpty || library.smb.isBrowsing)
                        Button { library.smb.refresh(sourceID: sourceID) } label: {
                            Label("刷新", systemImage: "arrow.clockwise")
                        }
                        .disabled(library.smb.isBrowsing)
                    }
                }
                Section("内容") {
                    if library.smb.isBrowsing { ProgressView("正在读取 SMB 目录…") }
                    if let message = library.smb.error {
                        Text(message).font(.footnote).foregroundStyle(.secondary)
                    }
                    if !library.smb.isBrowsing && library.smb.entries.isEmpty && library.smb.error == nil {
                        Text("该目录为空。").foregroundStyle(.secondary)
                    }
                    ForEach(library.smb.entries) { entry in
                        if entry.isDirectory {
                            Button { library.smb.open(entry, sourceID: sourceID) } label: {
                                Label(entry.name, systemImage: "folder")
                            }
                            .disabled(library.smb.isBrowsing)
                        } else if entry.audioExtension != nil {
                            Button { library.playSMBEntry(entry, sourceID: sourceID) } label: {
                                Label(entry.name, systemImage: "play.circle")
                            }
                        } else {
                            Label(entry.name, systemImage: "doc").foregroundStyle(.secondary)
                        }
                    }
                }
                Section {
                    Text("SMB 只读访问。凭据仅存本机钥匙串；音频按 Range 分块读取，不写入订阅或 JSON。")
                        .font(.footnote).foregroundStyle(.secondary)
                }
            }
        }
        .navigationTitle(source?.label ?? "SMB")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            if library.selection != "smb:" + sourceID { library.select("smb:" + sourceID) }
        }
    }
}

// MARK: - Network storage playback bridge

/// Custom scheme only. The host carries the source id and the path is base64url encoded,
/// so no real URL, host or credential ever appears in AVPlayer/UI state.
private enum StoragePlaybackURL {
    static let scheme = "podcast-storage"
    private static let allowedSourceID = CharacterSet(
        charactersIn: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-")

    static func make(sourceID: String, path: String) -> URL? {
        guard !sourceID.isEmpty, !path.isEmpty,
              sourceID.rangeOfCharacter(from: allowedSourceID.inverted) == nil else { return nil }
        let encoded = Data(path.utf8).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
        var components = URLComponents()
        components.scheme = scheme
        components.host = sourceID.lowercased()
        components.path = "/" + encoded
        return components.url
    }

    static func parse(_ url: URL) -> (sourceID: String, path: String)? {
        guard let components = URLComponents(url: url, resolvingAgainstBaseURL: false),
              components.scheme?.lowercased() == scheme,
              let host = components.host, !host.isEmpty,
              host.rangeOfCharacter(from: allowedSourceID.inverted) == nil else { return nil }
        var raw = String(components.path.dropFirst())
        guard !raw.isEmpty else { return nil }
        raw = raw.replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        let remainder = raw.count % 4
        if remainder != 0 { raw += String(repeating: "=", count: 4 - remainder) }
        guard let data = Data(base64Encoded: raw),
              let path = String(data: data, encoding: .utf8), !path.isEmpty else { return nil }
        return (host, path)
    }
}

/// Shared base URL (server address + root path). Never contains credentials.
private func storageClientBaseURL(for source: StoredNetworkSource) -> URL? {
    guard var components = URLComponents(string: source.baseUrl) else { return nil }
    var path = components.percentEncodedPath
    if !path.hasSuffix("/") { path += "/" }
    if !source.rootPath.isEmpty {
        let encoded = source.rootPath.split(separator: "/", omittingEmptySubsequences: true)
            .map { $0.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? "" }
            .joined(separator: "/")
        path += encoded + "/"
    }
    components.percentEncodedPath = path
    return components.url
}

private enum StoragePlaybackError: LocalizedError {
    case sourceMissing, invalidURL, invalidRange, badResponse

    var errorDescription: String? {
        switch self {
        case .sourceMissing: return "网络存储来源不存在。"
        case .invalidURL: return "音频地址无效。"
        case .invalidRange: return "音频范围请求无效。"
        case .badResponse: return "无法读取音频数据。"
        }
    }
}

/// Resolves custom-scheme requests into an authenticated WebDAV GET with Range support.
/// Reuses the same credential headers, custom CA, client certificate, digest and redirect
/// policy as feed requests; handles only URLs created by StoragePlaybackURL.
private final class StorageResourceLoader: NSObject, AVAssetResourceLoaderDelegate, @unchecked Sendable {
    private let lock = NSLock()
    private var tasks: [ObjectIdentifier: Task<Void, Never>] = [:]

    func resourceLoader(_ resourceLoader: AVAssetResourceLoader,
                        shouldWaitForLoadingOfRequestedResource loadingRequest: AVAssetResourceLoadingRequest) -> Bool {
        guard let url = loadingRequest.request.url,
              let target = StoragePlaybackURL.parse(url) else { return false }
        let key = ObjectIdentifier(loadingRequest)
        let task = Task { [weak self] in
            await self?.fulfill(target, request: loadingRequest)
            self?.forget(key)
        }
        lock.lock()
        tasks[key] = task
        lock.unlock()
        return true
    }

    func resourceLoader(_ resourceLoader: AVAssetResourceLoader,
                        didCancel loadingRequest: AVAssetResourceLoadingRequest) {
        let key = ObjectIdentifier(loadingRequest)
        lock.lock()
        let task = tasks.removeValue(forKey: key)
        lock.unlock()
        task?.cancel()
    }

    private func forget(_ key: ObjectIdentifier) {
        lock.lock()
        tasks.removeValue(forKey: key)
        lock.unlock()
    }

    private func fulfill(_ target: (sourceID: String, path: String),
                         request: AVAssetResourceLoadingRequest) async {
        do {
            try Task.checkCancellation()
            guard let stored = NetworkStorageStore().load().first(where: { $0.id == target.sourceID }),
                  stored.webDavSource != nil,
                  let base = storageClientBaseURL(for: stored) else {
                throw StoragePlaybackError.sourceMissing
            }
            let secrets = FeedSecretStore.load(for: stored.id)
            let client = WebDavClient(baseURL: base, credentials: secrets.credentials,
                                      transport: secrets.transport)
            let realURL = try client.fileURL(path: target.path)
            guard let scheme = realURL.scheme?.lowercased(),
                  scheme == "https" || scheme == "http" else { throw StoragePlaybackError.invalidURL }

            let dataRequest = request.dataRequest
            let offset = dataRequest?.requestedOffset ?? 0
            let length = dataRequest?.requestedLength ?? 0
            let wantsAll = dataRequest?.requestsAllDataToEndOfResource ?? true
            guard offset >= 0 else { throw StoragePlaybackError.invalidRange }

            var urlRequest = URLRequest(url: realURL, cachePolicy: .reloadIgnoringLocalCacheData,
                                        timeoutInterval: 60)
            urlRequest.httpShouldHandleCookies = false
            if wantsAll {
                urlRequest.setValue("bytes=\(offset)-", forHTTPHeaderField: "Range")
            } else {
                guard length > 0 else { throw StoragePlaybackError.invalidRange }
                urlRequest.setValue("bytes=\(offset)-\(offset + Int64(length) - 1)",
                                    forHTTPHeaderField: "Range")
            }
            // Basic/Bearer/API Key are static headers; Digest is answered on challenge.
            for (field, value) in secrets.credentials.httpHeaders {
                urlRequest.setValue(value, forHTTPHeaderField: field)
            }

            let configuration = URLSessionConfiguration.ephemeral
            configuration.urlCache = nil
            configuration.httpCookieStorage = nil
            configuration.httpShouldSetCookies = false
            configuration.urlCredentialStorage = nil
            let session = URLSession(configuration: configuration,
                                     delegate: FeedSessionDelegate(transport: secrets.transport,
                                                                   credentials: secrets.credentials,
                                                                   originalURL: realURL),
                                     delegateQueue: nil)
            defer { session.finishTasksAndInvalidate() }
            let (data, response) = try await session.data(for: urlRequest)
            try Task.checkCancellation()
            guard let http = response as? HTTPURLResponse,
                  http.statusCode == 200 || http.statusCode == 206 else {
                throw StoragePlaybackError.badResponse
            }

            var totalLength: Int64?
            if http.statusCode == 206,
               let contentRange = http.value(forHTTPHeaderField: "Content-Range"),
               let rangePart = contentRange.split(separator: " ").last,
               let slash = rangePart.firstIndex(of: "/") {
                let startText = rangePart[rangePart.startIndex..<slash].split(separator: "-").first ?? ""
                if let start = Int64(startText), start != offset {
                    throw StoragePlaybackError.badResponse
                }
                totalLength = Int64(rangePart[rangePart.index(after: slash)...])
            } else if http.statusCode == 200 {
                totalLength = offset + Int64(data.count)
            }

            if let info = request.contentInformationRequest {
                info.isByteRangeAccessSupported = http.statusCode == 206
                info.contentType = http.mimeType ?? "audio/mpeg"
                if let totalLength, totalLength >= 0 { info.contentLength = totalLength }
            }
            if let dataRequest {
                dataRequest.respond(with: wantsAll ? data : data.prefix(length))
            }
            request.finishLoading()
        } catch {
            if error is CancellationError {
                request.finishLoading()
            } else {
                request.finishLoading(with: error)
            }
        }
    }
}

// MARK: - Direct SMB playback bridge

/// Opaque custom-scheme URL: host is only the source id, the path is base64url encoded.
/// No real host, share, file URL or credential is exposed to AVPlayer or UI state.
private enum SMBPlaybackURL {
    static let scheme = "podcast-smb"
    private static let allowedSourceID = CharacterSet(
        charactersIn: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-")

    static func make(sourceID: String, path: String) -> URL? {
        guard !sourceID.isEmpty, !path.isEmpty,
              sourceID.rangeOfCharacter(from: allowedSourceID.inverted) == nil else { return nil }
        let encoded = Data(path.utf8).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
        var components = URLComponents()
        components.scheme = scheme
        components.host = sourceID.lowercased()
        components.path = "/" + encoded
        return components.url
    }

    static func parse(_ url: URL) -> (sourceID: String, path: String)? {
        guard let components = URLComponents(url: url, resolvingAgainstBaseURL: false),
              components.scheme?.lowercased() == scheme,
              let host = components.host, !host.isEmpty,
              host.rangeOfCharacter(from: allowedSourceID.inverted) == nil else { return nil }
        var raw = String(components.path.dropFirst())
        guard !raw.isEmpty else { return nil }
        raw = raw.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        let remainder = raw.count % 4
        if remainder != 0 { raw += String(repeating: "=", count: 4 - remainder) }
        guard let data = Data(base64Encoded: raw),
              let path = String(data: data, encoding: .utf8), !path.isEmpty else { return nil }
        return (host, path)
    }
}

/// Independent delegate: the WebDAV loader speaks HTTP only and cannot be reused for SMB.
/// It opens an SMBReadHandle and serves bounded, offset-based Range reads via SMBByteRange.
private final class SMBResourceLoader: NSObject, AVAssetResourceLoaderDelegate, @unchecked Sendable {
    private let lock = NSLock()
    private var tasks: [ObjectIdentifier: Task<Void, Never>] = [:]

    func resourceLoader(_ resourceLoader: AVAssetResourceLoader,
                        shouldWaitForLoadingOfRequestedResource loadingRequest: AVAssetResourceLoadingRequest) -> Bool {
        guard let url = loadingRequest.request.url, let target = SMBPlaybackURL.parse(url) else { return false }
        let key = ObjectIdentifier(loadingRequest)
        let task = Task { [weak self] in
            await self?.fulfill(target, request: loadingRequest)
            self?.forget(key)
        }
        lock.lock(); tasks[key] = task; lock.unlock()
        return true
    }

    func resourceLoader(_ resourceLoader: AVAssetResourceLoader,
                        didCancel loadingRequest: AVAssetResourceLoadingRequest) {
        let key = ObjectIdentifier(loadingRequest)
        lock.lock(); let task = tasks.removeValue(forKey: key); lock.unlock()
        task?.cancel()
    }

    private func forget(_ key: ObjectIdentifier) {
        lock.lock(); tasks.removeValue(forKey: key); lock.unlock()
    }

    private static func mimeType(for name: String) -> String {
        switch (name as NSString).pathExtension.lowercased() {
        case "m4a", "aac", "mp4": return "audio/mp4"
        case "mp3": return "audio/mpeg"
        case "wav": return "audio/wav"
        case "flac": return "audio/flac"
        case "aiff", "aif": return "audio/aiff"
        case "caf": return "audio/x-caf"
        default: return "application/octet-stream"
        }
    }

    private func fulfill(_ target: (sourceID: String, path: String),
                         request: AVAssetResourceLoadingRequest) async {
        do {
            try Task.checkCancellation()
            guard let source = SMBSourceStore().load().first(where: { $0.id == target.sourceID }),
                  let endpoint = source.endpoint else {
                throw SMBSourceError.invalidSource
            }
            let joined = await SMBStorageSession.join(source.rootPath, target.path)
            let relative = try SMBPath(relative: joined)
            let credentials = try SMBSecretStore.load(for: source.id)
            let client = try SMBClient(endpoint: endpoint, credentials: credentials, transport: source.transport)
            let handle = try await client.open(relative)
            try Task.checkCancellation()
            let size = handle.size

            if let info = request.contentInformationRequest {
                info.isByteRangeAccessSupported = true
                info.contentType = Self.mimeType(for: relative.wirePath)
                info.contentLength = size
            }
            guard let dataRequest = request.dataRequest else {
                request.finishLoading()
                return
            }
            let offset = dataRequest.requestedOffset
            guard offset >= 0 else { throw SMBSourceError.invalidRange }
            let requested = Int64(dataRequest.requestedLength)
            let end = dataRequest.requestsAllDataToEndOfResource ? size : min(size, offset + requested)
            // Cap each response; AVPlayer re-requests the next Range from the same handle task.
            let stop = min(end, offset + 2 * 1024 * 1024)
            var data = Data()
            var cursor = offset
            while cursor < stop {
                try Task.checkCancellation()
                let range = try SMBByteRange.resolve(
                    requestedOffset: cursor, currentOffset: cursor,
                    length: Int(min(stop - cursor, Int64(Int.max))),
                    throughEOF: false, size: size)
                let chunk = try await handle.read(SMBByteRange.chunk(range))
                guard !chunk.isEmpty else { throw SMBSourceError.fileChanged }
                data.append(chunk)
                cursor += Int64(chunk.count)
            }
            try Task.checkCancellation()
            dataRequest.respond(with: data)
            request.finishLoading()
        } catch {
            if error is CancellationError { request.finishLoading() }
            else { request.finishLoading(with: error) }
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
    private var storageLoader: StorageResourceLoader?
    private var smbLoader: SMBResourceLoader?
    private var isOneDriveItem = false
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

    func play(_ episode: StoredEpisode, localURL: URL?) {
        reset()
        title = episode.title
        if let localURL {
            install(AVPlayerItem(url: localURL))
            return
        }
        guard let components = URLComponents(string: episode.audioUrl),
              (components.scheme?.lowercased() == "https" || components.scheme?.lowercased() == "http"), let host = components.host, !host.isEmpty,
              components.user == nil, components.password == nil, let url = components.url else {
            status = "该单集没有可播放的 HTTP(S) 音频地址。"
            return
        }
        install(AVPlayerItem(url: url))
    }

    /// Network storage playback goes through a custom scheme. The real URL and credentials
    /// are resolved only inside StorageResourceLoader, never in StoredFeed/JSON/UI state.
    func playStorage(source: StoredNetworkSource, entry: WebDavEntry, path: String) {
        reset()
        title = entry.name
        guard let assetURL = StoragePlaybackURL.make(sourceID: source.id, path: path) else {
            status = "该音频文件没有可播放的地址。"
            return
        }
        let asset = AVURLAsset(url: assetURL)
        let loader = StorageResourceLoader()
        asset.resourceLoader.setDelegate(loader, queue: DispatchQueue.global(qos: DispatchQoS.QoSClass.userInitiated))
        // AVAssetResourceLoader.delegate is weak; keep the loader alive for this item.
        storageLoader = loader
        install(AVPlayerItem(asset: asset))
    }

    /// SMB playback uses an opaque URL; the endpoint and credentials are resolved inside
    /// SMBResourceLoader only, and audio is served by bounded SMBReadHandle Range reads.
    func playSMB(source: StoredSMBSource, entry: SMBEntry, path: String) {
        reset()
        title = entry.name
        guard let assetURL = SMBPlaybackURL.make(sourceID: source.id, path: path) else {
            status = "该音频文件没有可播放的地址。"
            return
        }
        let asset = AVURLAsset(url: assetURL)
        let loader = SMBResourceLoader()
        asset.resourceLoader.setDelegate(loader, queue: DispatchQueue.global(qos: DispatchQoS.QoSClass.userInitiated))
        // AVAssetResourceLoader.delegate is weak; keep the loader alive for this item.
        smbLoader = loader
        install(AVPlayerItem(asset: asset))
    }

    /// The signed URL lives only in the active player item; no bearer headers are attached.
    func playOneDrive(url: URL, title: String) {
        reset()
        self.title = title
        isOneDriveItem = true
        install(AVPlayerItem(url: url))
    }

    func stopOneDrive() {
        if isOneDriveItem { reset() }
    }

    private func install(_ item: AVPlayerItem) {
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
        storageLoader = nil
        smbLoader = nil
        isOneDriveItem = false
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

// MARK: - OneDrive (session-only OAuth and Graph browser)

@MainActor
private final class OneDriveSession: ObservableObject {
    enum State { case disconnected, requesting, waiting(OneDriveDeviceCode), connected }
    @Published private(set) var state = State.disconnected
    @Published private(set) var folders: [OneDriveItem] = []
    @Published private(set) var entries: [OneDriveItem] = []
    @Published private(set) var next: OneDrivePage.Cursor?
    @Published private(set) var isBusy = false
    @Published private(set) var error: OneDriveError?

    private var client: OneDriveClient?
    private var request: Task<Void, Never>?
    private var loaded = false

    func connect(clientID: String, playback: EpisodePlayback) {
        guard case .disconnected = state else { return }
        do {
            let service = try OneDriveClient(clientID: clientID)
            client = service
            state = .requesting
            work(playback: playback) { [self] in
                let code = try await service.startSignIn()
                try Task.checkCancellation()
                state = .waiting(code)
                try await service.finishSignIn(code)
                try Task.checkCancellation()
                state = .connected
                try await load(using: service, folders: [])
            }
        } catch {
            self.error = .invalidClientID
        }
    }

    func disconnect(playback: EpisodePlayback) {
        cancelRequest()
        state = .disconnected
        loaded = false
        folders = []
        entries = []
        next = nil
        error = nil
        playback.stopOneDrive()
        if let client { Task { await client.disconnect() } }
        client = nil
    }

    func leaveBrowser(playback: EpisodePlayback) {
        switch state {
        case .requesting, .waiting: disconnect(playback: playback)
        case .connected: cancelRequest()
        case .disconnected: break
        }
    }

    func loadIfNeeded(playback: EpisodePlayback) {
        if !loaded { browse([], playback: playback) }
    }

    func browse(_ folders: [OneDriveItem], playback: EpisodePlayback) {
        guard case .connected = state, let client, !isBusy else { return }
        work(playback: playback) { [self] in try await load(using: client, folders: folders) }
    }

    func loadMore(playback: EpisodePlayback) {
        guard case .connected = state, let client, let next, !isBusy else { return }
        work(playback: playback) { [self] in
            let page = try await client.list(after: next)
            try Task.checkCancellation()
            var seen = Set(entries.map(\.id))
            entries += page.items.filter { ($0.isFolder || $0.isAudio) && seen.insert($0.id).inserted }
            self.next = page.next == next ? nil : page.next
        }
    }

    func play(_ item: OneDriveItem, playback: EpisodePlayback) {
        guard case .connected = state, let client, !isBusy else { return }
        let previousPlayback = playback.itemID
        work(playback: playback) {
            let url = try await client.playbackURL(for: item)
            try Task.checkCancellation()
            guard playback.itemID == previousPlayback else { return }
            playback.playOneDrive(url: url, title: item.name)
        }
    }

    private func load(using client: OneDriveClient, folders: [OneDriveItem]) async throws {
        let page = try await client.list(folderID: folders.last?.id)
        try Task.checkCancellation()
        var seen = Set<String>()
        entries = page.items.filter { ($0.isFolder || $0.isAudio) && seen.insert($0.id).inserted }
        self.folders = folders
        next = page.next
        loaded = true
    }

    private func work(playback: EpisodePlayback, operation: @escaping @MainActor () async throws -> Void) {
        isBusy = true
        error = nil
        request = Task { [self] in
            do { try await operation() }
            catch {
                guard !Task.isCancelled else { return }
                let failure = (error as? OneDriveError) ?? .network
                switch state {
                case .requesting, .waiting: disconnect(playback: playback)
                case .connected where failure == .authorizationRequired: disconnect(playback: playback)
                default: break
                }
                self.error = failure
            }
            guard !Task.isCancelled else { return }
            request = nil
            isBusy = false
        }
    }

    private func cancelRequest() {
        request?.cancel()
        request = nil
        isBusy = false
    }
}

@MainActor
private struct OneDriveBrowserView: View {
    @ObservedObject var session: OneDriveSession
    let playback: EpisodePlayback
    @AppStorage("oneDrivePublicClientID") private var savedClientID = ""
    @State private var clientID = ""

    var body: some View {
        List {
            switch session.state {
            case .disconnected:
                Section {
                    TextField("应用程序（客户端）ID", text: $clientID)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    Button("连接 OneDrive") {
                        let value = clientID.trimmingCharacters(in: .whitespacesAndNewlines)
                        if let id = UUID(uuidString: value) { savedClientID = id.uuidString.lowercased() }
                        session.connect(clientID: value, playback: playback)
                    }
                } header: {
                    Text("未连接")
                } footer: {
                    Text("填写 Microsoft Entra 的应用程序 ID。注册范围需支持所用账号（建议多租户 + 个人账户），开启公共客户端流，并配置 Microsoft Graph 委托权限 Files.Read。无需客户端密钥。")
                }
            case .requesting:
                ProgressView("正在向 Microsoft 请求设备码…")
                cancelButton
            case .waiting(let code):
                Section("等待 Microsoft 授权") {
                    Text("打开 Microsoft 官方登录页并输入下方设备码，只批准你主动发起的请求。")
                    Text(verbatim: code.userCode).font(.title2.monospaced()).textSelection(.enabled)
                    Link("打开 Microsoft 设备登录", destination: code.verificationURL)
                    Text("有效期至 \(code.expiresAt.formatted(date: .omitted, time: .standard))")
                        .font(.footnote).foregroundStyle(.secondary)
                    cancelButton
                }
            case .connected:
                connectedRows
            }
            if let error = session.error, let message = error.errorDescription {
                Text(message).foregroundStyle(.secondary)
            }
            Section {
                Text("OneDrive 仅只读访问。访问令牌和刷新令牌仅保留在本次 App 运行的内存中；退出 App 后需重新授权。音频下载地址不会保存。")
                    .font(.footnote).foregroundStyle(.secondary)
            }
        }
        .navigationTitle("OneDrive")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            clientID = savedClientID
            session.loadIfNeeded(playback: playback)
        }
        .onDisappear { session.leaveBrowser(playback: playback) }
    }

    private var cancelButton: some View {
        Button("取消授权") { session.disconnect(playback: playback) }
    }

    @ViewBuilder
    private var connectedRows: some View {
        Section("已连接 · 本次会话") {
            Button("断开 OneDrive", role: .destructive) { session.disconnect(playback: playback) }
        }
        Section(session.folders.last?.name ?? "OneDrive 根目录") {
            if !session.folders.isEmpty {
                Button {
                    session.browse(Array(session.folders.dropLast()), playback: playback)
                } label: { Label("返回上级目录", systemImage: "arrow.up") }
                .disabled(session.isBusy)
            }
            if session.isBusy {
                ProgressView("正在读取 OneDrive…")
            } else if session.entries.isEmpty, session.error == nil, session.next == nil {
                Text("此处没有目录或可播放的音频。").foregroundStyle(.secondary)
            }
            ForEach(session.entries) { item in
                Button {
                    if item.isFolder { session.browse(session.folders + [item], playback: playback) }
                    else { session.play(item, playback: playback) }
                } label: {
                    Label { Text(item.name) } icon: { Image(systemName: item.isFolder ? "folder" : "play.circle") }
                }
                .disabled(session.isBusy)
            }
            if session.next != nil {
                Button("加载更多") { session.loadMore(playback: playback) }.disabled(session.isBusy)
            }
            Button("刷新目录") { session.browse(session.folders, playback: playback) }.disabled(session.isBusy)
        }
    }
}
