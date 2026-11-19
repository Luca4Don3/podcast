#if canImport(PodcastLibSMB2)
import Foundation
import Darwin
import PodcastLibSMB2

/// One endpoint and a single tree per session. No writes, share enumeration, filesystem mounts,
/// URL parsing, or credential forwarding. The blocking C calls never run on the UI thread.
public struct SMBClient: Sendable {
    private let endpoint: SMBEndpoint
    private let credentials: SMBCredentials
    private let transport: SMBTransport

    public init(endpoint: SMBEndpoint, credentials: SMBCredentials, transport: SMBTransport) throws {
        guard transport != .tlsOnly else { throw SMBSourceError.tlsUnavailable }
        self.endpoint = endpoint
        self.credentials = credentials
        self.transport = transport
    }

    public func list(_ path: SMBPath) async throws -> [SMBEntry] {
        try Task.checkCancellation()
        let entries = try await Self.run(on: DispatchQueue.global(qos: .userInitiated)) {
            let context = try connect()
            defer { smb2_destroy_context(context) }
            try Self.checkPath(path, directory: true, in: context)
            guard let dir = smb2_opendir(context, path.wirePath) else { throw SMBSourceError.transport }
            defer { smb2_closedir(context, dir) }
            var entries: [SMBEntry] = []
            var seen = Set<String>()
            while let item = smb2_readdir(context, dir) {
                let entry = item.pointee
                guard let rawName = entry.name, let name = String(validatingUTF8: rawName),
                      SMBPath.validComponent(name), Self.isPlain(entry.st),
                      entry.st.smb2_type == SMB2_TYPE_DIRECTORY || entry.st.smb2_type == SMB2_TYPE_FILE else { continue }
                let value = SMBEntry(name: name, isDirectory: entry.st.smb2_type == SMB2_TYPE_DIRECTORY)
                if (value.isDirectory || value.audioExtension != nil), seen.insert(name).inserted {
                    entries.append(value)
                }
            }
            return entries.sorted { a, b in
                if a.isDirectory != b.isDirectory { return a.isDirectory }
                return a.name.localizedStandardCompare(b.name) == .orderedAscending
            }
        }
        try Task.checkCancellation()
        return entries
    }

    public func open(_ path: SMBPath) async throws -> SMBReadHandle {
        try Task.checkCancellation()
        guard let name = path.components.last,
              SMBEntry(name: name, isDirectory: false).audioExtension != nil else { throw SMBSourceError.invalidPath }
        let queue = DispatchQueue(label: "podcast.smb.pread", qos: .userInitiated)
        let handle = try await Self.run(on: queue) {
            let context = try connect()
            do {
                try Self.checkPath(path, directory: false, in: context)
                guard let file = smb2_open(context, path.wirePath, O_RDONLY) else { throw SMBSourceError.transport }
                var attributes = smb2_stat_64()
                guard smb2_fstat(context, file, &attributes) == 0,
                      Self.isPlain(attributes), attributes.smb2_type == SMB2_TYPE_FILE,
                      let size = Int64(exactly: attributes.smb2_size), size > 0 else { throw SMBSourceError.invalidPath }
                return SMBReadHandle(context: context, file: file, size: size, queue: queue)
            } catch {
                smb2_destroy_context(context)
                throw error
            }
        }
        try Task.checkCancellation()
        return handle
    }

    private func connect() throws -> OpaquePointer {
        guard let context = smb2_init_context() else { throw SMBSourceError.transport }
        smb2_set_timeout(context, 10)
        // ANY offers only SMB 2.02/2.10 and 3.00/3.02/3.11. Do not enable QUIC or RDMA.
        smb2_set_version(context, transport == .encrypted ? SMB2_VERSION_ANY3 : SMB2_VERSION_ANY)
        smb2_set_security_mode(context, UInt16(SMB2_NEGOTIATE_SIGNING_ENABLED | SMB2_NEGOTIATE_SIGNING_REQUIRED))
        smb2_set_sign(context, 1)
        if transport == .encrypted { smb2_set_seal(context, 1) }
        smb2_set_authentication(context, Int32(SMB2_SEC_NTLMSSP.rawValue))
        smb2_set_user(context, credentials.user)
        smb2_set_domain(context, credentials.domain)
        smb2_set_password(context, credentials.password)
        guard smb2_connect_share(context, endpoint.authority, endpoint.share, credentials.user) == 0 else {
            smb2_destroy_context(context)
            throw SMBSourceError.transport
        }
        let dialect = smb2_get_dialect(context)
        let smb3 = [0x0300, 0x0302, 0x0311].contains(Int(dialect))
        guard smb3 || (transport == .signed && [0x0202, 0x0210].contains(Int(dialect))) else {
            smb2_destroy_context(context)
            throw SMBSourceError.transport
        }
        return context
    }

    private static func isPlain(_ attributes: smb2_stat_64) -> Bool {
        attributes.smb2_attributes & UInt32(SMB2_FILE_ATTRIBUTE_REPARSE_POINT) == 0
    }

    // Treat the selected tree as the server-side security boundary; never accept a user/server path
    // that tries to change hosts/shares. Ignore reparse points and check each ancestor before opening.
    private static func checkPath(_ path: SMBPath, directory: Bool, in context: OpaquePointer) throws {
        var prefix = SMBPath.root
        for (index, part) in path.components.enumerated() {
            prefix = try prefix.appending(part)
            var attributes = smb2_stat_64()
            let expected = directory || index < path.components.count - 1 ? SMB2_TYPE_DIRECTORY : SMB2_TYPE_FILE
            guard smb2_stat(context, prefix.wirePath, &attributes) == 0,
                  isPlain(attributes), attributes.smb2_type == expected else { throw SMBSourceError.invalidPath }
        }
    }

    fileprivate static func run<T: Sendable>(on queue: DispatchQueue,
                                              _ work: @escaping @Sendable () throws -> T) async throws -> T {
        try await withCheckedThrowingContinuation { continuation in
            queue.async { continuation.resume(with: Result(catching: work)) }
        }
    }
}

/// Immutable file handle confined to a serial queue. Reads are offset-based and memory-bounded.
public final class SMBReadHandle: @unchecked Sendable {
    public let size: Int64
    private let queue: DispatchQueue
    private let context: OpaquePointer
    private let file: OpaquePointer

    fileprivate init(context: OpaquePointer, file: OpaquePointer, size: Int64, queue: DispatchQueue) {
        self.context = context
        self.file = file
        self.size = size
        self.queue = queue
    }

    deinit { smb2_destroy_context(context) }

    public func read(_ range: Range<Int64>) async throws -> Data {
        guard range.lowerBound >= 0, range.upperBound <= size,
              range.upperBound - range.lowerBound <= SMBByteRange.maxChunk else { throw SMBSourceError.invalidRange }
        try Task.checkCancellation()
        let data = try await SMBClient.run(on: queue) { [self] in
            let preferred = Int(smb2_get_max_read_size(context))
            guard preferred > 0 else { throw SMBSourceError.transport }
            let count = min(preferred, Int(range.upperBound - range.lowerBound))
            guard count > 0 else { return Data() }
            var data = Data(count: count)
            let read = data.withUnsafeMutableBytes { bytes in
                smb2_pread(context, file, bytes.bindMemory(to: UInt8.self).baseAddress,
                           UInt32(count), UInt64(range.lowerBound))
            }
            guard read >= 0, read <= count else { throw SMBSourceError.transport }
            guard read > 0 else { throw SMBSourceError.fileChanged }
            data.count = Int(read)
            return data
        }
        try Task.checkCancellation()
        return data
    }
}
#endif
