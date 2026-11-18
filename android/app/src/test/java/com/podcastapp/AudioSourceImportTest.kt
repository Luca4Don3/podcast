package com.podcastapp.data

import com.podcastapp.data.remote.ImportStationsRequest
import com.podcastapp.data.remote.ImportStationsResponse
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioSourceImportTest {
    @Test fun singleTrimsNameAndUrlWithoutChangingSignedQuery() {
        val item = AudioSourceImport.single("  我的音源  ", " https://audio.example.test/live?key=a%2Fb&v=2 ")
        assertEquals("我的音源", item.name)
        assertEquals("https://audio.example.test/live?key=a%2Fb&v=2", item.streamUrl)
        assertEquals("", item.externalId)
        assertEquals("", item.homepage)
    }

    @Test fun unnamedSourceUsesHostAndAllowsHttpHttps() {
        assertEquals("audio.example.test", AudioSourceImport.single("", "HTTP://audio.example.test/live").name)
        assertEquals("http://audio.example.test/live", HttpSourceUrl.parse("HTTP://audio.example.test/live"))
        assertEquals("https://[2001:db8::1]:8443/live", HttpSourceUrl.parse("https://[2001:db8::1]:8443/live"))
    }

    @Test fun rejectsUnsupportedOrMalformedUrls() {
        listOf("", "file:///audio.mp3", "content://audio/1", "ftp://audio.example.test/live",
            "javascript:alert(1)", "//audio.example.test/live", "https://", "https:///live",
            "https://audio.example.test/a b", "https://audio.example.test/a\nb", "https://audio.example.test/%zz",
            "https://user:pass@audio.example.test/live", "https://audio.example.test:0/live",
            "https://audio.example.test:65536/live", "https://audio.example.test:/live").forEach { url ->
            assertThrows("must reject " + url, IllegalArgumentException::class.java) { HttpSourceUrl.parse(url) }
        }
    }

    @Test fun parsesBatchWithNamesBareUrlsBlankLinesAndCrLf() {
        val items = AudioSourceImport.batch("  自有直播 | https://audio.example.test/a.m3u8  \r\n\r\nhttp://audio.example.test/b.mp3\n")
        assertEquals(2, items.size)
        assertEquals("自有直播", items[0].name)
        assertEquals("https://audio.example.test/a.m3u8", items[0].streamUrl)
        assertEquals("audio.example.test", items[1].name)
    }

    @Test fun reportsEveryInvalidLineUsingOriginalLineNumbers() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            AudioSourceImport.batch("https://audio.example.test/ok\n\n坏协议|ftp://audio.example.test/no\n缺地址|\n|https://audio.example.test/a\n多|段|https://audio.example.test/b")
        }
        listOf(3, 4, 5, 6).forEach { assertTrue(error.message.orEmpty().contains("第 " + it + " 行")) }
        assertFalse(error.message.orEmpty().contains("第 1 行"))
    }

    @Test fun rejectsDuplicateUrlInsteadOfSilentlyDroppingIt() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            AudioSourceImport.batch("甲|https://audio.example.test/live\n乙|https://audio.example.test/live")
        }
        assertTrue(error.message.orEmpty().contains("第 2 行"))
        assertTrue(error.message.orEmpty().contains("第 1 行"))
        assertTrue(error.message.orEmpty().contains("重复"))
    }

    @Test fun acceptsEncodedPipeButRejectsRawPipe() {
        assertEquals(1, AudioSourceImport.batch("甲|https://audio.example.test/live?a=%7C").size)
        assertThrows(IllegalArgumentException::class.java) {
            AudioSourceImport.batch("甲|https://audio.example.test/live?a=|")
        }
    }

    @Test fun enforcesNonEmptyAndBatchLimit() {
        assertThrows(IllegalArgumentException::class.java) { AudioSourceImport.batch(" \n\r\n") }
        val hundred = (1..100).joinToString("\n") { "https://audio.example.test/" + it }
        assertEquals(100, AudioSourceImport.batch(hundred).size)
        assertThrows(IllegalArgumentException::class.java) {
            AudioSourceImport.batch(hundred + "\nhttps://audio.example.test/101")
        }
    }

    @Test fun rejectsControlCharactersInName() {
        assertThrows(IllegalArgumentException::class.java) {
            AudioSourceImport.single("坏\t名称", "https://audio.example.test/live")
        }
    }

    @Test fun batchUsesExistingImportRequestAndResponseDtos() {
        val parsed = AudioSourceImport.batch("自有音频|https://audio.example.test/live")
        val request = Json.encodeToString(ImportStationsRequest(parsed))
        assertTrue(request.contains("\"stations\""))
        assertTrue(request.contains("\"streamUrl\":\"https://audio.example.test/live\""))
        assertFalse(request.contains("externalId"))
        val response = Json.decodeFromString<ImportStationsResponse>(
            """{"imported":1,"items":[{"id":"s1","name":"自有音频","streamUrl":"https://audio.example.test/live","source":"manual"}]}""",
        )
        assertEquals(1, response.imported)
        assertEquals("manual", response.items.single().source)
    }
}
