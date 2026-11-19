package com.podcastapp.data.storage

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import java.io.IOException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.Request

/** Fetches a new short-lived location on every open (including resumes and seeks). */
internal class OneDriveDownloads(
    private val calls: Call.Factory,
    private val resolve: suspend () -> HttpUrl,
) {
    private val lifetime = Job()
    private val activeCalls = mutableSetOf<Call>()

    @Synchronized
    fun cancel() {
        lifetime.cancel()
        activeCalls.forEach { it.cancel() }
        activeCalls.clear()
    }

    @Synchronized
    fun forget(call: Call) { activeCalls -= call }

    fun open(position: Long, length: Long): StorageStreamResult {
        var url = runBlocking(lifetime) { resolve() }
        repeat(6) {
            if (!url.isPublicHttps()) throw OneDriveError("拒绝不安全的 OneDrive 音频跳转。")
            // Do not forward bearer tokens, cookies or metadata from Graph to the signed URL.
            val request = Request.Builder().url(url).header("Accept-Encoding", "identity").apply {
                if (position > 0 || length > 0) {
                    val end = if (length > 0) (position + (length - 1).coerceAtMost(Long.MAX_VALUE - position)).toString() else ""
                    header("Range", "bytes=" + position + "-" + end)
                }
            }.build()
            val call = synchronized(this) {
                if (!lifetime.isActive) throw OneDriveError("OneDrive 播放会话已结束。")
                calls.newCall(request).also { activeCalls += it }
            }
            val response = try { call.execute() } catch (_: IOException) {
                forget(call)
                throw OneDriveNetworkError()
            }
            if (response.code !in setOf(301, 302, 303, 307, 308)) return StorageStreamResult(call, response)
            val location = response.use { it.header("Location") }
            forget(call)
            url = location?.let(url::resolve) ?: throw OneDriveError("OneDrive 音频跳转地址无效。")
        }
        throw OneDriveError("OneDrive 音频跳转次数过多。")
    }
}

@OptIn(UnstableApi::class)
internal class OneDrivePlaybackFactory(
    private val downloads: OneDriveDownloads,
    private val mediaUri: Uri,
) : StoragePlaybackFactory {
    override fun cancel() = downloads.cancel()
    override fun createDataSource(): DataSource =
        StorageStreamDataSource(mediaUri, downloads::open, downloads::forget, exposeResponseHeaders = false)
}
