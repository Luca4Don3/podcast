import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

public struct PodcastSummary: Identifiable, Sendable, Decodable {
    public let id: String
    public let title: String
    public let author: String

    private enum CodingKeys: String, CodingKey { case id, title, author }

    public init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        id = try values.decode(String.self, forKey: .id)
        title = try values.decode(String.self, forKey: .title)
        author = try values.decodeIfPresent(String.self, forKey: .author) ?? ""
    }
}

public struct PodcastEpisode: Identifiable, Sendable, Decodable {
    public let id: String
    public let title: String
    public let audioUrl: String

    private enum CodingKeys: String, CodingKey { case id, title, audioUrl }

    public init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        id = try values.decode(String.self, forKey: .id)
        title = try values.decode(String.self, forKey: .title)
        audioUrl = try values.decodeIfPresent(String.self, forKey: .audioUrl) ?? ""
    }
}

public enum PodcastAPIError: LocalizedError, Equatable, Sendable {
    case invalidServerURL
    case invalidPodcastID
    case invalidAccessToken
    case requestFailed
    case invalidResponse
    case httpStatus(Int)

    public var errorDescription: String? {
        switch self {
        case .invalidServerURL: return "请输入有效的 HTTPS 服务器地址，不含账号、查询参数或片段。"
        case .invalidPodcastID: return "播客 ID 无效。"
        case .invalidAccessToken: return "访问令牌无效，请重新登录。"
        case .requestFailed: return "无法完成服务器请求。"
        case .invalidResponse: return "服务器响应格式不正确。"
        case .httpStatus(401): return "身份验证失败，请检查登录信息或重新登录。"
        case .httpStatus(300..<400): return "服务器返回重定向，请直接配置正确的 HTTPS 地址。"
        case .httpStatus(let status): return "服务器请求失败（HTTP \(status)）。"
        }
    }
}

public struct PodcastAPI: Sendable {
    private let baseURL: URL?
    private let transport: Transport

    /// An injected session supplies only trusted URLProtocol classes (for test doubles).
    /// The API owns its session and never adopts the caller's delegate or credential state.
    public init(baseURL: URL, session: URLSession? = nil) {
        self.baseURL = baseURL.baseURL == nil ? try? Self.serverURL(baseURL.absoluteString) : nil
        self.transport = Transport(protocolClasses: session?.configuration.protocolClasses)
    }

    /// Accepts an explicit HTTPS origin, optional reverse-proxy prefix, or existing API root.
    /// The normalized URL always ends in /api/v1/.
    public static func serverURL(_ input: String) throws -> URL {
        let value = input.trimmingCharacters(in: .whitespacesAndNewlines)
        let forbidden = CharacterSet.whitespacesAndNewlines.union(.controlCharacters)
        guard value.prefix(8).lowercased() == "https://",
              !value.contains("\\"),
              value.rangeOfCharacter(from: forbidden) == nil,
              var components = URLComponents(string: value),
              let host = components.percentEncodedHost, !host.isEmpty, !host.contains("%"),
              components.percentEncodedUser == nil, components.percentEncodedPassword == nil,
              components.percentEncodedQuery == nil, components.percentEncodedFragment == nil else {
            throw PodcastAPIError.invalidServerURL
        }
        let authority = value.dropFirst(8).prefix { !"/?#".contains($0) }
        guard !authority.hasSuffix(":"), components.port.map({ (1...65535).contains($0) }) ?? true else {
            throw PodcastAPIError.invalidServerURL
        }

        let segments = components.percentEncodedPath.split(separator: "/")
        for segment in segments {
            guard let decoded = segment.removingPercentEncoding,
                  decoded != ".", decoded != "..", !decoded.contains("/"), !decoded.contains("\\"),
                  decoded.rangeOfCharacter(from: .controlCharacters) == nil else {
                throw PodcastAPIError.invalidServerURL
            }
        }
        var path = segments.isEmpty ? "" : "/" + segments.joined(separator: "/")
        if path != "/api/v1" && !path.hasSuffix("/api/v1") {
            path += "/api/v1"
        }
        components.scheme = "https"
        components.percentEncodedHost = host.lowercased()
        components.percentEncodedPath = path + "/"
        guard let url = components.url else { throw PodcastAPIError.invalidServerURL }
        return url
    }

    public func login(email: String, password: String) async throws -> String {
        var request = try makeRequest("auth/login", method: "POST")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        do {
            request.httpBody = try JSONEncoder().encode(LoginRequest(email: email, password: password))
        } catch {
            throw PodcastAPIError.requestFailed
        }
        let response: LoginResponse = try await send(request)
        guard Self.isValidToken(response.token.accessToken) else { throw PodcastAPIError.invalidResponse }
        return response.token.accessToken
    }

    public func podcasts(token: String) async throws -> [PodcastSummary] {
        let request = try makeRequest("podcasts", queryItems: [URLQueryItem(name: "scope", value: "subscribed")], token: token)
        let response: ItemList<PodcastSummary> = try await send(request)
        return response.items
    }

    /// Returns the server's default page of episodes.
    public func episodes(podcastID: String, token: String) async throws -> [PodcastEpisode] {
        let unreserved = CharacterSet(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~")
        guard !podcastID.isEmpty, podcastID != ".", podcastID != "..",
              let segment = podcastID.addingPercentEncoding(withAllowedCharacters: unreserved) else {
            throw PodcastAPIError.invalidPodcastID
        }
        let request = try makeRequest("podcasts/\(segment)/episodes", token: token)
        let response: ItemList<PodcastEpisode> = try await send(request)
        return response.items
    }

    private func makeRequest(_ path: String, method: String = "GET", queryItems: [URLQueryItem]? = nil, token: String? = nil) throws -> URLRequest {
        guard let baseURL, var components = URLComponents(url: baseURL, resolvingAgainstBaseURL: false) else {
            throw PodcastAPIError.invalidServerURL
        }
        components.percentEncodedPath += path
        components.queryItems = queryItems
        guard let url = components.url else { throw PodcastAPIError.invalidServerURL }
        var request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData)
        request.httpMethod = method
        request.httpShouldHandleCookies = false
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if let token {
            guard Self.isValidToken(token) else { throw PodcastAPIError.invalidAccessToken }
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }
        return request
    }

    private static func isValidToken(_ token: String) -> Bool {
        // A credential must not contain whitespace or control characters in an HTTP header.
        !token.isEmpty && token.utf8.allSatisfy { (33...126).contains($0) }
    }

    private func send<T: Decodable>(_ request: URLRequest) async throws -> T {
        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await transport.session.data(for: request)
        } catch {
            try Task.checkCancellation()
            // Foundation errors can include request URLs and server-controlled values.
            throw PodcastAPIError.requestFailed
        }
        guard let http = response as? HTTPURLResponse else { throw PodcastAPIError.invalidResponse }
        guard (200..<300).contains(http.statusCode) else { throw PodcastAPIError.httpStatus(http.statusCode) }
        do {
            return try JSONDecoder().decode(T.self, from: data)
        } catch {
            throw PodcastAPIError.invalidResponse
        }
    }

    private struct LoginRequest: Encodable {
        let email: String
        let password: String
    }

    private struct LoginResponse: Decodable {
        struct Token: Decodable { let accessToken: String }
        let token: Token
    }

    private struct ItemList<Item: Decodable>: Decodable {
        let items: [Item]
    }

    private final class Transport: Sendable {
        let session: URLSession

        init(protocolClasses: [AnyClass]?) {
            let configuration = URLSessionConfiguration.ephemeral
            configuration.urlCache = nil
            configuration.httpCookieStorage = nil
            configuration.httpShouldSetCookies = false
            configuration.urlCredentialStorage = nil
            if let protocolClasses { configuration.protocolClasses = protocolClasses }
            session = URLSession(configuration: configuration, delegate: NoRedirects(), delegateQueue: nil)
        }

        deinit { session.finishTasksAndInvalidate() }
    }

    private final class NoRedirects: NSObject, URLSessionTaskDelegate, Sendable {
        func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                        newRequest request: URLRequest, completionHandler: @escaping @Sendable (URLRequest?) -> Void) {
            completionHandler(nil)
        }
        // Do not override authentication challenges: use Foundation's normal TLS validation.
    }
}
