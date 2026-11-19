package com.podcastapp.ui.discover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SharedUrlTest {
    @Test
    fun extractsUrlFromSharedSentence() {
        assertEquals(
            "https://podcasts.apple.com/us/podcast/id123456789",
            extractSharedUrl("推荐这个播客 https://podcasts.apple.com/us/podcast/id123456789 听听看"),
        )
    }

    @Test
    fun acceptsBareUrlAndRejectsOtherSchemes() {
        val url = "https://example.com/feed.xml"
        assertEquals(url, extractSharedUrl(url))
        assertNull(extractSharedUrl("ftp://example.com/feed.xml"))
        assertNull(extractSharedUrl("no link here"))
    }
}
