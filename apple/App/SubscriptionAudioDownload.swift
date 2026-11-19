import Darwin
import Foundation
import PodcastKit

/// One foreground transfer writes only to the app-private .part path.
final class SubscriptionAudioDownload: FeedSessionDelegate, URLSessionDataDelegate, @unchecked Sendable {
    private let originalURL: URL
    private let file: FileHandle
    private var completion: CheckedContinuation<Int64, Error>?
    private var expected: Int64?
    private var received: Int64 = 0
    private var prefix = Data()
    private var failure: Error?

    private init(url: URL, file: FileHandle, credentials: SourceCredentials, transport: SourceTransport) {
        originalURL = url
        self.file = file
        super.init(transport: transport, credentials: credentials, originalURL: url, sameOriginOnly: true)
    }

    static func save(_ item: FeedAudioCache.Item, in cache: FeedAudioCache, feedURL: URL,
                     credentials: SourceCredentials, transport: SourceTransport) async throws {
        try Task.checkCancellation()
        guard transport.allows(item.url) else { throw FeedAudioCache.Failure.unsupported }
        let sameOrigin = FeedCacheMedia.sameOrigin(feedURL, item.url)
        // Public CDN enclosures are anonymous: no source credentials, custom anchors or client identity.
        let credentials = sameOrigin ? credentials : SourceCredentials()
        let transport = sameOrigin ? transport : SourceTransport(requireHttps: transport.requireHttps)
        let part = cache.partialURL(for: item)
        if FileManager.default.fileExists(atPath: part.path) { try FileManager.default.removeItem(at: part) }
        let fd = open(part.path, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW, 0o600)
        guard fd >= 0 else { throw FeedAudioCache.Failure.storage }
        defer { try? FileManager.default.removeItem(at: part) }
        let delegate = SubscriptionAudioDownload(url: item.url,
            file: FileHandle(fileDescriptor: fd, closeOnDealloc: true), credentials: credentials, transport: transport)
        let configuration = FeedSessionDelegate.ephemeralConfiguration()
        configuration.timeoutIntervalForResource = 10 * 60
        let session = URLSession(configuration: configuration, delegate: delegate, delegateQueue: nil)
        defer { session.finishTasksAndInvalidate() }
        var request = URLRequest(url: item.url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 30)
        request.httpShouldHandleCookies = false
        request.setValue("audio/*, application/octet-stream;q=0.9", forHTTPHeaderField: "Accept")
        request.setValue("identity", forHTTPHeaderField: "Accept-Encoding")
        for (field, value) in credentials.httpHeaders { request.setValue(value, forHTTPHeaderField: field) }
        let cancellation = DownloadCancellation()
        let size = try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                delegate.completion = continuation
                cancellation.start(session.dataTask(with: request))
            }
        } onCancel: {
            cancellation.cancel()
        }
        try Task.checkCancellation()
        try cache.commit(item, expectedSize: size)
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive response: URLResponse,
                    completionHandler: @escaping (URLSession.ResponseDisposition) -> Void) {
        guard let http = response as? HTTPURLResponse,
              let size = FeedCacheMedia.expectedSize(http, for: originalURL) else {
            failure = FeedAudioCache.Failure.unsupported
            completionHandler(.cancel)
            return
        }
        expected = size
        completionHandler(.allow)
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        guard failure == nil, let expected else { return }
        received += Int64(data.count)
        if received > FeedCacheMedia.maximumBytes || (expected >= 0 && received > expected) {
            failure = FeedAudioCache.Failure.incomplete
            dataTask.cancel()
            return
        }
        if prefix.count < 16 {
            prefix.append(contentsOf: data.prefix(16 - prefix.count))
            if prefix.count == 16, !FeedCacheMedia.hasAudioSignature(prefix) {
                failure = FeedAudioCache.Failure.unsupported
                dataTask.cancel()
                return
            }
        }
        do { try file.write(contentsOf: data) }
        catch {
            failure = FeedAudioCache.Failure.storage
            dataTask.cancel()
        }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        let result = Result<Int64, Error> {
            defer { try? file.close() }
            if let failure { throw failure }
            if let error { throw error }
            guard let expected, received > 0, expected == -1 || received == expected,
                  FeedCacheMedia.hasAudioSignature(prefix) else { throw FeedAudioCache.Failure.incomplete }
            try file.synchronize()
            return expected
        }
        completion?.resume(with: result)
        completion = nil
    }
}

/// Cancellation may happen before the continuation registers a URLSession task.
private final class DownloadCancellation: @unchecked Sendable {
    private let lock = NSLock()
    private var task: URLSessionDataTask?
    private var cancelled = false

    func start(_ task: URLSessionDataTask) {
        lock.lock()
        defer { lock.unlock() }
        self.task = task
        task.resume()
        if cancelled { task.cancel() }
    }

    func cancel() {
        lock.lock()
        defer { lock.unlock() }
        cancelled = true
        task?.cancel()
    }
}
