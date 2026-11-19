import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
import XCTest
import PodcastKit

final class APITests: XCTestCase {
    func testServerURLNormalizesHTTPSAndIsIdempotent() throws {
        let examples = [
            ("  HTTPS://PODCAST.EXAMPLE\n", "https://podcast.example/api/v1/"),
            ("https://podcast.example/", "https://podcast.example/api/v1/"),
            ("https://podcast.example:8443/prefix/", "https://podcast.example:8443/prefix/api/v1/"),
            ("https://podcast.example/prefix/api/v1", "https://podcast.example/prefix/api/v1/"),
            ("https://[2001:db8::1]:8443/", "https://[2001:db8::1]:8443/api/v1/"),
        ]
        for (input, expected) in examples {
            let url = try PodcastAPI.serverURL(input)
            XCTAssertEqual(url.absoluteString, expected)
            XCTAssertEqual(try PodcastAPI.serverURL(url.absoluteString), url)
        }
    }

    func testServerURLRejectsInsecureAndAmbiguousAddresses() {
        for input in ["", "podcast.example", "//podcast.example", "http://podcast.example",
                      "https:/podcast.example", "https:///api/v1", "https://podcast.example:",
                      "https://podcast.example:65536", "https://sample:secret@podcast.example",
                      "https://podcast.example/?", "https://podcast.example/#", "https://podcast.example/../",
                      "https://podcast.example/%2e%2e/", "https://podcast.example\\alternate.example"] {
            XCTAssertThrowsError(try PodcastAPI.serverURL(input), input) {
                XCTAssertEqual($0 as? PodcastAPIError, .invalidServerURL)
            }
        }
    }

    func testLoginPostsJSONAndDecodesNestedAccessToken() async throws {
        let email = "listener@example.invalid"
        let password = "fixture-\"password"
        let token = try await withAPI { request in
            XCTAssertEqual(request.url?.scheme, "https")
            XCTAssertEqual(request.url?.path, "/proxy/api/v1/auth/login")
            XCTAssertNil(request.url?.query)
            XCTAssertEqual(request.httpMethod, "POST")
            XCTAssertEqual(request.value(forHTTPHeaderField: "Content-Type"), "application/json")
            XCTAssertEqual(request.value(forHTTPHeaderField: "Accept"), "application/json")
            XCTAssertNil(request.value(forHTTPHeaderField: "Authorization"))
            XCTAssertNil(request.value(forHTTPHeaderField: "Cookie"))
            let body = try JSONSerialization.jsonObject(with: Self.body(of: request)) as? [String: String]
            XCTAssertEqual(body, ["email": email, "password": password])
            return .response(200, #"{"user":{"id":"listener"},"token":{"accessToken":"access.test.token","refreshToken":"unused"}}"#)
        } operation: { api in
            try await api.login(email: email, password: password)
        }
        XCTAssertEqual(token, "access.test.token")
    }

    func testPodcastsRequestsSubscribedScopeAndDecodesModels() async throws {
        let podcasts = try await withAPI { request in
            XCTAssertEqual(request.httpMethod, "GET")
            XCTAssertEqual(request.url?.path, "/proxy/api/v1/podcasts")
            XCTAssertEqual(request.url?.query, "scope=subscribed")
            XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer fixture-token")
            return .response(200, #"{"items":[{"id":"p1","title":"One","author":"Host","subscribed":true},{"id":"p2","title":"Two"}],"total":2}"#)
        } operation: { api in
            try await api.podcasts(token: "fixture-token")
        }
        XCTAssertEqual(podcasts.map(\.id), ["p1", "p2"])
        XCTAssertEqual(podcasts.map(\.title), ["One", "Two"])
        XCTAssertEqual(podcasts.map(\.author), ["Host", ""])
    }

    func testEpisodesEncodesIDAsOneSegmentAndDecodesModels() async throws {
        let episodes = try await withAPI { request in
            XCTAssertEqual(request.httpMethod, "GET")
            let url = try XCTUnwrap(request.url)
            let components = URLComponents(url: url, resolvingAgainstBaseURL: false)
            XCTAssertEqual(components?.percentEncodedPath, "/proxy/api/v1/podcasts/one%2Ftwo%3F%23%25/episodes")
            XCTAssertNil(components?.query)
            XCTAssertNil(components?.fragment)
            XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer fixture-token")
            return .response(200, #"{"items":[{"id":"e1","title":"Episode","audioUrl":"https://audio.example.invalid/show.mp3"},{"id":"e2","title":"Bonus","audioUrl":null}],"total":2,"limit":30,"offset":0}"#)
        } operation: { api in
            try await api.episodes(podcastID: "one/two?#%", token: "fixture-token")
        }
        XCTAssertEqual(episodes.map(\.id), ["e1", "e2"])
        XCTAssertEqual(episodes.map(\.title), ["Episode", "Bonus"])
        XCTAssertEqual(episodes.map(\.audioUrl), ["https://audio.example.invalid/show.mp3", ""])
    }

    func testUnsafeBaseNeverInvokesTransportForCredentials() async throws {
        for insecure in ["http://", "https://sample:secret@"] {
            try await withAPI(schemeAndAuthorityPrefix: insecure) { _ in
                XCTFail("Credentials must not reach the transport for an unsafe base URL")
                return .response(500, "")
            } operation: { api in
                await self.assertError(.invalidServerURL) { _ = try await api.login(email: "listener@example.invalid", password: "fixture-password") }
                await self.assertError(.invalidServerURL) { _ = try await api.podcasts(token: "fixture-token") }
                await self.assertError(.invalidServerURL) { _ = try await api.episodes(podcastID: "p1", token: "fixture-token") }
            }
        }
    }

    func testInvalidBearerAndDotSegmentAreRejectedBeforeSending() async throws {
        try await withAPI { _ in
            XCTFail("Invalid inputs must not reach the transport")
            return .response(500, "")
        } operation: { api in
            await self.assertError(.invalidAccessToken) { _ = try await api.podcasts(token: "a\r\nb") }
            await self.assertError(.invalidPodcastID) { _ = try await api.episodes(podcastID: "..", token: "fixture-token") }
        }
    }

    func testHTTPBodyAndTransportDetailsAreNotExposed() async throws {
        let marker = "sensitive-fixture-marker"
        try await withAPI { request in
            switch request.url?.path {
            case "/proxy/api/v1/auth/login": return .response(401, "echo \(marker)")
            case "/proxy/api/v1/podcasts": return .response(200, "malformed \(marker)")
            default: throw URLError(.secureConnectionFailed, userInfo: [NSLocalizedDescriptionKey: marker])
            }
        } operation: { api in
            await self.assertError(.httpStatus(401), excluding: marker) { _ = try await api.login(email: "listener@example.invalid", password: marker) }
            await self.assertError(.invalidResponse, excluding: marker) { _ = try await api.podcasts(token: marker) }
            await self.assertError(.requestFailed, excluding: marker) { _ = try await api.episodes(podcastID: "p1", token: marker) }
        }
    }

    func testRedirectIsRejectedForPasswordAndBearer() async throws {
        let marker = "redirect-fixture-marker"
        let location = try XCTUnwrap(URL(string: "http://other.example.invalid/elsewhere?value=\(marker)"))
        try await withAPI { request in
            guard request.url?.host != location.host else {
                XCTFail("Must not make any request to the redirect target")
                return .response(500, "")
            }
            return .redirect(location)
        } operation: { api in
            await self.assertError(.httpStatus(307), excluding: marker) { _ = try await api.login(email: "listener@example.invalid", password: marker) }
            await self.assertError(.httpStatus(307), excluding: marker) { _ = try await api.podcasts(token: marker) }
        }
    }

    private func assertError(_ expected: PodcastAPIError, excluding marker: String? = nil,
                             file: StaticString = #filePath, line: UInt = #line, operation: () async throws -> Void) async {
        do {
            try await operation()
            XCTFail("Expected an API error", file: file, line: line)
        } catch {
            XCTAssertEqual(error as? PodcastAPIError, expected, file: file, line: line)
            if let marker {
                XCTAssertFalse(error.localizedDescription.contains(marker), file: file, line: line)
                XCTAssertFalse(String(reflecting: error).contains(marker), file: file, line: line)
            }
        }
    }

    private func withAPI<Result>(schemeAndAuthorityPrefix: String = "https://", responder: @escaping StubProtocol.Responder,
                                 operation: (PodcastAPI) async throws -> Result) async throws -> Result {
        let host = UUID().uuidString.lowercased() + ".example.invalid"
        let url = try XCTUnwrap(URL(string: "\(schemeAndAuthorityPrefix)\(host)/proxy"))
        StubProtocol.routes.set(host, to: responder)
        defer { StubProtocol.routes.set(host, to: nil) }

        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [StubProtocol.self]
        // The injected session must not add implicit credentials to the API's requests.
        configuration.httpAdditionalHeaders = ["Cookie": "injected=fixture", "Authorization": "Basic fixture"]
        let session = URLSession(configuration: configuration)
        defer { session.invalidateAndCancel() }
        return try await operation(PodcastAPI(baseURL: url, session: session))
    }

    private static func body(of request: URLRequest) throws -> Data {
        if let body = request.httpBody { return body }
        let stream = try XCTUnwrap(request.httpBodyStream)
        stream.open()
        defer { stream.close() }
        var body = Data()
        var buffer = [UInt8](repeating: 0, count: 1024)
        while true {
            let count = stream.read(&buffer, maxLength: buffer.count)
            guard count >= 0 else { throw URLError(.cannotDecodeContentData) }
            if count == 0 { return body }
            body.append(contentsOf: buffer.prefix(count))
        }
    }
}

private final class StubProtocol: URLProtocol, @unchecked Sendable {
    enum Reply {
        case response(Int, String)
        case redirect(URL)
    }

    typealias Responder = @Sendable (URLRequest) throws -> Reply

    final class Routes: @unchecked Sendable {
        private let lock = NSLock()
        private var handlers: [String: Responder] = [:]

        func set(_ host: String, to responder: Responder?) {
            lock.lock()
            defer { lock.unlock() }
            handlers[host] = responder
        }

        func get(_ host: String) -> Responder? {
            lock.lock()
            defer { lock.unlock() }
            return handlers[host]
        }
    }

    static let routes = Routes()

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        do {
            let url = try XCTUnwrap(request.url)
            guard let host = url.host, let responder = Self.routes.get(host) else {
                XCTFail("Unexpected outbound request")
                throw URLError(.unsupportedURL)
            }
            switch try responder(request) {
            case .response(let code, let body):
                let response = try XCTUnwrap(HTTPURLResponse(url: url, statusCode: code, httpVersion: "HTTP/1.1", headerFields: nil))
                client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
                client?.urlProtocol(self, didLoad: Data(body.utf8))
            case .redirect(let location):
                let response = try XCTUnwrap(HTTPURLResponse(url: url, statusCode: 307, httpVersion: "HTTP/1.1", headerFields: ["Location": location.absoluteString]))
                #if canImport(FoundationNetworking)
                // Corelibs Foundation does not support URLProtocolClient's redirect callback.
                client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
                #else
                var redirected = request // Model a 307 that would preserve method, body and headers.
                redirected.url = location
                client?.urlProtocol(self, wasRedirectedTo: redirected, redirectResponse: response)
                #endif
            }
            client?.urlProtocolDidFinishLoading(self)
        } catch {
            client?.urlProtocol(self, didFailWithError: error)
        }
    }

    override func stopLoading() {}
}
