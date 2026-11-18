package com.podcastapp

import com.podcastapp.data.remote.ApiClient
import org.junit.Assert.assertEquals
import org.junit.Test

class ApiClientUrlTest {

    private fun client(baseUrl: String) = ApiClient(
        baseUrl = baseUrl,
        tokenProvider = { null },
        onUnauthorized = {},
        debug = false,
    )

    @Test
    fun addsHttpsSchemeWhenMissing() {
        val c = client("podcast.example.com")
        assertEquals("https://podcast.example.com/api/v1/", c.baseUrlResolved)
    }

    @Test
    fun keepsExplicitHttpAndTrailingSlash() {
        val c = client("http://10.0.2.2:8080/")
        assertEquals("http://10.0.2.2:8080/api/v1/", c.baseUrlResolved)
    }

    @Test
    fun keepsPathPrefix() {
        val c = client("https://example.com/api/")
        assertEquals("https://example.com/api/", c.baseUrlResolved)
    }

    @Test
    fun normalizesSpacesAndMissingTrailingSlash() {
        val c = client("  https://example.com/podcast  ")
        assertEquals("https://example.com/podcast/", c.baseUrlResolved)
    }
}
