import Foundation
import XCTest
@testable import PodcastKit
final class WebDavClientTests: XCTestCase {
    func testPathAndURIPolicyRejectsCredentialsAndTraversal() {
        XCTAssertNoThrow(try WebDavURLPolicy.canonicalOrigin(URL(string: "https://dav.example.com/dav/")!))
        XCTAssertThrowsError(try WebDavURLPolicy.canonicalOrigin(URL(string: "https://user:pass@dav.example.com/")!))
        XCTAssertThrowsError(try WebDavURLPolicy.canonicalOrigin(URL(string: "https://dav.example.com/?token=abc")!))
        XCTAssertThrowsError(try WebDavURLPolicy.relativeSegments("../secret"))
        XCTAssertEqual(try? WebDavURLPolicy.relativeSegments("Music/my ep.mp3"), ["Music", "my ep.mp3"])
    }
    func testPropfindXmlParsesEntriesAndRejectsDtd() throws {
        let xml = """
        <?xml version="1.0" encoding="utf-8"?>
        <d:multistatus xmlns:d="DAV:">
          <d:response><d:href>/dav/Music%20Folder/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype><d:displayname>Music Folder</d:displayname></d:prop></d:propstat></d:response>
          <d:response><d:href>/dav/ep%201.mp3</d:href><d:propstat><d:prop><d:resourcetype/><d:getcontentlength>42</d:getcontentlength><d:getcontenttype>audio/mpeg</d:getcontenttype></d:prop></d:propstat></d:response>
        </d:multistatus>
        """
        let entries = try WebDavXMLParser.parse(Data(xml.utf8), requestSegments: ["dav"], prefixSegments: ["dav"])
        XCTAssertEqual(entries.count, 2)
        XCTAssertEqual(entries.first?.path, "Music Folder")
        XCTAssertTrue(entries.first?.directory == true)
        XCTAssertEqual(entries.last?.name, "ep 1.mp3")
        XCTAssertEqual(entries.last?.size, 42)
        let xxe = """
        <?xml version="1.0"?><!DOCTYPE foo [<!ENTITY x SYSTEM "file:///etc/passwd">]><d:multistatus xmlns:d="DAV:"/>
        """
        XCTAssertThrowsError(try WebDavXMLParser.parse(Data(xxe.utf8), requestSegments: ["dav"], prefixSegments: ["dav"]))
    }

    func testStatusMappingIsFixedAndSafe() {
        XCTAssertTrue(WebDavError.badStatus(207).errorDescription?.contains("207") == true)
        XCTAssertEqual(WebDavError.invalidBaseURL.errorDescription?.contains("token"), false)
    }
}
