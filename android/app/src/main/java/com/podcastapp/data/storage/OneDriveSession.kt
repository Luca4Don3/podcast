package com.podcastapp.data.storage

import java.io.IOException
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import okhttp3.Call
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request

internal sealed interface OneDriveAuthorization {
    data object Disconnected : OneDriveAuthorization
    data object Starting : OneDriveAuthorization
    data object Cancelled : OneDriveAuthorization
    data object Expired : OneDriveAuthorization
    data object Denied : OneDriveAuthorization
    data object SignInAgain : OneDriveAuthorization
    data class Failure(val message: String) : OneDriveAuthorization
    data class DeviceCode(val userCode: String, val verificationUrl: HttpUrl) : OneDriveAuthorization
    data class Connected(val generation: Long) : OneDriveAuthorization
}

private const val SCOPE = "https://graph.microsoft.com/Files.Read offline_access"
private const val DEVICE_CODE_GRANT = "urn:ietf:params:oauth:grant-type:device_code"
private val appIdPattern = Regex("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}")
internal fun validOneDriveAppId(value: String): Boolean = appIdPattern.matches(value.trim())

/** Credentials only live in this object in the current app process; never exposed as UI state. */
internal class OneDriveSession(
    private val calls: Call.Factory = oneDriveHttpClient(),
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val waitMs: suspend (Long) -> Unit = { delay(it) },
) {
    private class Tokens(val clientId: String, val access: String, val refresh: String?, val expiresAt: Long)
    private class Challenge(val deviceCode: String, val display: OneDriveAuthorization.DeviceCode, val lifetime: Long, val interval: Long)
    private val lock = Any()
    private val refreshLock = Mutex()
    private var generation = 0L
    private var tokens: Tokens? = null
    private val _authorization = MutableStateFlow<OneDriveAuthorization>(OneDriveAuthorization.Disconnected)
    val authorization = _authorization.asStateFlow()

    suspend fun authorize(id: String) {
        val clientId = id.trim()
        val attempt = synchronized(lock) {
            if (tokens != null || _authorization.value is OneDriveAuthorization.DeviceCode ||
                _authorization.value == OneDriveAuthorization.Starting) return
            if (!validOneDriveAppId(clientId)) {
                _authorization.value = OneDriveAuthorization.Failure("请输入有效的 Microsoft 应用程序（客户端）ID。")
                return
            }
            _authorization.value = OneDriveAuthorization.Starting
            ++generation
        }
        try {
            val response = oauth("devicecode", "client_id" to clientId, "scope" to SCOPE)
            if (!response.successful) throw OneDriveError("无法请求设备码，请检查应用 ID 与公共客户端设置。")
            val challenge = parseChallenge(response.json)
            val deadline = nowMs() + challenge.lifetime
            update(attempt, challenge.display)
            val completed = withTimeoutOrNull(challenge.lifetime) { poll(attempt, clientId, challenge, deadline) }
            if (completed == null) update(attempt, OneDriveAuthorization.Expired)
        } catch (e: CancellationException) {
            update(attempt, OneDriveAuthorization.Cancelled)
            throw e
        } catch (e: IOException) {
            update(attempt, OneDriveAuthorization.Failure(e.message ?: "无法授权 OneDrive。"))
        }
    }

    fun disconnect() = synchronized(lock) {
        ++generation
        tokens = null
        _authorization.value = OneDriveAuthorization.Disconnected
    }

    private fun update(attempt: Long, state: OneDriveAuthorization) = synchronized(lock) {
        if (attempt == generation) _authorization.value = state
    }

    private suspend fun poll(attempt: Long, clientId: String, challenge: Challenge, deadline: Long) {
        var interval = challenge.interval
        var networkBackoff = interval
        while (true) {
            val remaining = deadline - nowMs()
            if (remaining <= 0) break
            waitMs(min(maxOf(interval, networkBackoff), remaining))
            currentCoroutineContext().ensureActive()
            if (nowMs() >= deadline) break
            val response = try {
                oauth("token", "client_id" to clientId, "grant_type" to DEVICE_CODE_GRANT, "device_code" to challenge.deviceCode)
            } catch (_: OneDriveNetworkError) {
                networkBackoff = (networkBackoff * 2).coerceAtMost(maxOf(interval, 60_000L))
                continue
            }
            // Do not publish a token received after the device code deadline or cancellation.
            currentCoroutineContext().ensureActive()
            if (nowMs() >= deadline) break
            if (response.successful) {
                val fresh = response.json.parseTokens(clientId)
                synchronized(lock) {
                    if (attempt == generation) {
                        tokens = fresh
                        _authorization.value = OneDriveAuthorization.Connected(attempt)
                    }
                }
                return
            }
            when (response.json.string("error")) {
                "authorization_pending" -> networkBackoff = interval
                "slow_down" -> { interval += 5_000; networkBackoff = interval }
                "authorization_declined", "access_denied" -> { update(attempt, OneDriveAuthorization.Denied); return }
                "expired_token", "code_expired" -> break
                else -> if (response.status == 429 || response.status in 500..599) {
                    networkBackoff = (networkBackoff * 2).coerceAtMost(maxOf(interval, 60_000L))
                } else {
                    throw OneDriveError("设备码授权失败，请检查 Microsoft 应用设置或重新发起授权。")
                }
            }
        }
        update(attempt, OneDriveAuthorization.Expired)
    }

    private suspend fun tokenFor(attempt: Long, rejected: Tokens? = null): Tokens = refreshLock.withLock {
        val current = synchronized(lock) { tokens.takeIf { generation == attempt } }
            ?: throw OneDriveError("请重新连接 OneDrive。")
        if (current !== rejected && current.expiresAt > nowMs()) return@withLock current
        val refresh = current.refresh ?: signInAgain(attempt, current)
        val response = oauth("token", "client_id" to current.clientId, "grant_type" to "refresh_token", "refresh_token" to refresh)
        if (!response.successful) {
            if (response.json.string("error") in setOf("invalid_grant", "interaction_required", "invalid_client", "unauthorized_client")) {
                signInAgain(attempt, current)
            }
            throw OneDriveError("无法刷新 OneDrive 授权，请稍后重试。")
        }
        val fresh = response.json.parseTokens(current.clientId, refresh)
        synchronized(lock) {
            if (attempt != generation) throw OneDriveError("OneDrive 已断开连接。")
            tokens = fresh
        }
        fresh
    }

    private fun signInAgain(attempt: Long, rejected: Tokens): Nothing {
        synchronized(lock) {
            if (attempt == generation && tokens === rejected) {
                tokens = null
                _authorization.value = OneDriveAuthorization.SignInAgain
            }
        }
        throw OneDriveError("OneDrive 授权已失效，请重新连接。")
    }

    /** One 401 retry at most. All bearer-bearing requests stay on the official Graph origin. */
    private suspend fun <T> graph(attempt: Long, url: HttpUrl, request: suspend (Request) -> Pair<Int, T>): Pair<Int, T> {
        if (!url.isOneDriveChildrenUrl() && (!url.isPublicHttps() || url.host != oneDriveGraph.host ||
            !url.encodedPath.startsWith(oneDriveGraph.encodedPath))) {
            throw OneDriveError("拒绝未经验证的 OneDrive 请求地址。")
        }
        fun authenticated(token: Tokens) = Request.Builder().url(url).header("Authorization", "Bearer " + token.access).build()
        val original = tokenFor(attempt)
        val first = request(authenticated(original))
        if (first.first != 401) return first
        val fresh = tokenFor(attempt, original)
        val second = request(authenticated(fresh))
        if (second.first == 401) signInAgain(attempt, fresh)
        return second
    }

    suspend fun list(attempt: Long, folderId: String?, nextPage: OneDrivePageCursor? = null): OneDrivePage {
        val url = nextPage?.url ?: driveItemsUrl(folderId, "children").newBuilder()
            .addQueryParameter("\$select", "id,name,folder,file")
            .addQueryParameter("\$top", "50")
            .build()
        val (status, json) = graph(attempt, url) { calls.oneDriveJson(it).let { reply -> reply.status to reply.json } }
        if (status !in 200..299) throw OneDriveError("无法读取 OneDrive 目录（HTTP " + status + "）。")
        return parseOneDrivePage(json)
    }

    fun downloads(attempt: Long, itemId: String) = OneDriveDownloads(calls) {
        val (status, location) = graph(attempt, driveItemsUrl(itemId, "content")) { request ->
            // Graph /content responds 302. Never read a media body here or retain the signed URL.
            calls.oneDriveRequest(request) { it.code to it.header("Location") }
        }
        if (status != 302) throw OneDriveError("无法取得 OneDrive 音频流（HTTP " + status + "）。")
        val url = location?.toHttpUrlOrNull()
        url?.takeIf { it.isPublicHttps() } ?: throw OneDriveError("OneDrive 音频跳转地址无效。")
    }

    private fun parseChallenge(json: JsonObject): Challenge {
        val device = json.string("device_code")?.takeIf(String::isNotBlank)
        val user = json.string("user_code")?.takeIf { it.length in 1..64 && it.all { c -> c in '!'..'~' } }
        val url = json.string("verification_uri")?.toHttpUrlOrNull()?.takeIf {
            it.isPublicHttps() && it.query == null && it.host in setOf("microsoft.com", "www.microsoft.com", "login.microsoftonline.com", "aka.ms")
        }
        val lifetime = json.string("expires_in")?.toLongOrNull()?.takeIf { it in 1..3600 }
        val interval = json.string("interval")?.toLongOrNull()?.takeIf { it in 1..3600 } ?: 5
        if (device == null || user == null || url == null || lifetime == null) {
            throw OneDriveError("Microsoft 返回了无效的设备码。")
        }
        return Challenge(device, OneDriveAuthorization.DeviceCode(user, url), lifetime * 1_000, interval * 1_000)
    }

    private suspend fun oauth(endpoint: String, vararg fields: Pair<String, String>): OneDriveJsonResponse {
        val body = FormBody.Builder().apply { fields.forEach { (key, value) -> add(key, value) } }.build()
        return calls.oneDriveJson(Request.Builder().url(microsoftLogin.resolve(endpoint)!!).post(body).build())
    }

    private fun JsonObject.parseTokens(clientId: String, previousRefresh: String? = null): Tokens {
        val access = string("access_token")?.takeIf { it.isNotEmpty() && it.all { c -> c in '!'..'~' } }
            ?: throw OneDriveError("Microsoft 未返回可用的访问令牌。")
        val lifetime = string("expires_in")?.toLongOrNull()?.takeIf { it in 1..86_400 }
            ?: throw OneDriveError("Microsoft 返回了无效的令牌有效期。")
        if (!string("token_type").equals("Bearer", ignoreCase = true)) throw OneDriveError("不支持 Microsoft 返回的令牌类型。")
        val refresh = string("refresh_token")?.takeIf(String::isNotBlank) ?: previousRefresh
        val expiry = nowMs() + lifetime * 1_000 - min(30_000, lifetime * 100)
        return Tokens(clientId, access, refresh, expiry)
    }
}
