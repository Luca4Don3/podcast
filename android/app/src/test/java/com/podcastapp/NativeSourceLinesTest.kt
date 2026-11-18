package com.podcastapp.data

import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeSourceLinesTest {
    private fun reference(text: String): IntArray {
        var offset = 0
        val values = ArrayList<Int>()
        for ((i, line) in text.lineSequence().withIndex()) {
            val start = line.indexOfFirst { !it.isWhitespace() }
            if (start >= 0) {
                val last = line.indexOfLast { !it.isWhitespace() } + 1
                val pipe = line.indexOf('|')
                values += i + 1
                values += offset + start
                values += offset + last
                values += line.count { it == '|' }
                values += if (pipe < 0) -1 else offset + pipe
                if (values.size >= 505) break
            }
            offset += line.length
            if (text.getOrNull(offset) == '\r' && text.getOrNull(offset + 1) == '\n') offset += 2
            else if (offset < text.length) ++offset
        }
        return values.toIntArray()
    }

    @Test fun matchesReferenceOnLineEndingsAndUnicode() {
        val random = Random(31)
        val symbols = charArrayOf('\r', '\n', '\t', '|', ' ', '中', '文', '\u00a0', '\u3000', '\u202f', '\u0085', '\u001c', '\u0000', '\ud83d', '\ude03', 'x')
        val cases = listOf("", "\r\n\r", "\u3000A | B\r\n\rD|E||\n")
        val fixtures = cases + List(300) {
            buildString { repeat(random.nextInt(0, 180)) { append(symbols[random.nextInt(symbols.size)]) } }
        }
        for (input in fixtures) {
            assertArrayEquals(reference(input), NativeSourceLines.scanManaged(input))
            assertArrayEquals(reference(input), NativeSourceLines.scan(input))
        }
    }

    @Test fun capsSpansAtOneOverTheBatchLimit() {
        val input = "甲|https://example.test/a\r\n".repeat(5000)
        val result = NativeSourceLines.scan(input)
        assertArrayEquals(reference(input), result)
        assertTrue(result.size == 505)
    }

    @Test fun nativeBridgeReallyRunsWhenRequested() {
        val input = "\r\n 音源|https://example.test/live\r\n"
        val native = NativeSourceLines.scanNativeOrNull(input)
        if (System.getProperty("podcast.requireNative") == "true") assertNotNull(native)
        native?.let { assertArrayEquals(reference(input), it) }
    }
}
