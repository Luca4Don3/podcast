package com.podcastapp

import com.podcastapp.data.ServerAddress
import com.podcastapp.data.remote.ServerHealthCheck
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.FutureTask
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ServerAddressTest {
    @Test fun skipUsesDefaultOrLeavesServerUnset() {
        val custom = ServerAddress.optional("  ")
        assertNull(custom)
        assertEquals("", ServerAddress.effective(custom, null))
        assertEquals("https://default.example/api/v1/", ServerAddress.effective(custom, "https://default.example/api/v1/"))
    }

    @Test fun explicitServerOverridesDefault() {
        val custom = ServerAddress.optional(" HTTP://[::1]:8080/api/v1 ")
        assertEquals("http://[::1]:8080/api/v1/", ServerAddress.effective(custom, "https://default.example/api/v1/"))
        assertEquals(custom, ServerAddress.optional("http://[::1]:8080/"))
    }

    @Test fun tailnetRoutesUseOrdinaryHttpOrHttpsAddresses() {
        assertEquals("http://100.64.1.2:8080/api/v1/", ServerAddress.optional("http://100.64.1.2:8080"))
        assertEquals("http://[fd7a:115c:a1e0::12]:8080/api/v1/", ServerAddress.optional("http://[fd7a:115c:a1e0::12]:8080"))
        assertEquals("https://nas.example.ts.net/api/v1/", ServerAddress.optional("nas.example.ts.net"))
        assertEquals("https://nas.example.ts.net/podcast/api/v1/", ServerAddress.optional("https://nas.example.ts.net/podcast/api/v1"))
        assertThrows(IllegalArgumentException::class.java) { ServerAddress.optional("tailscale://nas") }
    }

    @Test fun invalidServerUrlsAreRejected() {
        for (url in listOf("https://", "ftp://example.com", "https://user:pass@example.com", "https://example.com/?token=x", "https://example.com/#x", "https://example.com:65536", "https://exa mple.com")) {
            assertThrows(url, IllegalArgumentException::class.java) { ServerAddress.optional(url) }
        }
    }

    @Test fun healthCheckUsesApiPathAndNeverSendsAuth() {
        withHealthResponse(200, """{"status":"ok","database":true}""") { base ->
            runBlocking { ServerHealthCheck.check(base) }
        }
    }

    @Test fun healthCheckRejectsHtmlAndHttpErrors() {
        withHealthResponse(200, "<html>Not the podcast backend</html>") { base ->
            assertThrows(IllegalStateException::class.java) { runBlocking { ServerHealthCheck.check(base) } }
        }
        withHealthResponse(503, """{"status":"ok"}""") { base ->
            assertThrows(IllegalStateException::class.java) { runBlocking { ServerHealthCheck.check(base) } }
        }
    }

    @Test fun healthCheckAsksForFinalApiAddressOnRedirect() {
        withHealthResponse(307, "") { base ->
            val error = assertThrows(IllegalStateException::class.java) { runBlocking { ServerHealthCheck.check(base) } }
            assertEquals("服务器要求跳转，请填写最终 HTTP(S) API 地址", error.message)
        }
    }

    private fun withHealthResponse(status: Int, body: String, block: (String) -> Unit) {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 5_000
            val response = FutureTask<Unit> {
                server.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val input = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                    assertEquals("GET /api/v1/healthz HTTP/1.1", input.readLine())
                    val headers = generateSequence { input.readLine()?.takeIf { it.isNotEmpty() } }.toList()
                    check(headers.none { it.startsWith("Authorization:", ignoreCase = true) })
                    val bytes = body.toByteArray()
                    socket.getOutputStream().apply {
                        val headersText = "HTTP/1.1 $status Response\r\nContent-Length: " + bytes.size + "\r\nConnection: close\r\n\r\n"
                        write(headersText.toByteArray())
                        write(bytes)
                        flush()
                    }
                }
            }
            Thread(response, "health-check-fixture").apply { isDaemon = true }.start()
            block(requireNotNull(ServerAddress.optional("http://127.0.0.1:" + server.localPort)))
            response.get()
        }
    }
}
