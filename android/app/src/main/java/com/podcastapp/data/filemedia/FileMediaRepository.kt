package com.podcastapp.data.filemedia

import com.podcastapp.data.ProgressOwner
import com.podcastapp.data.ServiceLocator
import com.podcastapp.data.Session
import com.podcastapp.data.progressOwner
import com.podcastapp.data.remote.ApiClient
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import retrofit2.HttpException

@Serializable
data class FileSource(val id: String, val label: String)

@Serializable
data class FileEntry(val name: String, val directory: Boolean, val size: Long)

@Serializable
data class FileSourcesResponse(val sources: List<FileSource>)

@Serializable
data class FileEntriesResponse(val entries: List<FileEntry>)

@Serializable
data class FileTicketRequest(val source: String, val path: String)

@Serializable
class FileTicketResponse(val ticket: String)

internal const val FILE_DIRECTORY_LIMIT = 500
internal data class FileDirectory(val entries: List<FileEntry>, val limitReached: Boolean)

// Bind to a particular login and API client; token refresh leaves this identity unchanged.
internal data class FileMediaSession private constructor(
    private val owner: ProgressOwner,
    private val loginId: String?,
    private val client: ApiClient,
) {
    suspend fun sources(): List<FileSource> = request { client.api.fileSources(owner).sources }
        .filter { it.id == "local" || it.id == "webdav" }.distinctBy { it.id }

    suspend fun entries(source: FileSource, path: List<String>): FileDirectory = request {
        val entries = client.api.fileEntries(source.id, filePath(path), owner).entries
        FileDirectory(entries.take(FILE_DIRECTORY_LIMIT)
            .filter { isFileSegment(it.name) }.distinctBy { it.name }, entries.size >= FILE_DIRECTORY_LIMIT)
    }

    suspend fun playback(source: FileSource, path: List<String>, entry: FileEntry): FileMediaPlayback = request {
        require(!entry.directory && isFileSegment(entry.name))
        val ticket = client.api.fileTicket(FileTicketRequest(source.id, filePath(path + entry.name)), owner).ticket
        FileMediaPlayback(this, entry.name, fileStreamUrl(client.baseUrlResolved, ticket))
    }

    suspend fun requireCurrent() {
        if (!matches(ServiceLocator.sessionStore.current(), ServiceLocator.client.value)) {
            throw CancellationException("文件音源会话已变更")
        }
    }

    suspend fun awaitInvalidation() {
        ServiceLocator.sessionStore.session.combine(ServiceLocator.client, ::matches).first { !it }
    }

    private fun matches(session: Session, current: ApiClient?) =
        current === client && session.progressOwner() == owner && session.loginId == loginId

    private suspend fun <T> request(block: suspend () -> T): T = coroutineScope {
        requireCurrent()
        val watch = launch(start = CoroutineStart.UNDISPATCHED) {
            awaitInvalidation()
            this@coroutineScope.cancel(CancellationException("文件音源会话已变更"))
        }
        try {
            block().also { requireCurrent() }
        } finally {
            watch.cancel()
        }
    }

    companion object {
        fun from(session: Session, client: ApiClient?): FileMediaSession? {
            val owner = session.progressOwner() ?: return null
            return client?.takeIf { it.baseUrlResolved == owner.baseUrl }
                ?.let { FileMediaSession(owner, session.loginId, it) }
        }
    }
}

// Avoid data-class toString: the stream URL contains a short-lived credential.
internal class FileMediaPlayback(val session: FileMediaSession, val title: String, val url: HttpUrl)

private fun isFileSegment(name: String): Boolean = name.isNotEmpty() && name != "." && name != ".." &&
    name.none { it == '/' || it == '\\' || it.isISOControl() }

private fun filePath(path: List<String>): String = path.joinToString("/").also {
    if (it.toByteArray(Charsets.UTF_8).size > 2048) throw FilePathTooLong()
}

private class FilePathTooLong : IllegalArgumentException()

internal fun fileStreamUrl(baseUrl: String, ticket: String): HttpUrl {
    require(ticket.matches(Regex("[A-Za-z0-9._~-]{1,1024}"))) { "无效的文件播放凭证" }
    return baseUrl.toHttpUrl().newBuilder()
        .addPathSegment("media").addPathSegment("stream")
        .addQueryParameter("ticket", ticket).build()
}

internal fun fileMediaError(error: Exception, notFound: String, badGateway: String? = null): String = when (error) {
    is HttpException -> when (error.code()) {
        401 -> "登录已失效，请重新登录。"
        403 -> "当前账号无权访问该文件音源。"
        404 -> notFound
        413 -> "目录内容超出服务器限制，请联系管理员整理目录。"
        429, 503 -> "文件音源暂时繁忙，请稍后重试。"
        502 -> badGateway ?: "文件音源暂不可用，请稍后重试。"
        else -> "文件音源请求失败，请稍后重试。"
    }
    is FilePathTooLong -> "路径超过 2048 字节，请联系管理员调整目录。"
    is IOException -> "无法连接文件音源，请检查网络后重试。"
    else -> "文件音源响应无效，请稍后重试。"
}
