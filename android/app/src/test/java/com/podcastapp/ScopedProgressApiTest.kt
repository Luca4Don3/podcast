package com.podcastapp

import com.podcastapp.data.ProgressOwner
import com.podcastapp.data.remote.ApiClient
import com.podcastapp.data.remote.SyncRequest
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import retrofit2.HttpException

class ScopedProgressApiTest {
    @Test fun scopedRequestUsesOnlyItsAccountToken() = withServer({ socket ->
        assertEquals("Bearer account-a", socket.readAuthorization())
        socket.respond("200 OK", "{}")
    }) { base ->
        val owner = ProgressOwner(base, "a")
        runBlocking { client(base, { if (it == owner) "account-a" else null }).api.sync(SyncRequest(), owner) }
    }

    @Test fun accountChangeBeforeSendStopsTheUpload() = withServer({ socket ->
        assertNull(socket.getInputStream().bufferedReader().readLine())
    }) { base ->
        val api = client(base, { null }).api
        assertThrows(IOException::class.java) {
            runBlocking { api.sync(SyncRequest(), ProgressOwner(base, "old-account")) }
        }
    }

    @Test fun accountChangeDuring401DoesNotRefreshTheNewAccount() {
        val active = AtomicReference("a")
        withServer({ socket ->
            assertEquals("Bearer account-a", socket.readAuthorization())
            active.set("b")
            socket.respond("401 Unauthorized", "{}")
        }) { base ->
            val client = client(base, { if (it.userId == active.get()) "account-" + active.get() else null })
            val refresh = AtomicReference<ProgressOwner?>()
            client.tokenRefresher = { refresh.set(it); false }
            val status = assertThrows(HttpException::class.java) {
                runBlocking { client.api.sync(SyncRequest(), ProgressOwner(base, "a")) }
            }
            assertEquals(401, status.code())
            assertNull(refresh.get())
        }
    }

    private fun client(base: String, provider: (ProgressOwner) -> String?) = ApiClient(
        baseUrl = base,
        tokenProvider = { "wrong-global-token" },
        onUnauthorized = { error("Unexpected global logout") },
        debug = false,
        ownedTokenProvider = provider,
    )

    private fun withServer(handle: (Socket) -> Unit, test: (String) -> Unit) {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { listener ->
            listener.soTimeout = 5_000
            val result = FutureTask<Unit> {
                listener.accept().use { socket ->
                    socket.soTimeout = 5_000
                    handle(socket)
                }
            }
            Thread(result, "scoped-progress-fixture").apply { isDaemon = true }.start()
            test("http://127.0.0.1:" + listener.localPort + "/api/v1/")
            result.get(6, TimeUnit.SECONDS)
        }
    }

    private fun Socket.readAuthorization(): String? {
        val input = getInputStream().bufferedReader(Charsets.US_ASCII)
        assertEquals("POST /api/v1/sync HTTP/1.1", input.readLine())
        val headers = generateSequence { input.readLine()?.takeIf { it.isNotEmpty() } }.toList()
        val length = headers.first { it.startsWith("Content-Length:", ignoreCase = true) }.substringAfter(':').trim().toInt()
        repeat(length) { check(input.read() >= 0) }
        return headers.firstOrNull { it.startsWith("Authorization:", ignoreCase = true) }?.substringAfter(':')?.trim()
    }

    private fun Socket.respond(status: String, body: String) {
        val bytes = body.toByteArray()
        getOutputStream().apply {
            write(("HTTP/1.1 " + status + "\r\nContent-Type: application/json\r\nContent-Length: " + bytes.size + "\r\nConnection: close\r\n\r\n").toByteArray())
            write(bytes)
            flush()
        }
    }
}
