package com.podcastapp.data.storage

import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Optional integration test for the isolated Samba fixture. It runs only when the harness
 * supplies a loopback SMB address; no real NAS credentials or addresses are ever contacted.
 */
class SmbFixtureTest {
    @Test
    fun readsKnownBytesFromReadOnlyShare() {
        val address = System.getenv("PODCAST_TEST_SMB_URL").orEmpty()
        val user = System.getenv("PODCAST_TEST_SMB_USER").orEmpty()
        val password = System.getenv("PODCAST_TEST_SMB_PASSWORD").orEmpty()
        val domain = System.getenv("PODCAST_TEST_SMB_DOMAIN").orEmpty()
        assumeTrue("SMB fixture not configured", address.isNotBlank() && user.isNotBlank() && password.isNotBlank())
        val host = URI.create(address.replace('\\', '/')).host
        assumeTrue("Only loopback fixture addresses are allowed", host == "127.0.0.1" || host == "::1" || host == "localhost")

        val location = SmbLocation.parse(address)
        val credentials = SmbCredentials(user, password, domain)
        val client = SmbClient(location, "media", credentials)
        val entries = client.list("media")
        assertTrue(entries.any { it.name == "seek.mp3" && !it.directory && it.mime == "audio/mpeg" })
        assertTrue(entries.none { it.name == "notice.txt" })

        SmbSocket(location).use { socket ->
            val handle = client.open("media/seek.mp3", socket)
            handle.use {
                assertEquals(16L, it.size)
                val buffer = ByteArray(6)
                val count = it.read(buffer, 10L, 0, buffer.size)
                assertEquals(6, count)
                assertEquals("abcdef", String(buffer, 0, count, Charsets.US_ASCII))
            }
        }
    }
}
