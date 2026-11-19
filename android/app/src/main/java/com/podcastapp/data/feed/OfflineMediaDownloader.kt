package com.podcastapp.data.feed

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.FileOutputStream
import java.util.Locale
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.Response

internal const val MAX_OFFLINE_EPISODE_BYTES = 256L * 1024L * 1024L
private const val MAX_MEDIA_REDIRECTS = 5

internal data class OfflineSourceAccess(
    val credentials: SourceCredentials = SourceCredentials(),
    val transport: SourceTransport = SourceTransport(),
)

internal class UnsupportedOfflineMedia : IOException("不是可缓存的单集音频文件")

internal fun interface OfflineMediaDownloader {
    suspend fun download(
        feedUrl: String,
        mediaUrl: String,
        access: OfflineSourceAccess,
        destination: File,
        onProgress: (Long, Long?) -> Unit,
    ): Long
}

internal val HttpOfflineMediaDownloader = OfflineHttpDownloader()

internal class OfflineHttpDownloader(
    private val hasMediaSignature: (ByteArray, Int) -> Boolean = { bytes, length ->
        NativeOfflineCache.hasMediaSignature(bytes, length)
    },
    private val createCall: (Request, OfflineSourceAccess) -> Call = { request, settings ->
        LocalPodcastLibrary.buildSourceClient(settings.credentials, settings.transport, timeoutSeconds = 240).newCall(request)
    },
) : OfflineMediaDownloader {
    override suspend fun download(
        feedUrl: String,
        mediaUrl: String,
        access: OfflineSourceAccess,
        destination: File,
        onProgress: (Long, Long?) -> Unit,
    ): Long = coroutineScope {
        val source = offlineMediaUrl(feedUrl, allowDocuments = true) ?: throw IOException("订阅来源地址无效")
        var current = offlineMediaUrl(mediaUrl) ?: throw UnsupportedOfflineMedia()
        var active = offlineHopAccess(source, current, access)
        for (hop in 0..MAX_MEDIA_REDIRECTS) {
            ensureActive()
            SourceTransportSecurity.assertSchemeAllowed(current.toString(), active.transport)
            val request = Request.Builder().url(current).header("User-Agent", "PodcastApp-OfflineCache")
                .header("Accept-Encoding", "identity").apply {
                    credentialHeaders(active.credentials).forEach { (name, value) -> header(name, value) }
                }.build()
            val call = createCall(request, active)
            // 在当前线程阻塞等待 OkHttp；独立取消监听立即关闭 socket，writer 返回后上层才清理 .part。
            val cancellation = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { call.cancel() }
            }
            try {
                call.execute().use { response ->
                    if (response.code in listOf(301, 302, 303, 307, 308)) {
                        if (hop == MAX_MEDIA_REDIRECTS) throw IOException("媒体跳转次数过多")
                        val location = response.header("Location")?.takeIf { it.isNotBlank() }
                            ?: throw IOException("媒体跳转地址缺失")
                        val next = current.resolve(location)
                            ?.let { offlineMediaUrl(it.toString()) } ?: throw UnsupportedOfflineMedia()
                        if (current.isHttps && !next.isHttps) throw IOException("拒绝 HTTPS 降级跳转")
                        active = offlineHopAccess(current, next, active)
                        current = next
                    } else {
                        if (response.code != 200 || response.header("Content-Range") != null) {
                            throw IOException("未收到完整单集 HTTP 200 响应")
                        }
                        return@coroutineScope writeResponse(response, destination, onProgress)
                    }
                }
            } finally {
                cancellation.cancel()
            }
        }
        throw IOException("媒体跳转次数过多")
    }

    private suspend fun writeResponse(
        response: Response,
        destination: File,
        onProgress: (Long, Long?) -> Unit,
    ): Long {
        if (response.headers.names().any { it.startsWith("icy-", ignoreCase = true) }) throw UnsupportedOfflineMedia()
        val encoding = response.header("Content-Encoding")
        if (encoding != null && !encoding.equals("identity", ignoreCase = true)) throw UnsupportedOfflineMedia()
        val body = response.body ?: throw IOException("单集响应为空")
        val type = response.header("Content-Type").orEmpty().substringBefore(';').trim().lowercase(Locale.ROOT)
        if (!supportedOfflineMime(type)) throw UnsupportedOfflineMedia()
        return streamOfflineBody(body.byteStream(), body.contentLength(), destination,
            hasMediaSignature = hasMediaSignature, onProgress = onProgress)
    }
}

/** 复用 HTTPS 才传递凭据的既有规则，且精确比较 scheme、host 和有效端口。 */
internal fun offlineHopAccess(current: HttpUrl, next: HttpUrl, active: OfflineSourceAccess): OfflineSourceAccess {
    if (current.isHttps && next.isHttps && current.host == next.host && current.port == next.port) return active
    return OfflineSourceAccess(transport = SourceTransport(requireHttps = active.transport.requireHttps))
}

private fun supportedOfflineMime(type: String): Boolean =
    !listOf("mpegurl", "scpls", "playlist", "dash", "asx", "x-ms-wax").any(type::contains) &&
        (type.startsWith("audio/") || type in setOf("", "application/octet-stream", "binary/octet-stream",
            "application/ogg", "application/mp4", "video/mp4", "video/webm", "video/x-ms-asf"))

internal suspend fun streamOfflineBody(
    input: InputStream,
    expected: Long,
    destination: File,
    maxBytes: Long = MAX_OFFLINE_EPISODE_BYTES,
    hasMediaSignature: (ByteArray, Int) -> Boolean,
    onProgress: (Long, Long?) -> Unit,
): Long {
    if (expected == 0L || expected > maxBytes) throw IOException("单集大小不符合缓存限制")
    val context = currentCoroutineContext()
    val prefix = ByteArray(16)
    var prefixLength = 0
    while (prefixLength < prefix.size) {
        context.ensureActive()
        val read = input.read(prefix, prefixLength, prefix.size - prefixLength)
        if (read < 0) break
        prefixLength += read
    }
    if (!hasMediaSignature(prefix, prefixLength)) throw UnsupportedOfflineMedia()
    var total = prefixLength.toLong()
    if (total > maxBytes || (expected >= 0L && total > expected)) throw IOException("单集大小超过限制")
    val declared = expected.takeIf { it >= 0L }
    if (!destination.createNewFile()) throw IOException("无法创建缓存临时文件")
    FileOutputStream(destination).buffered().use { out ->
        out.write(prefix, 0, prefixLength)
        onProgress(total, declared)
        val buffer = ByteArray(64 * 1024)
        var lastReported = total
        while (true) {
            context.ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > maxBytes || (expected >= 0L && total > expected)) throw IOException("单集大小超过限制")
            out.write(buffer, 0, count)
            if (total - lastReported >= 256L * 1024L) {
                onProgress(total, declared)
                lastReported = total
            }
        }
    }
    context.ensureActive()
    if (expected >= 0L && expected != total) throw IOException("单集大小校验失败")
    onProgress(total, declared)
    return total
}
