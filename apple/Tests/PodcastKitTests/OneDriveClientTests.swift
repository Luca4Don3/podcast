import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
import XCTest
@testable import PodcastKit

final class OneDriveClientTests: XCTestCase {
    private let clientID = "00000000-0000-4000-8000-000000000001"
    private let device = Reply(json: #"{"user_code":"MOCK-CODE","device_code":"mock+device&value","verification_uri":"https://www.microsoft.com/devicelogin","expires_in":15,"interval":2}"#)
    private let token = Reply(json: #"{"token_type":"Bearer","access_token":"mock-access","refresh_token":"mock+refresh&value","expires_in":90}"#)
    private let freshToken = Reply(json: #"{"token_type":"Bearer","access_token":"mock-fresh","refresh_token":"mock-rotated","expires_in":90}"#)
    private let emptyPage = Reply(json: #"{"value":[]}"#)

    func testPublicClientConfiguration() throws {
        XCTAssertThrowsError(try OneDriveClient(clientID: "not-an-app-id")) {
            XCTAssertEqual($0 as? OneDriveError, .invalidClientID)
        }
        _ = try OneDriveClient(clientID: " " + clientID + "\n")
    }

    func testDeviceAuthorizationPendingSlowDownAndReadOnlyRequests() async throws {
        let (client, stub, clock) = make([device, .error("authorization_pending"), .error("slow_down"), token, emptyPage])
        let code = try await client.startSignIn()
        XCTAssertEqual(code.userCode, "MOCK-CODE")
        XCTAssertEqual(code.verificationURL.absoluteString, "https://www.microsoft.com/devicelogin")
        XCTAssertEqual(code.expiresAt.timeIntervalSince(clock.now()), 15)
        try await client.finishSignIn(code)
        let page = try await client.list()
        XCTAssertTrue(page.items.isEmpty)
        XCTAssertEqual(clock.sleeps, [2, 2, 7])
        let requests = await stub.requests
        XCTAssertEqual(requests.map(\.httpMethod), ["POST", "POST", "POST", "POST", "GET"])
        XCTAssertEqual(requests.first?.url?.absoluteString, "https://login.microsoftonline.com/common/oauth2/v2.0/devicecode")
        XCTAssertEqual(requests[0].value(forHTTPHeaderField: "Content-Type"), "application/x-www-form-urlencoded")
        XCTAssertEqual(form(requests[0]), ["client_id": clientID, "scope": "https://graph.microsoft.com/Files.Read offline_access"])
        for request in requests[1...3] {
            XCTAssertEqual(request.url?.absoluteString, "https://login.microsoftonline.com/common/oauth2/v2.0/token")
            XCTAssertEqual(form(request), ["client_id": clientID, "device_code": "mock+device&value",
                                           "grant_type": "urn:ietf:params:oauth:grant-type:device_code"])
            XCTAssertNil(request.value(forHTTPHeaderField: "Authorization"))
        }
        XCTAssertEqual(requests[4].url?.absoluteString, "https://graph.microsoft.com/v1.0/me/drive/root/children?$select=id,name,folder,file")
        XCTAssertEqual(requests[4].value(forHTTPHeaderField: "Authorization"), "Bearer mock-access")
        XCTAssertNil(requests[4].httpBody)
    }

    func testDeviceExpiryAndDeclineDoNotAuthorizeGraph() async throws {
        let short = Reply(json: device.json.replacingOccurrences(of: "\"expires_in\":15", with: "\"expires_in\":3"))
        let (expired, expiryStub, clock) = make([short, .error("authorization_pending")])
        let code = try await expired.startSignIn()
        do { try await expired.finishSignIn(code); XCTFail("Expected device code expiry") }
        catch { XCTAssertEqual(error as? OneDriveError, .deviceCodeExpired) }
        XCTAssertEqual(clock.sleeps, [2, 1])
        let expiryCount = await expiryStub.requests.count
        XCTAssertEqual(expiryCount, 2)

        let (declined, declineStub, _) = make([device, .error("authorization_declined")])
        let declinedCode = try await declined.startSignIn()
        do { try await declined.finishSignIn(declinedCode); XCTFail("Expected decline") }
        catch { XCTAssertEqual(error as? OneDriveError, .authorizationDenied) }
        do { _ = try await declined.list(); XCTFail("Expected authorization requirement") }
        catch { XCTAssertEqual(error as? OneDriveError, .authorizationRequired) }
        let declineCount = await declineStub.requests.count
        XCTAssertEqual(declineCount, 2)
    }

    func testCancelledPollAndDisconnectInvalidateDeviceCode() async throws {
        let (client, stub, _) = make([device], sleep: { _ in throw CancellationError() })
        let code = try await client.startSignIn()
        do { try await client.finishSignIn(code); XCTFail("Expected poll cancellation") }
        catch { XCTAssertTrue(error is CancellationError) }
        await client.disconnect()
        do { try await client.finishSignIn(code); XCTFail("Expected invalidated device code") }
        catch { XCTAssertTrue(error is CancellationError) }
        let count = await stub.requests.count
        XCTAssertEqual(count, 1)
    }

    func testFolderPagingAndPlaybackResolveOnlyOnDemand() async throws {
        let root = Reply(json: #"{"value":[{"id":"dir!1","name":"栏目","folder":{"childCount":1}},{"id":"a","name":"note.txt","file":{"mimeType":"text/plain"}},{"id":"b","name":"sample.MP3","file":{}},{"id":"c","name":"stream.bin","file":{"mimeType":"audio/mpeg"}}],"@odata.nextLink":"https://graph.microsoft.com/v1.0/me/drive/root/children?$skiptoken=mock%2Bpage"}"#)
        let media = "https://example.invalid/sample.mp3?sig=mock-signed-url"
        let (client, stub, _) = make([device, token, root, emptyPage, emptyPage,
                                       Reply(status: 302, headers: ["Location": media]),
                                       Reply(status: 302, headers: ["Location": media])])
        try await signIn(client)
        let first = try await client.list()
        XCTAssertEqual(first.items.map(\.isFolder), [true, false, false, false])
        XCTAssertEqual(first.items.map(\.isAudio), [false, false, true, true])
        let next = try XCTUnwrap(first.next)
        _ = try await client.list(after: next)
        _ = try await client.list(folderID: first.items[0].id)
        do { _ = try await client.playbackURL(for: first.items[1]); XCTFail("Expected non-audio rejection") }
        catch { XCTAssertEqual(error as? OneDriveError, .notAudio) }
        let beforePlayback = await stub.requests.count
        XCTAssertEqual(beforePlayback, 5)
        let url = try await client.playbackURL(for: first.items[2])
        XCTAssertEqual(url.absoluteString, media)
        _ = try await client.playbackURL(for: first.items[2])
        let requests = await stub.requests
        XCTAssertEqual(requests[3].url?.absoluteString, "https://graph.microsoft.com/v1.0/me/drive/root/children?$skiptoken=mock%2Bpage")
        XCTAssertTrue(requests[4].url?.absoluteString.contains("/items/dir%211/children") == true)
        XCTAssertEqual(requests.filter { $0.url?.path.hasSuffix("/items/b/content") == true }.count, 2)
        XCTAssertFalse(requests.contains { $0.url?.host == "example.invalid" })
        await client.disconnect()
        do { _ = try await client.list(after: next); XCTFail("Cursor must be invalidated by disconnect") }
        catch { XCTAssertTrue(error is CancellationError) }
    }

    func testUntrustedNextLinkAndInsecureDownloadAreRejected() async throws {
        let bad = Reply(json: #"{"value":[],"@odata.nextLink":"https://graph.microsoft.com.example.invalid/v1.0/me/drive/root/children"}"#)
        let (client, stub, _) = make([device, token, bad, Reply(status: 302, headers: ["Location": "http://example.invalid/audio"])])
        try await signIn(client)
        do { _ = try await client.list(); XCTFail("Expected rejection of a different host") }
        catch { XCTAssertEqual(error as? OneDriveError, .invalidResponse) }
        let item = OneDriveItem(id: "audio", name: "sample.mp3", isFolder: false, isAudio: true)
        do { _ = try await client.playbackURL(for: item); XCTFail("Expected rejection of insecure playback URL") }
        catch { XCTAssertEqual(error as? OneDriveError, .invalidResponse) }
        let graphRequests = await stub.requests.dropFirst(2)
        XCTAssertEqual(graphRequests.map { $0.url?.host }, ["graph.microsoft.com", "graph.microsoft.com"])
    }

    func testTokenExpiresRefreshRotatesAndGraphUnauthorizedRetriesOnce() async throws {
        let (client, stub, clock) = make([device, token, freshToken, emptyPage, Reply(status: 401),
            Reply(json: #"{"token_type":"Bearer","access_token":"mock-retry","expires_in":90}"#), emptyPage])
        try await signIn(client)
        clock.advance(91)
        _ = try await client.list()
        _ = try await client.list()
        let requests = await stub.requests
        XCTAssertEqual(requests.count, 7)
        XCTAssertEqual(form(requests[2]), ["client_id": clientID, "grant_type": "refresh_token",
                                          "refresh_token": "mock+refresh&value", "scope": "https://graph.microsoft.com/Files.Read offline_access"])
        XCTAssertEqual(form(requests[5])["refresh_token"], "mock-rotated")
        XCTAssertNil(form(requests[5])["client_secret"])
        XCTAssertEqual(requests[3].value(forHTTPHeaderField: "Authorization"), "Bearer mock-fresh")
        XCTAssertEqual(requests[4].value(forHTTPHeaderField: "Authorization"), "Bearer mock-fresh")
        XCTAssertEqual(requests[6].value(forHTTPHeaderField: "Authorization"), "Bearer mock-retry")
    }

    func testRevokedRefreshRequiresNewSignIn() async throws {
        let (client, stub, clock) = make([device, token, .error("invalid_grant")])
        try await signIn(client)
        clock.advance(91)
        for _ in 0..<2 {
            do { _ = try await client.list(); XCTFail("Expected renewed authorization") }
            catch { XCTAssertEqual(error as? OneDriveError, .authorizationRequired) }
        }
        let count = await stub.requests.count
        XCTAssertEqual(count, 3)
    }

    private func make(_ replies: [Reply], sleep: OneDriveClient.Sleep? = nil) -> (OneDriveClient, Stub, Clock) {
        let stub = Stub(replies)
        let clock = Clock()
        let client = OneDriveClient(clientID: clientID, send: { try await stub.send($0) },
                                    now: { clock.now() }, sleep: sleep ?? { clock.advance($0, recordSleep: true) })
        return (client, stub, clock)
    }

    private func signIn(_ client: OneDriveClient) async throws {
        let code = try await client.startSignIn()
        try await client.finishSignIn(code)
    }

    private func form(_ request: URLRequest) -> [String: String] {
        guard let body = request.httpBody.flatMap({ String(data: $0, encoding: .utf8) }),
              let pairs = URLComponents(string: "https://example.invalid/?" + body)?.queryItems else { return [:] }
        return Dictionary(uniqueKeysWithValues: pairs.map { ($0.name, $0.value ?? "") })
    }

    private struct Reply: Sendable {
        var status = 200
        var json = ""
        var headers: [String: String] = [:]
        static func error(_ name: String) -> Self { Reply(status: 400, json: "{\"error\":\"\(name)\"}") }
    }

    private actor Stub {
        var replies: [Reply]
        private(set) var requests: [URLRequest] = []
        init(_ replies: [Reply]) { self.replies = replies }

        func send(_ request: URLRequest) throws -> (Data, HTTPURLResponse) {
            requests.append(request)
            guard !replies.isEmpty else { throw URLError(.badServerResponse) }
            let reply = replies.removeFirst()
            let response = HTTPURLResponse(url: request.url!, statusCode: reply.status,
                                           httpVersion: "HTTP/1.1", headerFields: reply.headers)!
            return (Data(reply.json.utf8), response)
        }
    }

    private final class Clock: @unchecked Sendable {
        private let lock = NSLock()
        private var instant = Date(timeIntervalSince1970: 100)
        private var intervals: [TimeInterval] = []
        var sleeps: [TimeInterval] { lock.withLock { intervals } }
        func now() -> Date { lock.withLock { instant } }
        func advance(_ seconds: TimeInterval, recordSleep: Bool = false) {
            lock.withLock {
                instant += seconds
                if recordSleep { intervals.append(seconds) }
            }
        }
    }
}
