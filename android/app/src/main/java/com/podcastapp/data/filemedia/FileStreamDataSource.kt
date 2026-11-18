package com.podcastapp.data.filemedia

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpUtil
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

// This client never sends account credentials or follows redirects with the ticket.
private val streamClient by lazy {
    OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
}

@OptIn(UnstableApi::class)
internal class FileStreamFactory(private val url: HttpUrl, private val mediaUri: Uri) : DataSource.Factory {
    private var invalidated = false
    private val calls = mutableSetOf<Call>()

    override fun createDataSource(): DataSource = FileStreamDataSource(this, mediaUri)

    @Synchronized
    fun cancel() {
        invalidated = true
        calls.forEach { it.cancel() }
        calls.clear()
    }

    @Synchronized
    fun newCall(position: Long, length: Long): Call {
        if (invalidated) throw IOException("文件音源会话已变更")
        val request = Request.Builder().url(url).header("Accept-Encoding", "identity")
        HttpUtil.buildRangeRequestHeader(position, length)?.let { request.header("Range", it) }
        return streamClient.newCall(request.build()).also { calls += it }
    }

    @Synchronized
    fun forget(call: Call) { calls -= call }
}

@OptIn(UnstableApi::class)
private class FileStreamDataSource(private val factory: FileStreamFactory, private val mediaUri: Uri) : BaseDataSource(true) {
    private var call: Call? = null
    private var response: Response? = null
    private var input: InputStream? = null
    private var opened = false
    private var remaining = C.LENGTH_UNSET.toLong()

    override fun open(dataSpec: DataSpec): Long {
        // The public MediaItem contains only a placeholder URI, never a ticket.
        if (dataSpec.uri != mediaUri || dataSpec.httpMethod != DataSpec.HTTP_METHOD_GET) {
            throw IOException("无效的文件播放请求")
        }
        transferInitializing(dataSpec)
        try {
            val nextCall = factory.newCall(dataSpec.position, dataSpec.length).also { call = it }
            val result = nextCall.execute().also { response = it }
            val contentRange = result.header("Content-Range")
            val body = result.body
            if (result.code == 416 && HttpUtil.getDocumentSize(contentRange) == dataSpec.position) {
                remaining = 0L
            } else {
                val rangeStart = contentRange?.removePrefix("bytes ")?.substringBefore('-')?.toLongOrNull()
                if (body == null || (result.code != 200 && result.code != 206) ||
                    (result.code == 206 && rangeStart != dataSpec.position) ||
                    (result.code == 200 && dataSpec.position != 0L) ||
                    result.header("Content-Encoding")?.equals("identity", ignoreCase = true) == false) {
                    throw IOException("文件流响应无效（HTTP " + result.code + "）")
                }
                val contentLength = HttpUtil.getContentLength(result.header("Content-Length"), contentRange)
                remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else contentLength
                input = body.byteStream()
            }
            opened = true
            transferStarted(dataSpec)
            return remaining
        } catch (e: Exception) {
            close()
            // Never propagate a transport exception that might contain the signed URL.
            throw IOException("无法读取文件音源，请重新选择文件后重试。")
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val limit = if (remaining == C.LENGTH_UNSET.toLong()) length else minOf(remaining, length.toLong()).toInt()
        val bytesRead = try {
            checkNotNull(input).read(buffer, offset, limit)
        } catch (_: IOException) {
            throw IOException("文件音源连接已中断")
        }
        if (bytesRead == -1) {
            if (remaining != C.LENGTH_UNSET.toLong()) throw EOFException("文件流提前结束")
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
