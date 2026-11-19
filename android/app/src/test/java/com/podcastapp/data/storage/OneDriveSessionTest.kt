package com.podcastapp.data.storage

import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Timeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OneDriveSessionTest {
    @Test
    fun pendingKeepsDeviceCodeVisibleUntilAuthorizationSucceeds() = runBlocking {
        val fixture = Fixture(deviceCode(), oauthError("authorization_pending"), token())

        fixture.session.authorize(" $CLIENT_ID ")

        val shownCode = OneDriveAuthorization.DeviceCode("ABCD-EFGH", VERIFICATION_URL.toHttpUrl())
        assertEquals(listOf(shownCode, shownCode), fixture.statesAtWait)
        assertEquals(listOf(2_000L, 2_000L), fixture.waits)
        assertTrue(fixture.session.authorization.value is OneDriveAuthorization.Connected)
        val (challenge, pending, retry) = fixture.http.requests
        assertEquals(DEVICE_CODE_URL, challenge.url.toString())
        assertEquals(
            mapOf("client_id" to CLIENT_ID, "scope" to "https://graph.microsoft.com/Files.Read offline_access"),
            challenge.formFields(),
        )
        for (request in listOf(pending, retry)) {
            assertEquals(TOKEN_URL, request.url.toString())
            assertEquals(
                mapOf(
                    "client_id" to CLIENT_ID,
                    "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                    "device_code" to "fake-device-code",
                ),
                request.formFields(),
            )
        }
        assertEquals(3, fixture.http.requests.size)
    }

    @Test
    fun pendingExpiresAtLocalDeadlineWithoutAnotherTokenRequest() = runBlocking {
        val fixture = Fixture(deviceCode(expiresIn = 3), oauthError("authorization_pending"))

        fixture.session.authorize(CLIENT_ID)

        assertEquals(OneDriveAuthorization.Expired, fixture.session.authorization.value)
        assertEquals(listOf(2_000L, 1_000L), fixture.waits)
        assertEquals(listOf(DEVICE_CODE_URL, TOKEN_URL), fixture.http.requests.map { it.url.toString() })
    }

    @Test
    fun serverExpiredTokenStopsPolling() = assertTerminalOAuthError("expired_token", OneDriveAuthorization.Expired)

    @Test
    fun serverDeclinedAuthorizationStopsPolling() =
        assertTerminalOAuthError("authorization_declined", OneDriveAuthorization.Denied)

    @Test
    fun listsTwoPagesUsingTheOpaqueGraphCursor() = runBlocking {
        val folderUrl = "https://graph.microsoft.com/v1.0/me/drive/items/folder%2Fid/children"
        val nextUrl = "$folderUrl?%24skiptoken=opaque%2Btoken%2Fpart%3D"
        val fixture = Fixture(
            deviceCode(), token(),
            Reply(body = """
                {"value":[
                    {"id":"a","name":"one.MP3","file":{"mimeType":"application/octet-stream"}},
                    {"id":"d","name":"Subfolder","folder":{}},
                    {"id":"x","name":"notes.txt","file":{"mimeType":"text/plain"}}
                ],"@odata.nextLink":"$nextUrl"}
            """.trimIndent()),
            Reply(body = """
                {"value":[{"id":"b","name":"two","file":{"mimeType":"audio/ogg; codecs=opus"}}]}
            """.trimIndent()),
        )
        val generation = fixture.connect()

        val first = fixture.session.list(generation, "folder/id")
        val second = fixture.session.list(generation, "folder/id", checkNotNull(first.next))

        assertEquals(listOf(OneDriveItem("d", "Subfolder", true, null), OneDriveItem("a", "one.MP3", false, null)), first.items)
        assertEquals(listOf(OneDriveItem("b", "two", false, "audio/ogg")), second.items)
        assertNull(second.next)
        val graph = fixture.http.requests.drop(2)
        assertEquals(2, graph.size)
        assertEquals(folderUrl, graph[0].url.newBuilder().query(null).build().toString())
        assertEquals("id,name,folder,file", graph[0].url.queryParameter("\$select"))
        assertEquals("50", graph[0].url.queryParameter("\$top"))
        assertEquals(nextUrl, graph[1].url.toString())
        assertEquals(listOf("Bearer fake-access", "Bearer fake-access"), graph.map { it.header("Authorization") })
    }

    @Test
    fun rejectsNonGraphPaginationLinkBeforeItCanReceiveBearer() = runBlocking {
        val fixture = Fixture(
            deviceCode(), token(),
            Reply(body = """{"value":[],"@odata.nextLink":"https://other.example/v1.0/me/drive/root/children"}"""),
        )
        val generation = fixture.connect()

        val failure = runCatching { fixture.session.list(generation, null) }.exceptionOrNull()

        assertTrue("Expected OneDriveError, got $failure", failure is OneDriveError)
        assertEquals(listOf("login.microsoftonline.com", "login.microsoftonline.com", "graph.microsoft.com"),
            fixture.http.requests.map { it.url.host })
    }

    @Test
    fun graph401RefreshesAndRetriesExactlyOnceWithNewBearer() = runBlocking {
        val fixture = Fixture(
            deviceCode(), token(), Reply(401, "not JSON"), token("fresh-fake-access"),
            Reply(body = """{"value":[]}"""),
        )
        val generation = fixture.connect()

        assertTrue(fixture.session.list(generation, null).items.isEmpty())

        val followUp = fixture.http.requests.drop(2)
        assertEquals(3, followUp.size)
        val (rejected, refresh, retried) = followUp
        assertEquals("graph.microsoft.com", rejected.url.host)
        assertEquals(rejected.url, retried.url)
        assertEquals("Bearer fake-access", rejected.header("Authorization"))
        assertEquals("Bearer fresh-fake-access", retried.header("Authorization"))
        assertEquals(TOKEN_URL, refresh.url.toString())
        assertEquals(
            mapOf("client_id" to CLIENT_ID, "grant_type" to "refresh_token", "refresh_token" to "fake-refresh"),
            refresh.formFields(),
        )
        assertEquals(OneDriveAuthorization.Connected(generation), fixture.session.authorization.value)
    }

    @Test
    fun graphBearerNeverReachesCdnOrItsRedirect() = runBlocking {
        val cdnUrl = "https://cdn.example/audio?sig=fake-signature"
        val redirectedUrl = "https://media.example/episode?sig=another-fake-signature"
        val fixture = Fixture(
            deviceCode(), token(), Reply(302, location = cdnUrl),
            Reply(307, location = redirectedUrl), Reply(206, "audio"),
        )
        val generation = fixture.connect()
        val downloads = fixture.session.downloads(generation, "episode")

        try {
            downloads.open(4, 5).response.use { response ->
                assertEquals(206, response.code)
                assertEquals("audio", response.body?.string())
            }
        } finally {
            downloads.cancel()
        }

        val content = fixture.http.requests.drop(2)
        val graphContentUrl = "https://graph.microsoft.com/v1.0/me/drive/items/episode/content"
        assertEquals(listOf(graphContentUrl, cdnUrl, redirectedUrl), content.map { it.url.toString() })
        assertEquals("Bearer fake-access", content.first().header("Authorization"))
        for (request in content.drop(1)) {
            assertNull(request.header("Authorization"))
            assertEquals("bytes=4-8", request.header("Range"))
        }
    }

    private fun assertTerminalOAuthError(error: String, state: OneDriveAuthorization) = runBlocking {
        val fixture = Fixture(deviceCode(), oauthError(error))

        fixture.session.authorize(CLIENT_ID)

        assertEquals(state, fixture.session.authorization.value)
        assertEquals(listOf(2_000L), fixture.waits)
        assertEquals(listOf(DEVICE_CODE_URL, TOKEN_URL), fixture.http.requests.map { it.url.toString() })
    }

    private fun Request.formFields(): Map<String, String> {
        assertEquals("POST", method)
        assertNull(header("Authorization"))
        val form = body as FormBody
        return (0 until form.size).associate { form.name(it) to form.value(it) }
    }

    private class Fixture(vararg replies: Reply) {
        val http = ScriptedCalls(replies.toList())
        val waits = mutableListOf<Long>()
        val statesAtWait = mutableListOf<OneDriveAuthorization>()
        private var now = 0L
        val session = OneDriveSession(http, nowMs = { now }, waitMs = ::advanceClock)

        private fun advanceClock(duration: Long) {
            waits += duration
            statesAtWait += session.authorization.value
            now += duration
        }

        suspend fun connect(): Long {
            session.authorize(CLIENT_ID)
            return (session.authorization.value as OneDriveAuthorization.Connected).generation
        }
    }

    private data class Reply(val code: Int = 200, val body: String = "", val location: String? = null)

    /** Delivers responses inline, with no sockets, background threads or real polling delay. */
    private class ScriptedCalls(replies: List<Reply>) : Call.Factory {
        private val pending = ArrayDeque(replies)
        val requests = mutableListOf<Request>()

        override fun newCall(request: Request): Call = object : Call {
            private val timeout = Timeout()
            private var executed = false
            private var canceled = false

            override fun request(): Request = request
            override fun timeout(): Timeout = timeout
            override fun isExecuted(): Boolean = executed
            override fun isCanceled(): Boolean = canceled
            override fun cancel() { canceled = true }
            override fun clone(): Call = newCall(request)

            override fun execute(): Response {
                check(!executed)
                executed = true
                if (canceled) throw IOException("Canceled")
                requests += request
                val reply = pending.removeFirstOrNull() ?: throw AssertionError("Unexpected request: "+ request.url)
                return Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                    .code(reply.code).message("Scripted response").body(reply.body.toResponseBody())
                    .apply { reply.location?.let { header("Location", it) } }.build()
            }

            override fun enqueue(responseCallback: Callback) {
                val response = try {
                    execute()
                } catch (e: IOException) {
                    responseCallback.onFailure(this, e)
                    return
                }
                responseCallback.onResponse(this, response)
            }
        }
    }

    private companion object {
        const val CLIENT_ID = "00000000-0000-4000-8000-000000000001"
        const val VERIFICATION_URL = "https://www.microsoft.com/devicelogin"
        const val OAUTH_BASE = "https://login.microsoftonline.com/common/oauth2/v2.0/"
        const val DEVICE_CODE_URL = OAUTH_BASE + "devicecode"
        const val TOKEN_URL = OAUTH_BASE + "token"

        fun deviceCode(expiresIn: Int = 60) = Reply(body = """
            {"device_code":"fake-device-code","user_code":"ABCD-EFGH","verification_uri":"$VERIFICATION_URL",
             "expires_in":$expiresIn,"interval":2}
        """.trimIndent())

        fun oauthError(error: String) = Reply(400, """{"error":"$error"}""")

        fun token(access: String = "fake-access") = Reply(body = """
            {"access_token":"$access","refresh_token":"fake-refresh","token_type":"Bearer","expires_in":3600}
        """.trimIndent())
    }
}
