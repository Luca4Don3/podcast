import Foundation
#if canImport(Darwin)
import Darwin
#elseif canImport(Glibc)
import Glibc
#endif

public enum SMBTransport: String, Codable, CaseIterable, Sendable {
    case encrypted, signed, tlsOnly
}

public enum SMBSourceError: LocalizedError, Equatable, Sendable {
    case invalidSource, invalidPath, invalidRange, tlsUnavailable, missingCredentials
    case credentialStore, transport, fileChanged

    public var errorDescription: String? {
        switch self {
        case .invalidSource: return "请填写有效的 SMB 主机、端口、共享名称和账户。"
        case .invalidPath: return "SMB 路径或文件类型无效。"
        case .invalidRange: return "SMB 音频读取范围无效。"
        case .tlsUnavailable: return "仅 TLS 策略阻止直连：SMB 签名和 SMB3 加密都不是 TLS。"
        case .missingCredentials: return "钥匙串中没有此 SMB 来源的凭据。请重新添加。"
        case .credentialStore: return "无法访问本机钥匙串或存储 SMB 来源。"
        case .transport: return "无法读取 SMB 来源。请检查网络、本地网络权限、账户及服务器的 SMB 签名或加密设置。"
        case .fileChanged: return "SMB 文件在读取期间发生变化，请重新打开。"
        }
    }
}

public struct SMBEndpoint: Codable, Equatable, Sendable {
    public let host: String
    public let port: UInt16
    public let share: String

    public init(host: String, port: Int = 445, share: String) throws {
        let host = host.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        let share = share.trimmingCharacters(in: .whitespacesAndNewlines)
        let bracketed = host.hasPrefix("[") && host.hasSuffix("]")
        let literal = bracketed ? String(host.dropFirst().dropLast()) : host
        var address = in6_addr()
        let isIPv6 = literal.contains(":") && !literal.utf8.contains(0)
            && literal.withCString { inet_pton(AF_INET6, $0, &address) == 1 }
        let dns = host.hasSuffix(".") ? String(host.dropLast()) : host
        let isDNS = !dns.isEmpty && dns.utf8.count <= 253
            && dns.split(separator: ".", omittingEmptySubsequences: false).allSatisfy { label in
                (1...63).contains(label.utf8.count) && label.first != "-" && label.last != "-"
                    && label.utf8.allSatisfy { (97...122).contains($0) || (48...57).contains($0) || $0 == 45 }
            }
        guard isIPv6 || isDNS, let port = UInt16(exactly: port), port != 0,
              SMBPath.validComponent(share), share.utf8.count <= 80,
              !["ipc$", "admin$"].contains(share.lowercased()) else { throw SMBSourceError.invalidSource }
        self.host = isIPv6 ? "[\(literal)]" : host
        self.port = port
        self.share = share
    }

    public var authority: String { "\(host):\(port)" }

    private enum CodingKeys: String, CodingKey { case host, port, share }
    public init(from decoder: Decoder) throws {
        let box = try decoder.container(keyedBy: CodingKeys.self)
        try self.init(host: box.decode(String.self, forKey: .host),
                      port: box.decode(Int.self, forKey: .port),
                      share: box.decode(String.self, forKey: .share))
    }
}

public struct SMBCredentials: Equatable, Sendable {
    public let user: String
    public let domain: String
    public let password: String

    public init(user: String, domain: String, password: String) throws {
        let user = user.trimmingCharacters(in: .whitespacesAndNewlines)
        let domain = domain.trimmingCharacters(in: .whitespacesAndNewlines)
        let forbidden = CharacterSet.controlCharacters.union(CharacterSet(charactersIn: "\\/@"))
        guard !user.isEmpty, !password.isEmpty, !password.utf8.contains(0),
              user.rangeOfCharacter(from: forbidden) == nil,
              domain.rangeOfCharacter(from: forbidden) == nil else { throw SMBSourceError.invalidSource }
        self.user = user
        self.domain = domain
        self.password = password
    }
}

public struct SMBPath: Equatable, Sendable {
    public let components: [String]
    public static let root = SMBPath(components: [])
    private init(components: [String]) { self.components = components }

    public init(relative: String) throws {
        let parts = relative.isEmpty ? [] : relative.components(separatedBy: "/")
        guard relative.utf8.count <= 4096, parts.allSatisfy(Self.validComponent) else {
            throw SMBSourceError.invalidPath
        }
        components = parts
    }

    static func validComponent(_ component: String) -> Bool {
        let forbidden = CharacterSet.controlCharacters.union(CharacterSet(charactersIn: "/\\:\"<>|?*"))
        return !component.isEmpty && component != "." && component != ".."
            && component.last != "." && component.last != " " && component.utf16.count <= 255
            && component.rangeOfCharacter(from: forbidden) == nil
    }

    public var wirePath: String { components.joined(separator: "/") }
    public var parent: SMBPath? { components.isEmpty ? nil : SMBPath(components: Array(components.dropLast())) }

    public func appending(_ name: String) throws -> SMBPath {
        guard Self.validComponent(name) else { throw SMBSourceError.invalidPath }
        return try SMBPath(relative: (components + [name]).joined(separator: "/"))
    }
}

public struct SMBEntry: Identifiable, Equatable, Sendable {
    public let name: String
    public let isDirectory: Bool
    public var id: String { name }
    public var audioExtension: String? {
        guard !isDirectory else { return nil }
        let ext = (name as NSString).pathExtension.lowercased()
        return ["mp3", "m4a", "aac", "wav", "flac", "aiff", "aif", "caf"].contains(ext) ? ext : nil
    }
}

public enum SMBByteRange {
    public static let maxChunk = 256 * 1024

    public static func resolve(requestedOffset: Int64, currentOffset: Int64, length: Int,
                               throughEOF: Bool, size: Int64) throws -> Range<Int64> {
        guard requestedOffset >= 0, currentOffset >= 0, length >= 0, size >= 0 else {
            throw SMBSourceError.invalidRange
        }
        let start = max(requestedOffset, currentOffset)
        guard start <= size else { throw SMBSourceError.invalidRange }
        let end = throughEOF ? size : requestedOffset + min(Int64(length), size - requestedOffset)
        guard start <= end else { throw SMBSourceError.invalidRange }
        return start..<end
    }

    public static func chunk(_ range: Range<Int64>) -> Range<Int64> {
        range.lowerBound..<(range.lowerBound + min(Int64(maxChunk), range.upperBound - range.lowerBound))
    }
}
