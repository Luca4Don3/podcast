package com.podcastapp.data.serversetup

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.podcastapp.data.remote.AuthResponse
import com.podcastapp.data.remote.LoginRequest
import com.podcastapp.data.remote.PodcastApi
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import okhttp3.Authenticator
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.HttpException
import retrofit2.Retrofit

/** A clean transport for confirming a link. It cannot send tokens from the current session. */
internal class ServerSetupLogin(builder: OkHttpClient.Builder = OkHttpClient.Builder()) {
    private val client = builder
        .callTimeout(12, TimeUnit.SECONDS)
        .authenticator(Authenticator.NONE)
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    suspend fun verify(link: ServerSetupLink, deviceId: String): AuthResponse {
        val email = requireNotNull(link.email)
        val password = requireNotNull(link.password)
        check(link.baseUrl.startsWith("https://")) { "带密码的链接必须使用 HTTPS" }
        val api = Retrofit.Builder()
            .baseUrl(link.baseUrl)
            .client(client)
            .addConverterFactory(Json { ignoreUnknownKeys = true }.asConverterFactory("application/json".toMediaType()))
            .build().create(PodcastApi::class.java)
        val response = try {
            api.login(LoginRequest(email, password, deviceId))
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            // A server is not trusted to provide errors that are safe to display.
            val message = when (e.code()) {
                in 300..399 -> "服务器要求重定向，请使用最终的 HTTPS API 地址"
                400, 401, 403 -> "登录验证失败，请检查账号密码"
                404, 405 -> "找不到登录接口，请检查播客服务器地址"
                429 -> "尝试次数过多，请稍后重试"
                else -> "服务器暂时无法验证登录，请稍后重试"
            }
            throw IllegalStateException(message)
        } catch (_: IOException) {
            throw IllegalStateException("无法建立连接，请检查网络、HTTPS 证书和服务器地址")
        } catch (_: Exception) {
            throw IllegalStateException("该地址没有返回有效的播客登录结果")
        }
        check(response.user.id.isNotBlank() && response.user.email.equals(email, ignoreCase = true) &&
            response.token.accessToken.isNotBlank() && response.token.refreshToken.isNotBlank()) {
            "该地址没有返回有效的播客登录结果"
        }
        return response
    }
}
