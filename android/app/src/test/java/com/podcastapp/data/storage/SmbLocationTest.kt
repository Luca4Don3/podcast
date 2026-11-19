package com.podcastapp.data.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** 纯 JVM 单测：只覆盖 SMB 地址解析与路径/扩展名的纯函数，不建立任何 SMB 连接。 */
class SmbLocationTest {

    @Test
    fun parseCanonicalLocationNormalizesSchemeHostPortAndTrailingSlash() {
        val default = SmbLocation.parse("smb://nas.local/Media")
        assertEquals("nas.local", default.host)
        assertEquals(445, default.port)
        assertEquals("Media", default.share)
        assertEquals("smb://nas.local/Media", default.url)

        // 首尾空白、协议大小写、末尾斜杠和显式默认端口都应归一到同一结果。
        assertEquals(default, SmbLocation.parse("  smb://nas.local/Media/  "))
        assertEquals(default, SmbLocation.parse("SMB://nas.local/Media"))
        assertEquals(default, SmbLocation.parse("smb://nas.local:445/Media"))

        // OkHttp 解析时主机名小写。
        val lowercased = SmbLocation.parse("smb://NAS.Example.COM/Media")
        assertEquals("nas.example.com", lowercased.host)
        assertEquals("smb://nas.example.com/Media", lowercased.url)

        val withPort = SmbLocation.parse("smb://nas.local:4455/Media/")
        assertEquals("nas.local", withPort.host)
        assertEquals(4455, withPort.port)
        assertEquals("smb://nas.local:4455/Media", withPort.url)

        // 只有 IPC$ 被保留名过滤；普通带 $ 的共享名合法。
        assertEquals("Media$", SmbLocation.parse("smb://nas.local/Media$").share)
    }

    @Test
    fun parseHandlesIPv6LiteralHostsWithAndWithoutPort() {
        val plain = SmbLocation.parse("smb://[::1]/Media")
        assertEquals("::1", plain.host)
        assertEquals(445, plain.port)
        assertEquals("smb://[::1]/Media", plain.url)

        val withPort = SmbLocation.parse("smb://[2001:db8::1]:4455/Media")
        assertEquals("2001:db8::1", withPort.host)
        assertEquals(4455, withPort.port)
        assertEquals("smb://[2001:db8::1]:4455/Media", withPort.url)
    }

    @Test
    fun parseKeepsEncodedShareAndNeverCarriesCredentials() {
        val encoded = SmbLocation.parse("smb://nas.local/My%20Media")
        assertEquals("My Media", encoded.share)
        assertEquals("smb://nas.local/My%20Media", encoded.url)

        val clean = SmbLocation.parse("smb://nas.local/Media")
        assertFalse(clean.url.contains("@"))
        assertFalse(clean.url.contains("secret"))
        assertFalse(encoded.url.contains("@"))

        for (bad in listOf(
            "smb://user:secret@nas.local/Media",
            "smb://user%40nas.local/Media",
            "smb:// @nas.local/Media",
            "smb://nas.local/Media?token=secret",
            "smb://nas.local/Media#secret",
        )) {
            assertThrows(IllegalArgumentException::class.java) { SmbLocation.parse(bad) }
        }
    }

    @Test
    fun parseRejectsControlCharactersAndMalformedAuthority() {
        for (bad in listOf(
            "http://nas.local/Media",
            "smb:///Media",
            "smb://nas.local:/Media",
            "smb://nas.local:abc/Media",
            "smb://nas.local:99999/Media",
            "smb://nas.local/Me\u0000dia",
            "smb://nas.local/Me\u0007dia",
            "smb://nas.local/Media\\x",
            "smb://nas.local/Media?x",
            "smb://nas.local/Media#x",
            "smb://na s.local/Media",
        )) {
            assertThrows(IllegalArgumentException::class.java) { SmbLocation.parse(bad) }
        }
    }

    @Test
    fun parseRejectsTraversalAndIllegalShareNames() {
        for (bad in listOf(
            "smb://nas.local/Media/../secret",
            "smb://nas.local/./Media",
            "smb://nas.local/a%2Fb",
            "smb://nas.local/..",
            "smb://nas.local/IPC$",
            "smb://nas.local/ipc$",
            "smb://nas.local/Media.",
            "smb://nas.local/Med:ia",
            "smb://nas.local/Media//",
        )) {
            assertThrows(Exception::class.java) { SmbLocation.parse(bad) }
        }
    }

    @Test
    fun validSmbSegmentChecksNamesWithoutFileSystemMetacharacters() {
        for (good in listOf("Media", "My Music", "ep-01_v2.mp3", "文件", "a.b", "Media$", " leading")) {
            assertTrue("应接受: $good", validSmbSegment(good))
        }
        for (bad in listOf(
            "", "   ", "Media.", "Media ", "a/b", "a\\b", "a:b", "a*b", "a?b",
            "a\"b", "a<b", "a>b", "a|b", "a\u0000b", "a\u001fb",
        )) {
            assertFalse("应拒绝: $bad", validSmbSegment(bad))
        }
    }

    @Test
    fun normalizeSmbRootConvertsSeparatorsTrimsSlashesAndValidates() {
        assertEquals("Media", normalizeSmbRoot("  /Media/  "))
        assertEquals("NAS/Media/Music", normalizeSmbRoot("\\NAS\\Media\\Music\\"))
        assertEquals("Media/Music", normalizeSmbRoot("/Media/Music/"))
        assertEquals("", normalizeSmbRoot("///"))
        assertEquals("", normalizeSmbRoot("   "))
        assertThrows(IllegalArgumentException::class.java) { normalizeSmbRoot("Media/../secret") }
        assertThrows(IllegalArgumentException::class.java) { normalizeSmbRoot("Med?ia") }
    }

    @Test
    fun smbPathSegmentsSplitsOnlyValidRelativePaths() {
        assertEquals(emptyList<String>(), smbPathSegments(""))
        assertEquals(listOf("Media", "ep.mp3"), smbPathSegments("Media/ep.mp3"))
        for (bad in listOf("/Media", "Media/", "Media//Music", "Media/../secret", "..", ".", "a/b?")) {
            assertThrows(IllegalArgumentException::class.java) { smbPathSegments(bad) }
        }
    }

    @Test
    fun smbAudioMimeRecognizesExtensionsCaseInsensitively() {
        val expected = mapOf(
            "ep.mp3" to "audio/mpeg",
            "EP.MP3" to "audio/mpeg",
            "a.m4a" to "audio/mp4",
            "a.m4b" to "audio/mp4",
            "a.aac" to "audio/aac",
            "a.ogg" to "audio/ogg",
            "a.oga" to "audio/ogg",
            "a.opus" to "audio/ogg",
            "a.wav" to "audio/wav",
            "a.WAVE" to "audio/wav",
            "a.flac" to "audio/flac",
        )
        for ((name, mime) in expected) assertEquals(name, mime, smbAudioMime(name))
        assertEquals("audio/mpeg", smbAudioMime(".mp3"))
        for (name in listOf("a.txt", "a.mp3.bak", "noextension", "a.", "a.mp3 ")) {
            assertNull(name, smbAudioMime(name))
        }
    }
}
