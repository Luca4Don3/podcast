import XCTest
@testable import PodcastKit

final class SMBSourceTests: XCTestCase {

    private func assertSMBError(_ expected: SMBSourceError,
                                file: StaticString = #filePath, line: UInt = #line,
                                _ body: () throws -> Void) {
        XCTAssertThrowsError(try body(), file: file, line: line) { error in
            XCTAssertEqual(error as? SMBSourceError, expected, file: file, line: line)
        }
    }

    // MARK: - SMBEndpoint

    func testEndpointNormalizesHostShareAndPort() throws {
        let endpoint = try SMBEndpoint(host: "  NAS.Example.COM  ", share: " Media ")
        XCTAssertEqual(endpoint.host, "nas.example.com")
        XCTAssertEqual(endpoint.port, 445)
        XCTAssertEqual(endpoint.share, "Media")
        XCTAssertEqual(endpoint.authority, "nas.example.com:445")

        let ipv4 = try SMBEndpoint(host: "192.168.1.10", port: 5005, share: "Media")
        XCTAssertEqual(ipv4.host, "192.168.1.10")
        XCTAssertEqual(ipv4.authority, "192.168.1.10:5005")

        let maxPort = try SMBEndpoint(host: "nas.local", port: 65535, share: "Media")
        XCTAssertEqual(maxPort.port, 65535)
    }

    func testEndpointAcceptsIPv6LiteralsAndTrailingDotFqdn() throws {
        let bracketed = try SMBEndpoint(host: "[::1]", share: "Media")
        XCTAssertEqual(bracketed.host, "[::1]")
        XCTAssertEqual(bracketed.authority, "[::1]:445")

        let bare = try SMBEndpoint(host: "2001:db8::1", port: 4455, share: "Media")
        XCTAssertEqual(bare.host, "[2001:db8::1]")
        XCTAssertEqual(bare.authority, "[2001:db8::1]:4455")

        let fqdn = try SMBEndpoint(host: "nas.example.com.", share: "Media")
        XCTAssertEqual(fqdn.host, "nas.example.com.")
    }

    func testEndpointRejectsInvalidHostsAndPorts() {
        let badHosts = [
            "", "   ", "host_name", "-nas.local", "nas-.local", "nas..local",
            "nas local", "nas/local", "not:ipv6", "[gg::1]", "[::1", "::1]",
            String(repeating: "a", count: 64) + ".example.com",
            String(repeating: "a.", count: 127) + "a",
        ]
        for host in badHosts {
            assertSMBError(.invalidSource) { _ = try SMBEndpoint(host: host, share: "Media") }
        }
        for port in [-1, 0, 65536, Int.max] {
            assertSMBError(.invalidSource) { _ = try SMBEndpoint(host: "nas.local", port: port, share: "Media") }
        }
    }

    func testEndpointRejectsReservedAndMalformedShares() {
        // 首尾空白会被裁剪，所以 " Media " 合法；句点/控制符/通配符/保留名均被拒绝。
        for share in [
            "", "   ", ".", "..", "Media.", "Med/ia", "Med\\ia",
            "ipc$", "IPC$", "admin$", "ADMIN$", "a:b", "a?b", "a*b",
            String(repeating: "s", count: 81),
        ] {
            assertSMBError(.invalidSource) { _ = try SMBEndpoint(host: "nas.local", share: share) }
        }
        XCTAssertNoThrow(try SMBEndpoint(host: "nas.local", share: String(repeating: "s", count: 80)))
        XCTAssertNoThrow(try SMBEndpoint(host: "nas.local", share: "C$"))
    }

    // MARK: - SMBPath

    func testPathRootComponentsWirePathAndParent() throws {
        XCTAssertEqual(try SMBPath(relative: ""), .root)
        XCTAssertEqual(try SMBPath(relative: "").components, [])
        XCTAssertEqual(SMBPath.root.wirePath, "")
        XCTAssertNil(SMBPath.root.parent)

        let path = try SMBPath(relative: "Music/2025/ep 01.mp3")
        XCTAssertEqual(path.components, ["Music", "2025", "ep 01.mp3"])
        XCTAssertEqual(path.wirePath, "Music/2025/ep 01.mp3")
        XCTAssertEqual(path.parent?.wirePath, "Music/2025")
        XCTAssertEqual(path.parent?.parent?.wirePath, "Music")
        XCTAssertEqual(path.parent?.parent?.parent, .root)
        XCTAssertNil(path.parent?.parent?.parent?.parent)
    }

    func testPathRejectsAbsoluteTraversalAndIllegalComponents() {
        for raw in [
            "/Music", "Music/", "Music//ep.mp3", "../secret", "Music/../secret",
            "./Music", "Music\\ep", "C:\\x", "name.", "name ", "a:b", "a?b", "a*b",
        ] {
            assertSMBError(.invalidPath) { _ = try SMBPath(relative: raw) }
        }
        assertSMBError(.invalidPath) { _ = try SMBPath(relative: String(repeating: "a", count: 256)) }
        XCTAssertNoThrow(try SMBPath(relative: String(repeating: "a", count: 255)))

        let atLimit = Array(repeating: String(repeating: "a", count: 240), count: 17).joined(separator: "/")
        XCTAssertEqual(atLimit.utf8.count, 4096)
        XCTAssertNoThrow(try SMBPath(relative: atLimit))
        let overLimit = Array(repeating: String(repeating: "a", count: 241), count: 17).joined(separator: "/")
        XCTAssertEqual(overLimit.utf8.count, 4113)
        assertSMBError(.invalidPath) { _ = try SMBPath(relative: overLimit) }
    }

    func testPathAppendingValidatesEachName() throws {
        let music = try SMBPath.root.appending("Music")
        XCTAssertEqual(music.components, ["Music"])
        let file = try music.appending("ep.mp3")
        XCTAssertEqual(file.wirePath, "Music/ep.mp3")

        for bad in ["", ".", "..", "../secret", "a/b", "a\\b", "name.", "name ", "a:b"] {
            assertSMBError(.invalidPath) { _ = try music.appending(bad) }
        }
    }

    func testValidComponentRules() {
        XCTAssertTrue(SMBPath.validComponent("ep 01.mp3"))
        XCTAssertTrue(SMBPath.validComponent("文件"))
        for bad in ["", ".", "..", "a.", "a ", "a/b", "a\\b", "a\u{0}b", String(repeating: "a", count: 256)] {
            XCTAssertFalse(SMBPath.validComponent(bad), bad)
        }
    }

    // MARK: - SMBCredentials

    func testCredentialsTrimAndValidateFields() throws {
        let creds = try SMBCredentials(user: "  alice  ", domain: " CORP ", password: "pw")
        XCTAssertEqual(creds.user, "alice")
        XCTAssertEqual(creds.domain, "CORP")
        XCTAssertEqual(creds.password, "pw")
        XCTAssertNoThrow(try SMBCredentials(user: "alice", domain: "", password: "pw"))

        // 密码不参与字符黑名单过滤，也不裁剪空白。
        let special = try SMBCredentials(user: "alice", domain: "", password: " p@ss/w\\ord ")
        XCTAssertEqual(special.password, " p@ss/w\\ord ")
    }

    func testCredentialsRejectEmptyAndForbiddenValues() {
        for user in ["", "   ", "bad\\user", "bad/user", "bad@user", "ba\u{1}d"] {
            assertSMBError(.invalidSource) { _ = try SMBCredentials(user: user, domain: "", password: "pw") }
        }
        for domain in ["bad\\dom", "bad/dom", "bad@dom", "ba\u{1}d"] {
            assertSMBError(.invalidSource) { _ = try SMBCredentials(user: "alice", domain: domain, password: "pw") }
        }
        assertSMBError(.invalidSource) { _ = try SMBCredentials(user: "alice", domain: "", password: "") }
        assertSMBError(.invalidSource) { _ = try SMBCredentials(user: "alice", domain: "", password: "p\u{0}w") }
    }

    // MARK: - SMBByteRange

    func testResolveHonorsCurrentOffsetAndClampsToEOF() throws {
        XCTAssertEqual(try SMBByteRange.resolve(requestedOffset: 0, currentOffset: 0, length: 100, throughEOF: false, size: 1000), 0..<100)
        XCTAssertEqual(try SMBByteRange.resolve(requestedOffset: 50, currentOffset: 0, length: 100, throughEOF: false, size: 1000), 50..<150)
        XCTAssertEqual(try SMBByteRange.resolve(requestedOffset: 50, currentOffset: 70, length: 100, throughEOF: false, size: 1000), 70..<150)
        XCTAssertEqual(try SMBByteRange.resolve(requestedOffset: 950, currentOffset: 0, length: 100, throughEOF: false, size: 1000), 950..<1000)

        let through = try SMBByteRange.resolve(requestedOffset: 100, currentOffset: 200, length: 0, throughEOF: true, size: 500)
        XCTAssertEqual(through, 200..<500)

        let atEOF = try SMBByteRange.resolve(requestedOffset: 1000, currentOffset: 1000, length: 10, throughEOF: false, size: 1000)
        XCTAssertEqual(atEOF, 1000..<1000)

        let empty = try SMBByteRange.resolve(requestedOffset: 0, currentOffset: 0, length: 0, throughEOF: false, size: 0)
        XCTAssertEqual(empty, 0..<0)
    }

    func testResolveRejectsNegativeAndInconsistentRanges() {
        assertSMBError(.invalidRange) { _ = try SMBByteRange.resolve(requestedOffset: -1, currentOffset: 0, length: 1, throughEOF: false, size: 100) }
        assertSMBError(.invalidRange) { _ = try SMBByteRange.resolve(requestedOffset: 0, currentOffset: -1, length: 1, throughEOF: false, size: 100) }
        assertSMBError(.invalidRange) { _ = try SMBByteRange.resolve(requestedOffset: 0, currentOffset: 0, length: -1, throughEOF: false, size: 100) }
        assertSMBError(.invalidRange) { _ = try SMBByteRange.resolve(requestedOffset: 0, currentOffset: 0, length: 1, throughEOF: false, size: -1) }
        assertSMBError(.invalidRange) { _ = try SMBByteRange.resolve(requestedOffset: 101, currentOffset: 0, length: 1, throughEOF: false, size: 100) }
        assertSMBError(.invalidRange) { _ = try SMBByteRange.resolve(requestedOffset: 0, currentOffset: 101, length: 1, throughEOF: false, size: 100) }
        assertSMBError(.invalidRange) { _ = try SMBByteRange.resolve(requestedOffset: 10, currentOffset: 20, length: 0, throughEOF: false, size: 100) }
    }

    func testChunkSplitsAtMaxChunkBoundary() {
        let max = Int64(SMBByteRange.maxChunk)
        XCTAssertEqual(max, 262144)
        XCTAssertEqual(SMBByteRange.chunk(0..<100), 0..<100)
        XCTAssertEqual(SMBByteRange.chunk(0..<max), 0..<max)
        XCTAssertEqual(SMBByteRange.chunk(0..<(max + 1)), 0..<max)
        XCTAssertEqual(SMBByteRange.chunk(100..<(100 + max + 5000)), 100..<(100 + max))
        XCTAssertEqual(SMBByteRange.chunk(50..<50), 50..<50)
        XCTAssertEqual(SMBByteRange.chunk((Int64.max - 10)..<Int64.max), (Int64.max - 10)..<Int64.max)
    }
}
