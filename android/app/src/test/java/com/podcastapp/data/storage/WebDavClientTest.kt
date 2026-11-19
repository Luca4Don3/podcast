package com.podcastapp.data.storage

import com.podcastapp.data.feed.CredentialKind
import com.podcastapp.data.feed.SourceCredentials
import com.podcastapp.data.feed.SourceTransport
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** 纯 JVM 单测：只覆盖协议层纯函数与 URL 拼接，不做任何网络请求。 */
class WebDavClientTest {

    private fun credentials(): SourceCredentials = SourceCredentials(
        kind = CredentialKind.BEARER,
        secret = "super-secret-token",
    )

    private fun client(
        baseUrl: String = "https://dav.example.com/files/",
        transport: SourceTransport = SourceTransport(),
    ): WebDavClient = WebDavClient(baseUrl, credentials(), transport)

    private fun multistatus(vararg responses: String): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <d:multistatus xmlns:d="DAV:">
        ${responses.joinToString("\n")}
        </d:multistatus>
    """.trimIndent()

    private fun response(href: String, props: String = "<d:resourcetype/>"): String = """
        <d:response>
          <d:href>${href}</d:href>
          <d:propstat><d:prop>${props}</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
        </d:response>
    """.trimIndent()

    @Test
    fun parsesDirectoriesAndFilesFromPropfindXml() {
        val xml = multistatus(
            response("/files/", "<d:resourcetype><d:collection/></d:resourcetype>"),
            response(
                "/files/Music%20Folder/",
                """
                <d:resourcetype><d:collection/></d:resourcetype>
                <d:displayname>Music Folder</d:displayname>
                <d:getlastmodified>Tue, 18 Nov 2025 08:00:00 +0000</d:getlastmodified>
                """.trimIndent(),
            ),
            """
            <d:response>
              <d:href>/files/Music%20Folder/ep%201.mp3</d:href>
              <d:propstat>
                <d:prop>
                  <d:resourcetype/>
                  <d:getcontentlength>12345</d:getcontentlength>
                  <d:getcontenttype>audio/mpeg</d:getcontenttype>
                  <d:getlastmodified>Tue, 18 Nov 2025 08:00:00 +0000</d:getlastmodified>
                </d:prop>
                <d:status>HTTP/1.1 200 OK</d:status>
              </d:propstat>
              <d:propstat>
                <d:prop><d:getetag>"ignored"</d:getetag></d:prop>
                <d:status>HTTP/1.1 404 Not Found</d:status>
              </d:propstat>
            </d:response>
            """.trimIndent(),
        )

        val entries = parsePropFindXml(xml, "/files")
        assertEquals(2, entries.size)

        val dir = entries[0]
        assertTrue(dir.directory)
        assertEquals("Music Folder", dir.name)
        assertEquals("/files/Music Folder", dir.path)
        assertNull(dir.size)

        val file = entries[1]
        assertFalse(file.directory)
        assertEquals("ep 1.mp3", file.name)
        assertEquals("/files/Music Folder/ep 1.mp3", file.path)
        assertEquals(12345L, file.size)
        assertEquals("audio/mpeg", file.contentType)
        assertEquals(Instant.parse("2025-11-18T08:00:00Z").toEpochMilli(), file.modifiedAt)
    }

    @Test
    fun capsEntriesAt500AndSkipsDuplicates() {
        val many = (0 until 520).map { index -> response("/files/item-$index.mp3") }
        val entries = parsePropFindXml(multistatus(*many.toTypedArray()), "/files")
        assertEquals(500, entries.size)
        assertEquals("/files/item-499.mp3", entries.last().path)

        val duplicated = parsePropFindXml(
            multistatus(response("/files/same.mp3"), response("/files/same.mp3"), response("/files/other.mp3")),
            "/files",
        )
        assertEquals(2, duplicated.size)
    }

    @Test
    fun rejectsMalformedXmlWrongRootAndDtd() {
        assertThrows(Exception::class.java) { parsePropFindXml("<d:multistatus") }
        assertThrows(IllegalArgumentException::class.java) { parsePropFindXml("<html><body/></html>") }
        assertThrows(IllegalArgumentException::class.java) { parsePropFindXml("   ") }
        val xxe = """
            <?xml version="1.0"?>
            <!DOCTYPE m [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
            <d:multistatus xmlns:d="DAV:"><d:response><d:href>&xxe;</d:href></d:response></d:multistatus>
        """.trimIndent()
        assertThrows(Exception::class.java) { parsePropFindXml(xxe) }
    }

    @Test
    fun resolveFileUrlJoinsRelativeAndAbsolutePaths() {
        val c = client()
        assertEquals("https://dav.example.com/files/ep.mp3", c.resolveFileUrl("ep.mp3"))
        assertEquals("https://dav.example.com/files/sub/ep.mp3", c.resolveFileUrl("sub/ep.mp3"))
        assertEquals("https://dav.example.com/files/ep.mp3", c.resolveFileUrl("/files/ep.mp3"))
        assertEquals("https://dav.example.com/files/", c.resolveFileUrl(""))

        val noTrailingSlash = client("https://dav.example.com/files")
        assertEquals("https://dav.example.com/files/ep.mp3", noTrailingSlash.resolveFileUrl("ep.mp3"))
    }

    @Test
    fun credentialsNeverEnterUrls() {
        val c = client()
        val url = c.resolveFileUrl("ep.mp3")
        assertFalse(url.contains("super-secret-token"))
        assertFalse(url.contains("@"))

        assertThrows(IllegalArgumentException::class.java) {
            client("https://user:pass@dav.example.com/files/")
        }
        assertThrows(IllegalArgumentException::class.java) {
            client("https://dav.example.com/files/?token=abc")
        }
        assertThrows(IllegalArgumentException::class.java) { c.resolveFileUrl("https://evil.example.com/x") }
        assertThrows(IllegalArgumentException::class.java) { c.resolveFileUrl("//evil.example.com/x") }
    }

    @Test
    fun rejectsNonHttpSchemesAndHttpsDowngrade() {
        for (bad in listOf("ftp://dav.example.com/files/", "file:///tmp/dav", "javascript:alert(1)", "content://dav/files", "dav.example.com/files")) {
            assertThrows(IllegalArgumentException::class.java) { client(bad) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            WebDavClient("http://dav.example.com/", credentials(), SourceTransport(requireHttps = true))
        }
        assertEquals(
            "http://dav.example.com/files/ep.mp3",
            WebDavClient("http://dav.example.com/files", credentials(), SourceTransport())
                .resolveFileUrl("ep.mp3"),
        )
    }
}
