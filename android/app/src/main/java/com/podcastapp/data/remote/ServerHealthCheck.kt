package com.podcastapp.data.remote

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/** Probe the selected API without the authenticated client or its credentials. */
object ServerHealthCheck {
    private val client = OkHttpClient.Builder()
        .callTimeout(8, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    suspend fun check(apiBase: String) = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(apiBase + "healthz").build()
        try {
            client.newCall(request).execute().use { response ->
                check(response.code !in 300..399) { "服务器要求跳转，请填写最终 HTTP(S) API 地址" }
                check(response.isSuccessful) { "服务器返回 HTTP " + response.code }
                val body = response.peekBody(16_384L).string()
                val payload = runCatching { Json.parseToJsonElement(body) }.getOrNull()
                check((payload as? JsonObject)?.get("status") == JsonPrimitive("ok")) {
                    "此地址未返回播客服务状态。NAS 管理页、网盘分享页不能作为服务器地址"
                }
            }
        } catch (_: UnknownHostException) {
            error("找不到服务器域名。若使用 Tailscale，请检查连接、MagicDNS 和主机名")
        } catch (_: SSLException) {
            error("HTTPS 验证失败，请检查证书是否受信任，以及地址是否与证书匹配")
        } catch (_: SocketTimeoutException) {
            error("连接超时，请检查网络、Tailscale 权限或服务器端口")
        } catch (_: ConnectException) {
            error("无法连接到服务器，请检查服务是否启动、端口及防火墙")
        } catch (_: IOException) {
            error("连接中断或超时，请检查网络、Tailscale 权限或服务器端口")
        }
    }
}
