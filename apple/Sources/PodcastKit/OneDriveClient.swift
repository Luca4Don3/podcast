import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

public enum OneDriveError: LocalizedError, Equatable, Sendable {
    case invalidClientID, authorizationDenied, deviceCodeExpired, authorizationRequired
    case serviceRejectedAuthorization, accessDenied, notFound, throttled, network, invalidResponse, notAudio

    public var errorDescription: String? {
        switch self {
        case .invalidClientID: return "请输入 Microsoft Entra 应用注册中的应用程序（客户端）ID。"
        case .authorizationDenied: return "未批准 OneDrive 授权。"
        case .deviceCodeExpired: return "设备码已过期，请重新连接 OneDrive。"
        case .authorizationRequired: return "OneDrive 授权已失效，请重新连接。"
        case .serviceRejectedAuthorization: return "Microsoft 未接受授权请求。请检查应用 ID、公共客户端设置和组织登录策略。"
        case .accessDenied: return "没有读取此 OneDrive 内容的权限。"
        case .notFound: return "OneDrive 未找到此网盘、文件或目录。"
        case .throttled: return "OneDrive 请求过于频繁，请稍后重试。"
        case .network: return "无法连接 OneDrive，请检查网络后重试。"
        case .invalidResponse: return "OneDrive 返回了无法处理的响应。"
        case .notAudio: return "此文件不是可尝试播放的音频。"
        }
    }
}

public struct OneDriveDeviceCode: Sendable {
    public let userCode: String
    public let verificationURL: URL
    public let expiresAt: Date
    fileprivate let id: UUID
}

public struct OneDriveItem: Identifiable, Equatable, Sendable {
    public let id: String
    public let name: String
    public let isFolder: Bool
    public let isAudio: Bool
}

public struct OneDrivePage: Sendable {
    public struct Cursor: Equatable, Sendable {
        fileprivate let url: URL
        fileprivate let sessionID: UUID
    }

    public let items: [OneDriveItem]
    public let next: Cursor?
}

/// Public-client device authorization and read-only Graph access. No credentials or resolved
/// media URLs are persisted. Create one instance per in-memory sign-in session.
public actor OneDriveClient {
    private static let oauth = URL(string: "https://login.microsoftonline.com/common/oauth2/v2.0/")!
    private static let scope = "https://graph.microsoft.com/Files.Read offline_access"
    private static let graph = "https://graph.microsoft.com/v1.0/me/drive/"
    private static let unreserved = CharacterSet(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~")
    private static let audioExtensions: Set<String> = ["mp3", "m4a", "m4b", "aac", "wav", "flac", "aif", "aiff", "caf", "alac", "opus", "ogg"]

    typealias Send = @Sendable (URLRequest) async throws -> (Data, HTTPURLResponse)
    typealias Sleep = @Sendable (TimeInterval) async throws -> Void
    private let clientID: String
    private let send: Send
    private let now: @Sendable () -> Date
    private let sleep: Sleep
    private var sessionID = UUID()
    private var pending: Pending?
    private var polling = false
    private var token: Token?
    private var renewal: (id: UUID, task: Task<Token, Error>)?

    public init(clientID: String) throws {
        guard let id = UUID(uuidString: clientID.trimmingCharacters(in: .whitespacesAndNewlines)) else {
            throw OneDriveError.invalidClientID
        }
        self.clientID = id.uuidString.lowercased()
        let transport = OneDriveTransport()
        self.send = { try await transport.send($0) }
        self.now = { Date() }
        self.sleep = { try await Task.sleep(nanoseconds: UInt64($0 * 1_000_000_000)) }
    }

    init(clientID: String, send: @escaping Send, now: @escaping @Sendable () -> Date, sleep: @escaping Sleep) {
        self.clientID = clientID
        self.send = send
        self.now = now
        self.sleep = sleep
    }

    /// Invalidates authorization and any in-flight responses for this session.
    public func disconnect() {
        sessionID = UUID()
        polling = false
        pending = nil
        token = nil
        renewal?.task.cancel()
        renewal = nil
    }

    public func startSignIn() async throws -> OneDriveDeviceCode {
        try Task.checkCancellation()
        disconnect()
        let id = sessionID
        let (data, response) = try await post("devicecode", fields: [("scope", Self.scope)])
        try check(id)
        guard response.statusCode == 200 else { throw oauthFailure(response.statusCode) }
        let grant: DeviceGrant = try decode(data)
        let interval = grant.interval ?? 5
        guard !grant.device_code.isEmpty, !grant.user_code.isEmpty,
              (1...86_400).contains(grant.expires_in), (1...86_400).contains(interval),
              let url = Self.httpsURL(grant.verification_uri),
              ["microsoft.com", "www.microsoft.com", "login.microsoftonline.com"].contains(url.host?.lowercased() ?? "") else {
            throw OneDriveError.invalidResponse
        }
        let expiresAt = now().addingTimeInterval(TimeInterval(grant.expires_in))
        pending = Pending(deviceCode: grant.device_code, interval: TimeInterval(interval), expiresAt: expiresAt)
        return OneDriveDeviceCode(userCode: grant.user_code, verificationURL: url, expiresAt: expiresAt, id: id)
    }

    public func finishSignIn(_ code: OneDriveDeviceCode) async throws {
        try check(code.id)
        guard let pending, !polling else { throw OneDriveError.authorizationRequired }
        polling = true
        defer {
            if code.id == sessionID {
                self.pending = nil
                polling = false
            }
        }
        var interval = pending.interval
        while true {
            let remaining = pending.expiresAt.timeIntervalSince(now())
            guard remaining > 0 else { throw OneDriveError.deviceCodeExpired }
            try await sleep(min(interval, remaining))
            try check(code.id)
            guard now() < pending.expiresAt else { throw OneDriveError.deviceCodeExpired }
            let result: (Data, HTTPURLResponse)
            do {
                result = try await post("token", fields: [
                    ("grant_type", "urn:ietf:params:oauth:grant-type:device_code"), ("device_code", pending.deviceCode),
                ], retryTimeout: true)
            } catch let error as URLError where error.code == .timedOut {
                try check(code.id)
                interval *= 2
                continue
            } catch is URLError {
                throw OneDriveError.network
            }
            try check(code.id)
            let (data, response) = result
            if response.statusCode == 200 {
                token = try parseToken(data)
                return
            }
            switch (try? JSONDecoder().decode(OAuthError.self, from: data))?.error {
            case "authorization_pending": continue
            case "slow_down": interval += 5
            case "authorization_declined", "access_denied": throw OneDriveError.authorizationDenied
            case "expired_token", "code_expired": throw OneDriveError.deviceCodeExpired
            default: throw oauthFailure(response.statusCode)
            }
        }
    }

    public func list(folderID: String? = nil, after cursor: OneDrivePage.Cursor? = nil) async throws -> OneDrivePage {
        let id = sessionID
        let url: URL
        if let cursor {
            try check(cursor.sessionID)
            url = cursor.url
        } else {
            let path = try folderID.map { "items/" + (try Self.segment($0)) } ?? "root"
            url = URL(string: Self.graph + path + "/children?$select=id,name,folder,file")!
        }
        let (data, response) = try await get(url)
        try check(id)
        guard response.statusCode == 200 else { throw graphFailure(response.statusCode) }
        let page: DrivePage = try decode(data)
        let next = try page.nextLink.map { link -> OneDrivePage.Cursor in
            guard let url = Self.httpsURL(link), url.host?.lowercased() == "graph.microsoft.com",
                  url.path.hasPrefix("/v1.0/"), url.path.hasSuffix("/children") else {
                throw OneDriveError.invalidResponse
            }
            return OneDrivePage.Cursor(url: url, sessionID: id)
        }
        let items = page.value.compactMap { entry -> OneDriveItem? in
            guard !entry.id.isEmpty, !entry.name.isEmpty else { return nil }
            let isFolder = entry.folder != nil
            let ext = (entry.name as NSString).pathExtension.lowercased()
            let isAudio = !isFolder && (entry.file.map {
                $0.mimeType?.lowercased().hasPrefix("audio/") == true || Self.audioExtensions.contains(ext)
            } ?? false)
            return OneDriveItem(id: entry.id, name: entry.name, isFolder: isFolder, isAudio: isAudio)
        }
        return OneDrivePage(items: items, next: next)
    }

    /// Resolve on user playback only; don't store this short-lived, preauthenticated URL.
    /// The player must not attach authorization headers when using it.
    public func playbackURL(for item: OneDriveItem) async throws -> URL {
        guard item.isAudio, !item.isFolder else { throw OneDriveError.notAudio }
        let id = sessionID
        let url = URL(string: Self.graph + "items/" + (try Self.segment(item.id)) + "/content")!
        let (_, response) = try await get(url)
        try check(id)
        guard response.statusCode == 302 else { throw graphFailure(response.statusCode) }
        guard let location = response.value(forHTTPHeaderField: "Location"), let url = Self.httpsURL(location) else {
            throw OneDriveError.invalidResponse
        }
        return url
    }

    private func get(_ url: URL) async throws -> (Data, HTTPURLResponse) {
        let id = sessionID
        try check(id)
        let first = try await accessToken()
        let result = try await graphGET(url, token: first)
        try check(id)
        guard result.1.statusCode == 401 else { return result }
        let second = try await accessToken(rejected: first)
        let retry = try await graphGET(url, token: second)
        try check(id)
        if retry.1.statusCode == 401 {
            if token?.value == second { token = nil }
            throw OneDriveError.authorizationRequired
        }
        return retry
    }

    private func accessToken(rejected: String? = nil) async throws -> String {
        guard let token else { throw OneDriveError.authorizationRequired }
        if token.value != rejected && token.expiresAt.timeIntervalSince(now()) > 30 { return token.value }
        if rejected == nil, token.refresh == nil, token.expiresAt > now() { return token.value }
        guard let refresh = token.refresh else {
            self.token = nil
            throw OneDriveError.authorizationRequired
        }
        let id = sessionID
        if renewal == nil {
            renewal = (UUID(), Task { try await self.refreshToken(refresh) })
        }
        guard let renewal else { throw OneDriveError.authorizationRequired }
        let result = await renewal.task.result
        guard id == sessionID else { throw CancellationError() }
        if self.renewal?.id == renewal.id {
            self.renewal = nil
            switch result {
            case .success(let fresh): self.token = fresh
            case .failure(let error):
                if error as? OneDriveError == .authorizationRequired { self.token = nil }
            }
        }
        try Task.checkCancellation()
        let fresh = try result.get()
        guard self.token != nil else { throw OneDriveError.authorizationRequired }
        return fresh.value
    }

    private func refreshToken(_ previous: String) async throws -> Token {
        let (data, response) = try await post("token", fields: [
            ("grant_type", "refresh_token"), ("refresh_token", previous), ("scope", Self.scope),
        ])
        guard response.statusCode == 200 else {
            switch (try? JSONDecoder().decode(OAuthError.self, from: data))?.error {
            case "invalid_grant", "interaction_required", "login_required", "consent_required":
                throw OneDriveError.authorizationRequired
            default: throw oauthFailure(response.statusCode)
            }
        }
        return try parseToken(data, previousRefresh: previous)
    }

    private func parseToken(_ data: Data, previousRefresh: String? = nil) throws -> Token {
        let grant: TokenGrant = try decode(data)
        guard grant.token_type.lowercased() == "bearer", grant.expires_in > 0, !grant.access_token.isEmpty,
              grant.access_token.utf8.allSatisfy({ (33...126).contains($0) }) else { throw OneDriveError.invalidResponse }
        let refresh = grant.refresh_token.flatMap { $0.isEmpty ? nil : $0 } ?? previousRefresh
        return Token(value: grant.access_token, refresh: refresh, expiresAt: now().addingTimeInterval(TimeInterval(grant.expires_in)))
    }

    private func post(_ endpoint: String, fields: [(String, String)], retryTimeout: Bool = false) async throws -> (Data, HTTPURLResponse) {
        let body = ([ ("client_id", clientID) ] + fields).map { key, value in
            key + "=" + value.addingPercentEncoding(withAllowedCharacters: Self.unreserved)!
        }.joined(separator: "&")
        var request = URLRequest(url: Self.oauth.appendingPathComponent(endpoint))
        request.httpMethod = "POST"
        request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        request.httpBody = Data(body.utf8)
        return try await perform(request, retryTimeout: retryTimeout)
    }

    private func graphGET(_ url: URL, token: String) async throws -> (Data, HTTPURLResponse) {
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.setValue("Bearer " + token, forHTTPHeaderField: "Authorization")
        return try await perform(request)
    }

    private func perform(_ request: URLRequest, retryTimeout: Bool = false) async throws -> (Data, HTTPURLResponse) {
        try Task.checkCancellation()
        do { return try await send(request) }
        catch is CancellationError { throw CancellationError() }
        catch let error as URLError where error.code == .cancelled { throw CancellationError() }
        catch let error as URLError where retryTimeout && error.code == .timedOut { throw error }
        catch { throw OneDriveError.network }
    }

    private func check(_ id: UUID) throws {
        try Task.checkCancellation()
        guard id == sessionID else { throw CancellationError() }
    }

    private func decode<T: Decodable>(_ data: Data) throws -> T {
        do { return try JSONDecoder().decode(T.self, from: data) }
        catch { throw OneDriveError.invalidResponse }
    }

    private func oauthFailure(_ status: Int) -> OneDriveError {
        if status == 429 { return .throttled }
        return status >= 500 ? .network : .serviceRejectedAuthorization
    }

    private func graphFailure(_ status: Int) -> OneDriveError {
        switch status {
        case 403: return .accessDenied
        case 404: return .notFound
        case 429: return .throttled
        case 500...599: return .network
        default: return .invalidResponse
        }
    }

    private static func segment(_ id: String) throws -> String {
        guard !id.isEmpty, id != ".", id != "..", let value = id.addingPercentEncoding(withAllowedCharacters: unreserved) else {
            throw OneDriveError.invalidResponse
        }
        return value
    }

    private static func httpsURL(_ string: String) -> URL? {
        guard let url = URLComponents(string: string), url.scheme?.lowercased() == "https",
              let host = url.host, !host.isEmpty, url.port == nil || url.port == 443,
              url.user == nil, url.password == nil, url.fragment == nil else { return nil }
        return url.url
    }

    private struct Pending { let deviceCode: String; let interval: TimeInterval; let expiresAt: Date }
    private struct Token { let value: String; let refresh: String?; let expiresAt: Date }
    private struct OAuthError: Decodable { let error: String }
    private struct DeviceGrant: Decodable {
        let device_code: String
        let user_code: String
        let verification_uri: String
        let expires_in: Int
        let interval: Int?
    }
    private struct TokenGrant: Decodable {
        let token_type: String
        let access_token: String
        let refresh_token: String?
        let expires_in: Int
    }
    private struct DrivePage: Decodable {
        let value: [Entry]
        let nextLink: String?
        enum CodingKeys: String, CodingKey { case value; case nextLink = "@odata.nextLink" }
    }
    private struct Entry: Decodable {
        struct Folder: Decodable {}
        struct File: Decodable { let mimeType: String? }
        let id: String
        let name: String
        let folder: Folder?
        let file: File?
    }
}

/// Reject redirects before URLSession can forward a Graph bearer or device/refresh token.
private final class OneDriveTransport: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    func send(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.urlCache = nil
        configuration.httpCookieStorage = nil
        configuration.urlCredentialStorage = nil
        configuration.httpShouldSetCookies = false
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        configuration.timeoutIntervalForRequest = 30
        let session = URLSession(configuration: configuration, delegate: self, delegateQueue: nil)
        defer { session.finishTasksAndInvalidate() }
        let (data, response) = try await session.data(for: request)
        guard let response = response as? HTTPURLResponse else { throw OneDriveError.invalidResponse }
        return (data, response)
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping @Sendable (URLRequest?) -> Void) {
        completionHandler(nil)
    }
}
