package com.podcastapp.ui.filemedia

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.podcastapp.data.filemedia.FILE_DIRECTORY_LIMIT
import com.podcastapp.data.filemedia.FileEntry
import com.podcastapp.data.filemedia.FileMediaPlayback
import com.podcastapp.data.filemedia.FileMediaSession
import com.podcastapp.data.filemedia.FileSource
import com.podcastapp.data.filemedia.fileMediaError
import com.podcastapp.ui.components.InlineIssue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun FileMediaScreen(
    session: FileMediaSession?,
    onBack: () -> Unit,
    onPlay: suspend (FileMediaPlayback) -> Unit,
) = key(session) {
    var active by remember { mutableStateOf(session != null) }
    var source by remember { mutableStateOf<FileSource?>(null) }
    var path by remember { mutableStateOf(emptyList<String>()) }
    val currentSource = source
    LaunchedEffect(session) {
        if (session != null) {
            session.awaitInvalidation()
            active = false
        }
    }

    fun back() {
        when {
            !active || currentSource == null -> onBack()
            path.isEmpty() -> source = null
            else -> path = path.dropLast(1)
        }
    }
    BackHandler(enabled = active && currentSource != null, onBack = ::back)
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        TextButton(onClick = ::back) { Text(if (active && currentSource != null) "返回上一级" else "返回导入") }
        Text("文件音源", Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineMedium)
        if (!active || session == null) {
            Text("当前会话不可用，请返回后重试。", Modifier.padding(top = 12.dp))
        } else if (currentSource == null) {
            Sources(session, onSelect = { source = it; path = emptyList() })
        } else {
            key(currentSource.id, path) {
                Directory(session, currentSource, path, onOpen = { path = path + it }, onPlay = onPlay)
            }
        }
    }
}

@Composable
private fun Sources(session: FileMediaSession, onSelect: (FileSource) -> Unit) {
    val load = fileLoad("当前服务器未启用或不支持文件音源。", request = session::sources)
    val sources = load.value
    LazyColumn(contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("浏览管理员为当前账号配置的只读音源。", style = MaterialTheme.typography.bodyMedium) }
        when {
            load.error != null -> item { InlineIssue(load.error, load.retry) }
            sources == null -> item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            sources.isEmpty() -> item { Text("当前账号没有可访问的文件音源。") }
            else -> items(sources, key = { it.id }) { source ->
                FileRow(source.displayLabel(), "打开音源", enabled = true) { onSelect(source) }
            }
        }
    }
}

@Composable
private fun Directory(
    session: FileMediaSession,
    source: FileSource,
    path: List<String>,
    onOpen: (String) -> Unit,
    onPlay: suspend (FileMediaPlayback) -> Unit,
) {
    val load = fileLoad("目录不存在，或文件音源已关闭。", "无法读取目录，音源可能不可用或条目过多。") {
        session.entries(source, path)
    }
    val directory = load.value
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<FileEntry?>(null) }
    var playIssue by remember { mutableStateOf<Pair<String, String>?>(null) }
    fun play(entry: FileEntry) {
        if (pending != null) return
        pending = entry
        playIssue = null
        scope.launch {
            try {
                onPlay(session.playback(source, path, entry))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                playIssue = entry.name to fileMediaError(e, "文件不存在，或文件音源已关闭。")
            } finally {
                pending = null
            }
        }
    }
    LazyColumn(contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item {
            val location = path.joinToString("/", prefix = "/")
            Text(source.displayLabel() + " · " + location, style = MaterialTheme.typography.titleSmall,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("每个目录最多显示 $FILE_DIRECTORY_LIMIT 项。", style = MaterialTheme.typography.bodySmall)
        }
        when {
            load.error != null -> item { InlineIssue(load.error, load.retry) }
            directory == null -> item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            directory.entries.isEmpty() -> item { Text("此目录没有可显示的文件或子目录。") }
        }
        if (directory?.limitReached == true) item { Text("已达目录显示上限，请联系管理员整理目录。") }
        items(directory?.entries.orEmpty(), key = { it.name }) { entry ->
            val subtitle = when {
                entry.directory -> "打开目录"
                pending == entry -> "正在请求播放…"
                else -> "播放文件"
            }
            FileRow(entry.name, subtitle, enabled = entry.directory || pending == null) {
                if (entry.directory) onOpen(entry.name) else play(entry)
            }
            playIssue?.takeIf { it.first == entry.name }?.let { InlineIssue(it.second, { play(entry) }) }
        }
    }
}

private fun FileSource.displayLabel() = label.ifBlank { if (id == "local") "服务器本地文件" else "WebDAV" }

@Composable
private fun FileRow(title: String, subtitle: String, enabled: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .heightIn(min = 48.dp).padding(vertical = 8.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title.ifBlank { "（空白名称）" }, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider()
}

private data class FileLoad<T>(val value: T?, val error: String?, val retry: () -> Unit)

@Composable
private fun <T> fileLoad(notFound: String, badGateway: String? = null, request: suspend () -> T): FileLoad<T> {
    var attempt by remember { mutableStateOf(0) }
    var value by remember(attempt) { mutableStateOf<T?>(null) }
    var error by remember(attempt) { mutableStateOf<String?>(null) }
    LaunchedEffect(attempt) {
        try {
            value = request()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = fileMediaError(e, notFound, badGateway)
        }
    }
    return FileLoad(value, error, { attempt++ })
}
