package com.podcastapp.data.remote

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.podcastapp.data.ProgressOwner
import com.podcastapp.data.ServerAddress
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.Authenticator
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

/**
 * 根据用户配置的服务器地址创建 Retrofit 客户端；地址变更时整体重建。
 * 401 时通过 [tokenRefresher] 自动续期并重放一次请求。
 */
class ApiClient(
    baseUrl: String,
    private val tokenProvider: () -> String?,
    private val onUnauthorized: () -> Unit,
    private val debug: Boolean,
    private val ownedTokenProvider: (ProgressOwner) -> String? = { null },
) {
    /** 由 ServiceLocator 在认证仓库就绪后注入，避免循环依赖。 */
    var tokenRefresher: (suspend (ProgressOwner?) -> Boolean)? = null

    private val endpoint = requireNotNull(ServerAddress.optional(baseUrl)) { "未配置服务器地址" }.toHttpUrl()

    val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    val okHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .authenticator(object : Authenticator {
            override fun authenticate(route: Route?, response: Response): Request? {
                if (responseCount(response) >= 2) return null
                val path = response.request.url.encodedPath
                if (path.endsWith("/auth/refresh") || path.endsWith("/auth/login") ||
                    path.endsWith("/auth/register") || path.endsWith("/auth/logout")) return null
                val owner = response.request.tag(ProgressOwner::class.java)
                if (owner != null && ownedToken(response.request, owner) == null) return null
                val refresher = tokenRefresher ?: return null
                val failedToken = response.request.header("Authorization")?.removePrefix("Bearer ") ?: return null
                val attempt = RefreshAttempt(failedToken)
                val refreshed = runBlocking(attempt) { runCatching { refresher(owner) }.getOrDefault(false) }
                if (!refreshed) return null
                val token = if (owner == null) tokenProvider() else ownedToken(response.request, owner)
                if (token == null || (attempt.newAccessToken != null && attempt.newAccessToken != token)) return null
                return response.request.newBuilder()
                    .header("Authorization", "Bearer " + token)
                    .build()
            }
        })
        .apply {
            if (debug) {
                addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
            }
            addInterceptor { chain ->
                val request = chain.request()
                val owner = request.tag(ProgressOwner::class.java)
                // Scoped requests attach credentials just before each actual network exchange.
                val token = if (owner == null && request.header("Authorization") == null) tokenProvider() else null
                chain.proceed(if (token == null) request else request.newBuilder().header("Authorization", "Bearer " + token).build())
            }
            addNetworkInterceptor { chain ->
                val request = chain.request()
                val owner = request.tag(ProgressOwner::class.java)
                if (owner == null) chain.proceed(request) else {
                    val token = ownedToken(request, owner) ?: throw IOException("进度请求的账号已变更")
                    val response = chain.proceed(request.newBuilder().header("Authorization", "Bearer " + token).build())
                    if (response.isRedirect) {
                        response.close()
                        throw IOException("带账号归属的请求不跟随重定向")
                    }
                    response
                }
            }
        }
        .build()

    private val retrofit: Retrofit = Retrofit.Builder()
        .baseUrl(endpoint)
        .client(okHttp)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    val api: PodcastApi = retrofit.create(PodcastApi::class.java)

    val baseUrlResolved: String get() = retrofit.baseUrl().toString()

    private val logoutApi: LogoutApi by lazy {
        // The bearer and body belong to the same cleared login; never refresh or redirect them.
        retrofit.newBuilder()
            .client(okHttp.newBuilder()
                .authenticator(Authenticator.NONE)
                .retryOnConnectionFailure(false)
                .followRedirects(false)
                .followSslRedirects(false)
                .build())
            .build()
            .create(LogoutApi::class.java)
    }

    internal suspend fun revokeSession(accessToken: String, refreshToken: String) {
        logoutApi.logout("Bearer $accessToken", LogoutRequest(refreshToken = refreshToken))
    }

    internal fun notifyAuthExpired() = onUnauthorized()

    private interface LogoutApi {
        @POST("auth/logout")
        suspend fun logout(@Header("Authorization") authorization: String, @Body body: LogoutRequest): StatusResponse
    }

    private fun ownedToken(request: Request, owner: ProgressOwner): String? {
        val url = request.url
        if (owner.baseUrl != endpoint.toString() || url.scheme != endpoint.scheme || url.host != endpoint.host ||
            url.port != endpoint.port || !url.encodedPath.startsWith(endpoint.encodedPath)) return null
        return ownedTokenProvider(owner)
    }

    private fun responseCount(response: Response): Int {
        var count = 1
        var prior = response.priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }

}

/** Carries the failed bearer through the existing refresher callback to its mutex-protected check. */
internal class RefreshAttempt(val failedAccessToken: String) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<RefreshAttempt>

    var newAccessToken: String? = null
}

/** 统一 API 异常，携带 HTTP 状态码与后端错误信息。 */
class ApiException(
    val statusCode: Int,
    override val message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    val isUnauthorized: Boolean get() = statusCode == 401
    val isNetwork: Boolean get() = statusCode == 0
}
