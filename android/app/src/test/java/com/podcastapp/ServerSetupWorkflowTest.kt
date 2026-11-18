package com.podcastapp.data.serversetup

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.podcastapp.data.SessionStore
import com.podcastapp.data.remote.LoginRequest
import java.io.File
import java.net.InetAddress
import java.net.Socket
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import java.util.concurrent.FutureTask
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ServerSetupWorkflowTest {
    @get:Rule val temp = TemporaryFolder()
    private val oldUrl = "https://old.example/api/v1/"
    private val loginResult = """{"user":{"id":"imported-user","email":"test@example.com"},"token":{"accessToken":"new-access","refreshToken":"new-refresh"}}"""

    @Test fun acceptsHttpsLoginAndNeverFollowsRedirect() = withStore { store ->
        val keys = testKeys()
        val keyManager = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        val fixturePassword = "test-passphrase".toCharArray()
        keyManager.init(keys, fixturePassword)
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        trust.init(keys)
        val serverTls = SSLContext.getInstance("TLS").apply { init(keyManager.keyManagers, null, null) }
        val clientTls = SSLContext.getInstance("TLS").apply { init(null, trust.trustManagers, null) }
        val builder = OkHttpClient.Builder()
            .sslSocketFactory(clientTls.socketFactory, trust.trustManagers.filterIsInstance<X509TrustManager>().single())
        serverTls.serverSocketFactory.createServerSocket(0, 2, InetAddress.getByName("127.0.0.1")).use { listener ->
            listener.soTimeout = 10_000
            val replies = FutureTask<Unit> {
                listener.accept().use { socket ->
                    assertEquals(expectedRequest, socket.readLogin("/redirect/auth/login"))
                    socket.respond("307 Temporary Redirect", "", "Location: /unexpected/\r\n")
                }
                listener.accept().use { socket ->
                    // A followed redirect here would consume the next socket with an unexpected path.
                    assertEquals(expectedRequest, socket.readLogin("/api/v1/auth/login"))
                    socket.respond("200 OK", loginResult)
                }
            }
            Thread(replies, "server-setup-tls-fixture").apply { isDaemon = true }.start()
            val host = "https://127.0.0.1:" + listener.localPort
            val activated = mutableListOf<String>()
            val setup = ServerSetup(store, { session, _ -> activated.add(session.baseUrl) }, ServerSetupLogin(builder))
            val original = store.current()
            val redirect = assertThrows(IllegalStateException::class.java) {
                runBlocking { setup.signIn(link(host + "/redirect/"), "test-device") }
            }
            assertEquals("服务器要求重定向，请使用最终的 HTTPS API 地址", redirect.message)
            assertEquals(original, store.current())
            assertEquals(emptyList<String>(), activated)
            val imported = setup.signIn(link(host), "test-device")
            assertEquals(host + "/api/v1/", imported.baseUrl)
            assertEquals("new-access", imported.accessToken)
            assertEquals("imported-user", imported.userId)
            assertNotNull(imported.importedConfigId)
            assertEquals(imported, store.current())
            assertEquals(listOf(imported.baseUrl), activated)
            replies.get(12, TimeUnit.SECONDS)
        }
    }

    @Test fun rejectedLoginPreservesConfigurationAndDoesNotEchoServerMessage() = withStore { store ->
        val original = store.current()
        val builder = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).code(401).protocol(Protocol.HTTP_1_1)
                .message("fake+password").body("{\"message\":\"fake+password\"}".toResponseBody()).build()
        }
        val setup = ServerSetup(store, { _, _ -> error("must not activate") }, ServerSetupLogin(builder))
        val rejected = assertThrows(IllegalStateException::class.java) {
            runBlocking { setup.signIn(link("https://new.example"), "test-device") }
        }
        assertEquals("登录验证失败，请检查账号密码", rejected.message)
        assertNull(rejected.cause)
        assertEquals(original, store.current())
    }

    @Test fun inFlightVerificationCannotOverwriteNewerConfig() = withStore { store ->
        val later = "https://selected-elsewhere.example/api/v1/"
        val builder = OkHttpClient.Builder().addInterceptor { chain ->
            runBlocking { store.setCustomBaseUrl(later) }
            Response.Builder().request(chain.request()).code(200).protocol(Protocol.HTTP_1_1)
                .message("OK").body(loginResult.toResponseBody("application/json".toMediaType())).build()
        }
        val setup = ServerSetup(store, { _, _ -> error("must not activate") }, ServerSetupLogin(builder))
        assertThrows(IllegalStateException::class.java) {
            runBlocking { setup.signIn(link("https://new.example"), "test-device") }
        }
        val current = store.current()
        assertEquals(later, current.baseUrl)
        assertNotEquals("new-access", current.accessToken)
    }

    private fun link(base: String) = ServerSetupLink.parse(base + "#username=test%40example.com&password=fake+password")

    private fun withStore(body: suspend (SessionStore) -> Unit) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = SessionStore(PreferenceDataStoreFactory.create(scope = scope) {
                temp.root.resolve("session.preferences_pb")
            }, "")
            store.setCustomBaseUrl(oldUrl)
            check(store.saveAuth("old-access", "old-refresh", "old-user", "old@example.com", "old", "user", oldUrl))
            body(store)
        } finally {
            scope.cancel()
        }
    }

    private val expectedRequest = LoginRequest("test@example.com", "fake+password", "test-device")

    private fun Socket.readLogin(path: String): LoginRequest {
        soTimeout = 8_000
        val input = getInputStream().bufferedReader(Charsets.UTF_8)
        assertEquals("POST $path HTTP/1.1", input.readLine())
        val headers = generateSequence { input.readLine()?.takeIf { it.isNotEmpty() } }.toList()
        check(headers.none { it.startsWith("Authorization:", ignoreCase = true) })
        val length = headers.single { it.startsWith("Content-Length:", ignoreCase = true) }
            .substringAfter(':').trim().toInt()
        check(length in 1..2048)
        val payload = buildString { repeat(length) { append(input.read().also { check(it >= 0) }.toChar()) } }
        return Json.decodeFromString<LoginRequest>(payload)
    }

    private fun Socket.respond(status: String, body: String, extraHeaders: String = "") {
        val bytes = body.encodeToByteArray()
        getOutputStream().apply {
            write(("HTTP/1.1 $status\r\n" + extraHeaders + "Content-Type: application/json\r\n" +
                "Content-Length: " + bytes.size + "\r\nConnection: close\r\n\r\n").encodeToByteArray())
            write(bytes)
            flush()
        }
    }

    private fun testKeys(): KeyStore {
        val file = temp.root.resolve("local-test.p12")
        val keytool = File(System.getProperty("java.home"), "bin/keytool")
        val process = ProcessBuilder(keytool.path, "-genkeypair", "-alias", "fixture", "-keyalg", "EC",
            "-dname", "CN=localhost", "-ext", "SAN=IP:127.0.0.1", "-validity", "1",
            "-storetype", "PKCS12", "-keystore", file.path, "-storepass", "test-passphrase")
            .redirectErrorStream(true).start()
        try {
            check(process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0) {
                if (process.isAlive) "keytool exceeded fixture deadline" else process.inputStream.bufferedReader().readText()
            }
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
        return KeyStore.getInstance("PKCS12").apply { file.inputStream().use { load(it, "test-passphrase".toCharArray()) } }
    }
}
