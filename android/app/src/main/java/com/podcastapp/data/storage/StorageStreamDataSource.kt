package com.podcastapp.data.storage

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpUtil
import com.podcastapp.data.feed.CredentialKind
import com.podcastapp.data.feed.DigestAuthenticator
import com.podcastapp.data.feed.SourceCredentials
import com.podcastapp.data.feed.SourceTransport
import com.podcastapp.data.feed.SourceTransportSecurity
import com.podcastapp.data.feed.credentialHeaders
import com.podcastapp.data.feed.digestEnabled
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import okhttp3.Call
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

private const val USER_AGENT = "PodcastApp-StorageStream/1.0"
private const val MAX_REDIRECTS = 5
private val CREDENTIAL_QUERY_KEYS = setOf("token", "api_key", "key", "password", "secret", "auth")

/** 最终响应和它所属的 Call；关闭流时必须一起释放。 */
internal data class StorageStreamResult(val call: Call, val response: Response)

/**
 * 网络存储音频的 DataSource.Factory。
 *
 * 真实 URL、认证头、自定义 CA、mTLS 私钥和 Digest 材料只存在于本 factory 内部；
 * MediaItem / MediaSession 只看到 storage: 占位 URI。凭据仅以请求头形式出现，
 * 绝不拼进 URL，也不进入 NowPlaying / StateFlow。
 */
@OptIn(UnstableApi::class)
internal class StorageStreamFactory(
    mediaUrl: String,
    private val mediaUri: Uri,
    private val credentials: SourceCredentials,
    private val transport: SourceTransport,
) : DataSource.Factory {
    private val url: HttpUrl = validateMediaUrl(mediaUrl, transport)
    private var invalidated = false
    private val calls = mutableSetOf<Call>()

    override fun createDataSource(): DataSource = StorageStreamDataSource(this, mediaUri)

    /** 退出或切换音源时调用：后续请求直接失败，正在进行的请求立刻中断。 */
    @Synchronized
    fun cancel() {
        invalidated = true
        calls.forEach { it.cancel() }
        calls.clear()
    }

    @Synchronized
    fun forget(call: Call) {
        calls -= call
    }

    /**
     * 手动跟随跳转，逐跳重新生成凭据头与传输材料，返回最终尚未读取的响应。
     * 不设置 OkHttp callTimeout：音频是长流，只限制连接与两次读之间的空闲时间。
     */
    internal fun openResponse(position: Long, length: Long): StorageStreamResult {
        var current = url.toString()
        var hopCredentials = credentials
        var hopTransport = transport
        for (hop in 0..MAX_REDIRECTS) {
            SourceTransportSecurity.assertSchemeAllowed(current, hopTransport)
            val request = Request.Builder()
                .url(current)
                .header("User-Agent", USER_AGENT)
                .header("Accept-Encoding", "identity")
                .apply {
                    HttpUtil.buildRangeRequestHeader(position, length)?.let { header("Range", it) }
                    credentialHeaders(hopCredentials).forEach { (name, value) -> header(name, value) }
                }
                .build()
            val client = buildClient(hopCredentials, hopTransport)
            val call = synchronized(this) {
                if (invalidated) throw IOException("网络存储播放会话已变更")
                client.newCall(request).also { calls += it }
            }
            val response = try {
                call.execute()
            } catch (e: Exception) {
                synchronized(this) { calls -= call }
                throw e
            }
            if (response.code in 300..399) {
                val location = response.header("Location")
                response.close()
                synchronized(this) { calls -= call }
                if (hop == MAX_REDIRECTS) throw IOException("跳转次数过多")
                if (location.isNullOrBlank()) throw IOException("跳转地址缺失")
                val next = resolveRedirect(current, location, hopTransport)
                hopCredentials = credentialsForHop(current, next, hopCredentials)
                hopTransport = SourceTransportSecurity.transportForRedirect(current, next, hopTransport)
                current = next
                continue
            }
            return StorageStreamResult(call, response)
        }
        throw IOException("跳转次数过多")
    }

    private fun buildClient(creds: SourceCredentials, tr: SourceTransport): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .cookieJar(CookieJar.NO_COOKIES)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
        val hasTlsMaterial = tr.customCaPem.isNotBlank() ||
            tr.clientCertPem.isNotBlank() || tr.clientKeyPem.isNotBlank()
        if (hasTlsMaterial) {
            val trustManager = SourceTransportSecurity.trustManager(tr.customCaPem)
            val keyManagers: Array<KeyManager>? =
                if (tr.clientCertPem.isNotBlank() || tr.clientKeyPem.isNotBlank()) {
                    arrayOf(SourceTransportSecurity.keyManager(tr.clientCertPem, tr.clientKeyPem, tr.clientKeyPassword))
                } else {
                    null
                }
            val socketFactory = SSLContext.getInstance("TLS").apply {
                init(keyManagers, arrayOf(trustManager), null)
            }.socketFactory
            builder.sslSocketFactory(socketFactory, trustManager)
        }
        if (digestEnabled(creds, tr)) builder.authenticator(DigestAuthenticator(creds))
        return builder.build()
    }
}

/** 构造时校验音源地址：仅 http(s)、禁止 userinfo / 凭据查询参数 / requireHttps 违规。 */
private fun validateMediaUrl(value: String, transport: SourceTransport): HttpUrl {
    require(value.isNotBlank()) { "音源地址为空" }
    require(!value.any { it.isISOControl() || it.isWhitespace() || it == '\\' }) { "音源地址含非法字符" }
    val url = runCatching { value.trim().toHttpUrl() }
        .getOrElse { throw IllegalArgumentException("音源地址不是有效的 http(s) URL") }
    require(url.scheme.equals("http", ignoreCase = true) || url.scheme.equals("https", ignoreCase = true)) {
        "只接受 http(s) 音源"
    }
    require(url.username.isEmpty() && url.password.isEmpty()) { "URL 不得包含 userinfo" }
    require(url.queryParameterNames.none { it.lowercase(Locale.ROOT) in CREDENTIAL_QUERY_KEYS }) {
        "凭据不得出现在 URL 查询参数中"
    }
    SourceTransportSecurity.assertSchemeAllowed(url.toString(), transport)
    return url
}

/** 只接受 http(s) 且无 userinfo 的同来源跳转；requireHttps 逐跳重新校验。 */
private fun resolveRedirect(current: String, location: String, tr: SourceTransport): String {
    require(!location.any { it.isISOControl() || it.isWhitespace() || it == '\\' }) { "跳转地址含非法字符" }
    val next = current.toHttpUrl().resolve(location) ?: throw IOException("跳转地址无效")
    require(next.scheme.equals("http", ignoreCase = true) || next.scheme.equals("https", ignoreCase = true)) {
        "拒绝非 http(s) 跳转"
    }
    require(next.username.isEmpty() && next.password.isEmpty()) { "跳转地址不得包含 userinfo" }
    SourceTransportSecurity.assertSchemeAllowed(next.toString(), tr)
    return next.toString()
}

/** 跨 host、端口变化或 https 降级时凭据降级为 NONE；与 WebDavClient 的逐跳策略一致。 */
private fun credentialsForHop(current: String, next: String, creds: SourceCredentials): SourceCredentials {
    if (creds.kind == CredentialKind.NONE) return SourceCredentials()
    val from = runCatching { current.toHttpUrl() }.getOrNull() ?: return SourceCredentials()
    val to = runCatching { next.toHttpUrl() }.getOrNull() ?: return SourceCredentials()
    val sameOrigin = from.host.equals(to.host, ignoreCase = true) &&
        from.port == to.port && from.scheme.equals(to.scheme, ignoreCase = true)
    val secure = from.scheme.equals("https", ignoreCase = true) && to.scheme.equals("https", ignoreCase = true)
    return if (sameOrigin && secure) creds else SourceCredentials()
}

@OptIn(UnstableApi::class)
private class StorageStreamDataSource(
    private val factory: StorageStreamFactory,
    private val mediaUri: Uri,
) : BaseDataSource(true) {
    private var call: Call? = null
    private var response: Response? = null
    private var input: InputStream? = null
    private var opened = false
    private var remaining = C.LENGTH_UNSET.toLong()

    override fun open(dataSpec: DataSpec): Long {
        // 公开 MediaItem 里只有占位 URI，绝不出现真实 URL 或认证头。
        if (dataSpec.uri != mediaUri || dataSpec.httpMethod != DataSpec.HTTP_METHOD_GET) {
            throw IOException("无效的网络存储播放请求")
        }
        transferInitializing(dataSpec)
        try {
            val stream = factory.openResponse(dataSpec.position, dataSpec.length)
            call = stream.call
            val result = stream.response.also { response = it }
            val contentRange = result.header("Content-Range")
            val body = result.body
            if (result.code == 416 && HttpUtil.getDocumentSize(contentRange) == dataSpec.position) {
                remaining = 0L
            } else {
                val rangeStart = contentRange?.removePrefix("bytes ")?.substringBefore('-')?.toLongOrNull()
                if (body == null || (result.code != 200 && result.code != 206) ||
                    (result.code == 206 && rangeStart != dataSpec.position) ||
                    (result.code == 200 && dataSpec.position != 0L) ||
                    result.header("Content-Encoding")?.equals("identity", ignoreCase = true) == false
                ) {
                    throw IOException("网络存储流响应无效（HTTP " + result.code + "）")
                }
                // 不限制音频总大小：长度未知时保持 LENGTH_UNSET，一直流式读到 EOF。
                val contentLength = HttpUtil.getContentLength(result.header("Content-Length"), contentRange)
                remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else contentLength
                input = body.byteStream()
            }
            opened = true
            transferStarted(dataSpec)
            return remaining
        } catch (e: Exception) {
            close()
            // 异常文案不能暴露可能带有签名/凭据的原始错误信息。
            throw IOException("无法读取网络存储音源，请检查来源设置后重试。")
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val limit = if (remaining == C.LENGTH_UNSET.toLong()) length else minOf(remaining, length.toLong()).toInt()
        val bytesRead = try {
            checkNotNull(input).read(buffer, offset, limit)
        } catch (_: IOException) {
            throw IOException("网络存储音源连接已中断")
        }
        if (bytesRead == -1) {
            if (remaining != C.LENGTH_UNSET.toLong()) throw EOFException("网络存储流提前结束")
            return C.RESULT_END_OF_INPUT
        }
        if (remaining != C.LENGTH_UNSET.toLong()) remaining -= bytesRead
        bytesTransferred(bytesRead)
        return bytesRead
    }

    override fun getUri(): Uri? = mediaUri.takeIf { opened }

    override fun getResponseHeaders(): Map<String, List<String>> = response?.headers?.toMultimap().orEmpty()

    override fun close() {
        input = null
        try {
            response?.close()
        } finally {
            response = null
            call?.let(factory::forget)
            call = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }
}
