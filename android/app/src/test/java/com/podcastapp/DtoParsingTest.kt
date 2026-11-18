package com.podcastapp.data.remote

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DtoParsingTest {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Test
    fun parsesAuthResponseWithUnknownFields() {
        val raw = """
            {"user":{"id":"u1","email":"a@b.com","displayName":"甲","role":"admin","createdAt":"2024-01-01T00:00:00Z","extra":1},
             "token":{"accessToken":"aaa","refreshToken":"bbb","expiresIn":900,"tokenType":"Bearer"}}
        """.trimIndent()
        val res = json.decodeFromString<AuthResponse>(raw)
        assertEquals("u1", res.user.id)
        assertEquals("admin", res.user.role)
        assertEquals("bbb", res.token.refreshToken)
    }

    @Test
    fun parsesEpisodeList() {
        val raw = """
            {"items":[{"id":"e1","podcastId":"p1","title":"第一集","audioUrl":"https://x/1.mp3",
                       "durationSeconds":1530,"publishedAt":"2024-11-12T10:00:00Z","imageUrl":""}],
             "total":1,"limit":30,"offset":0}
        """.trimIndent()
        val list = json.decodeFromString<EpisodeList>(raw)
        assertEquals(1, list.items.size)
        assertEquals(1530, list.items[0].durationSeconds)
    }

    @Test
    fun parsesApiErrorBody() {
        val err = json.decodeFromString<ApiErrorBody>("""{"code":"invalid_credentials","message":"邮箱或密码错误"}""")
        assertEquals("invalid_credentials", err.code)
        assertEquals("邮箱或密码错误", err.message)
    }

    @Test
    fun encodesSyncRequestWithNullsOmitted() {
        val body = json.encodeToString(
            SyncRequest.serializer(),
            SyncRequest(items = listOf(SyncItem(episodeId = "e1", positionMs = 1000, durationMs = 2000, starred = true))),
        )
        assertTrue(body.contains("\"episodeId\":\"e1\""))
        assertTrue(body.contains("\"starred\":true"))
    }
}
