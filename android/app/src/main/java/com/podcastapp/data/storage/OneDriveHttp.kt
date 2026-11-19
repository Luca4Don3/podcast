package com.podcastapp.data.storage

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

internal val oneDriveGraph = "https://graph.microsoft.com/v1.0/me/drive/".toHttpUrl()
internal val microsoftLogin = "https://login.microsoftonline.com/common/oauth2/v2.0/".toHttpUrl()
private const val JSON_LIMIT = 2L * 1024 * 1024

internal fun oneDriveHttpClient() = OkHttpClient.Builder()
    .followRedirects(false)
    .followSslRedirects(false)
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(20, TimeUnit.SECONDS)
    .writeTimeout(15, TimeUnit.SECONDS)
    .build()

internal class OneDriveError(message: String) : IOException(message)
internal class OneDriveNetworkError : IOException("无法连接 OneDrive，请检查网络后重试。")

internal class OneDriveJsonResponse(val status: Int, val json: JsonObject) {
    val successful get() = status in 200..299
}

/** No logging, caching or automatic redirects; tests can inject an in-memory Call.Factory. */
internal suspend fun Call.Factory.oneDriveJson(request: Request): OneDriveJsonResponse =
    oneDriveRequest(request) {
        val body = it.body
        val json = if (body == null || it.code == 401 && request.url.host == oneDriveGraph.host) {
            JsonObject(emptyMap()) // Graph 401 is handled even if the body is absent/non-JSON.
        } else {
            val source = body.source()
            if (source.request(JSON_LIMIT + 1)) throw OneDriveError("OneDrive 响应过大。")
            val text = source.readUtf8()
            val parsed = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
            parsed ?: if (!it.isSuccessful) JsonObject(emptyMap())
            else throw OneDriveError("OneDrive 返回了无效的响应。")
        }
        OneDriveJsonResponse(it.code, json)
    }

internal suspend fun <T> Call.Factory.oneDriveRequest(request: Request, process: (Response) -> T): T =
    suspendCancellableCoroutine { continuation ->
        val call = newCall(request)
        call.timeout().timeout(20, TimeUnit.SECONDS)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(OneDriveNetworkError())
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    continuation.resume(response.use(process))
                } catch (e: IOException) {
                    continuation.resumeWithException(if (e is OneDriveError) e else OneDriveNetworkError())
                }
            }
        })
    }

internal fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull

internal fun HttpUrl.isPublicHttps(): Boolean =
    isHttps && port == 443 && username.isEmpty() && password.isEmpty() && fragment == null

// Drive item IDs, not display paths, are used as URI path segments.
internal fun driveItemsUrl(itemId: String?, operation: String): HttpUrl {
    if (itemId == null) return oneDriveGraph.newBuilder().addPathSegments("root/" + operation).build()
    if (itemId.isEmpty() || itemId.length > 1024 || itemId == "." || itemId == ".." || itemId.any { it.isISOControl() }) {
        throw OneDriveError("OneDrive 条目标识无效。")
    }
    return oneDriveGraph.newBuilder().addPathSegment("items").addPathSegment(itemId)
        .addPathSegment(operation).build()
}
