package com.podcastapp

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.podcastapp.data.Session
import com.podcastapp.data.SessionStore
import com.podcastapp.data.remote.ApiClient
import com.podcastapp.data.remote.LogoutRequest
import com.podcastapp.data.repository.AuthRepository
import com.podcastapp.data.repository.LogoutResult
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import retrofit2.HttpException

class AuthSessionTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun logout401DoesNotRefreshAndStillClearsLocally() = withFixture { f ->
        val before = f.login()
        f.serve({ request ->
            assertEquals("/api/v1/auth/logout", request.path)
            assertEquals("Bearer access-old", request.authorization)
            assertTrue(request.body.contains("\"refreshToken\":\"refresh-old\""))
            runBlocking { assertFalse(f.store.current().isLoggedIn) }
            reply("401 Unauthorized")
        })
        assertEquals(LogoutResult.LOCAL_ONLY, f.repo.logout(before))
        assertFalse(f.store.current().isLoggedIn)
        assertEquals(0, f.refreshCalls.get())
        assertEquals(0, f.expired.get())
    }

    @Test fun genericApiLogoutAlsoBypassesAuthenticator() = withFixture { f ->
        f.login()
        f.serve({ request ->
            assertEquals("/api/v1/auth/logout", request.path)
            reply("401 Unauthorized")
        })
        assertEquals(401, status { f.client.api.logout(LogoutRequest("refresh-old")) })
        assertEquals(0, f.refreshCalls.get())
    }

    @Test fun logoutUsesRotatedPairFromInFlightRefresh() = withFixture { f ->
        val before = f.login()
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        f.serve({ request ->
            assertEquals("/api/v1/auth/refresh", request.path)
            started.complete(Unit)
            runBlocking { withTimeout(5_000) { finish.await() } }
            reply("200 OK", rotated)
        }, { request ->
            assertEquals("/api/v1/auth/logout", request.path)
            assertEquals("Bearer access-new", request.authorization)
            assertTrue(request.body.contains("\"refreshToken\":\"refresh-new\""))
            reply("200 OK", "{\"status\":\"ok\"}")
        })
        val refreshed = async { f.repo.refreshTokens(f.client, null) }
        started.await()
        val loggedOut = async(start = CoroutineStart.UNDISPATCHED) { f.repo.logout(before) }
        finish.complete(Unit)
        assertTrue(refreshed.await())
        assertEquals(LogoutResult.REMOTE_CONFIRMED, loggedOut.await())
        assertFalse(f.store.current().isLoggedIn)
    }

    @Test fun stalledRefreshCannotBlockOrResurrectLocalLogout() = withFixture { f ->
        val before = f.login()
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        f.serve({ request ->
            assertEquals("/api/v1/auth/refresh", request.path)
            started.complete(Unit)
            runBlocking { withTimeout(5_000) { finish.await() } }
            reply("200 OK", rotated)
        })
        val refreshed = async { f.repo.refreshTokens(f.client, null) }
        started.await()
        assertEquals(LogoutResult.LOCAL_ONLY, withTimeout(2_500) { f.repo.logout(before) })
        assertFalse(f.store.current().isLoggedIn)
        finish.complete(Unit)
        assertFalse(refreshed.await())
        assertFalse(f.store.current().isLoggedIn)
    }

    @Test fun logoutTimesOutAfterLocalSessionIsCleared() = withFixture { f ->
        val before = f.login()
        val received = CompletableDeferred<Unit>()
        f.serve({ request ->
            assertEquals("/api/v1/auth/logout", request.path)
            received.complete(Unit)
            assertEquals(-1, getInputStream().read())
        })
        val loggedOut = async { f.repo.logout(before) }
        received.await()
        assertFalse(f.store.current().isLoggedIn)
        assertEquals(LogoutResult.LOCAL_ONLY, withTimeout(4_000) { loggedOut.await() })
    }

    @Test fun staleLogoutDoesNotClearReloginOrNewServer() = withFixture { f ->
        val before = f.login()
        val relogin = f.login() // Same account and tokens, but a different login.
        assertEquals(LogoutResult.UNCHANGED, f.repo.logout(before))
        assertEquals(relogin, f.store.current())
        val newBase = "http://127.0.0.1:2/api/v1/"
        f.store.setCustomBaseUrl(newBase)
        val switched = f.login(base = newBase)
        assertEquals(LogoutResult.UNCHANGED, f.repo.logout(relogin))
        assertEquals(switched, f.store.current())
    }

    @Test fun refresh503AndConnectionLossKeepAuthAndDoNotReportExpiry() = withFixture { f ->
        val before = f.login()
        f.serve({ request ->
            assertEquals("/api/v1/me", request.path)
            reply("401 Unauthorized")
        }, { request ->
            assertEquals("/api/v1/auth/refresh", request.path)
            reply("503 Service Unavailable")
        }, { request ->
            assertEquals("/api/v1/auth/refresh", request.path)
            // Close the socket without responding.
        })
        assertEquals(401, status { f.client.api.me() })
        assertEquals(before, f.store.current())
        assertFalse(f.repo.refreshTokens(f.client, null))
        assertEquals(before, f.store.current())
        assertEquals(0, f.expired.get())
    }

    @Test fun refresh401ClearsMatchingSessionAndReportsExpiry() = withFixture { f ->
        f.login()
        f.serve({ request ->
            assertEquals("/api/v1/auth/refresh", request.path)
            reply("401 Unauthorized")
        })
        assertFalse(f.repo.refreshTokens(f.client, null))
        assertFalse(f.store.current().isLoggedIn)
        assertEquals(1, f.expired.get())
    }

    @Test fun lateRefresh401DoesNotClearReplacementLogin() = withFixture { f ->
        f.login()
        val next = CompletableDeferred<Session>()
        f.serve({ request ->
            assertEquals("/api/v1/auth/refresh", request.path)
            next.complete(runBlocking { f.login(access = "access-other", refresh = "refresh-other", user = "other") })
            reply("401 Unauthorized")
        })
        assertFalse(f.repo.refreshTokens(f.client, null))
        assertEquals(next.await(), f.store.current())
        assertEquals(0, f.expired.get())
    }

    private fun withFixture(test: suspend CoroutineScope.(Fixture) -> Unit) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            ServerSocket(0, 5, InetAddress.getByName("127.0.0.1")).use { listener ->
                listener.soTimeout = 6_000
                val base = "http://127.0.0.1:" + listener.localPort + "/api/v1/"
                val data = PreferenceDataStoreFactory.create(scope = scope) { tmp.root.resolve("session.preferences_pb") }
                val fixture = Fixture(listener, SessionStore(data, base), base)
                withTimeout(8_000) { test(fixture) }
                fixture.served?.get(6, TimeUnit.SECONDS)
            }
        } finally {
            scope.cancel()
        }
        Unit
    }

    private class Fixture(private val listener: ServerSocket, val store: SessionStore, private val base: String) {
        val expired = AtomicInteger()
        val refreshCalls = AtomicInteger()
        val client = ApiClient(base, { runBlocking { store.current().accessToken } }, { expired.incrementAndGet() }, false)
        val repo = AuthRepository(store, { client }, { "test-device" })
        var served: FutureTask<Unit>? = null
            private set

        init { client.tokenRefresher = { refreshCalls.incrementAndGet(); repo.refreshTokens(client, it) } }

        suspend fun login(access: String = "access-old", refresh: String = "refresh-old", user: String = "user", base: String = this.base): Session {
            assertTrue(store.saveAuth(access, refresh, user, "", "", "user", base))
            return store.current()
        }

        fun serve(vararg actions: Socket.(Request) -> Unit) {
            check(served == null)
            val task = FutureTask<Unit> {
                for (action in actions) listener.accept().use { socket ->
                    socket.soTimeout = 6_000
                    action(socket, socket.readRequest())
                }
            }
            served = task
            Thread(task, "auth-session-fixture").apply { isDaemon = true }.start()
        }
    }

    private data class Request(val path: String, val authorization: String?, val body: String)

    private companion object {
        const val rotated = """{"user":{"id":"user"},"token":{"accessToken":"access-new","refreshToken":"refresh-new"}}"""

        suspend fun status(block: suspend () -> Any): Int = try {
            block()
            200
        } catch (e: HttpException) {
            e.code()
        }

        fun Socket.readRequest(): Request {
            val input = getInputStream().bufferedReader(Charsets.US_ASCII)
            val path = input.readLine().split(' ')[1]
            val headers = generateSequence { input.readLine()?.takeIf { it.isNotEmpty() } }.toList()
            fun header(name: String) = headers.firstOrNull { it.startsWith("$name:", ignoreCase = true) }?.substringAfter(':')?.trim()
            val body = buildString {
                repeat(header("Content-Length")?.toInt() ?: 0) { append(input.read().also { check(it >= 0) }.toChar()) }
            }
            return Request(path, header("Authorization"), body)
        }

        fun Socket.reply(status: String, body: String = "{}") {
            val bytes = body.toByteArray()
            getOutputStream().apply {
                write(("HTTP/1.1 $status\r\nContent-Type: application/json\r\nContent-Length: " + bytes.size + "\r\nConnection: close\r\n\r\n").toByteArray())
                write(bytes)
                flush()
            }
        }
    }
}
