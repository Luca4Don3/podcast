package com.podcastapp.ui.discover

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.podcastapp.data.storage.OneDriveAuthorization
import com.podcastapp.data.storage.OneDriveItem
import com.podcastapp.data.storage.OneDrivePage
import com.podcastapp.data.storage.OneDriveSession
import com.podcastapp.data.storage.validOneDriveAppId
import com.podcastapp.playback.PlaybackHolder
import java.io.IOException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal class CloudDriveViewModel : ViewModel() {
    val session = OneDriveSession()
    override fun onCleared() { session.disconnect() }
}

@Composable
internal fun CloudDriveSection(model: CloudDriveViewModel = viewModel()) {
    val session = model.session
    val auth by session.authorization.collectAsState()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var id by remember { mutableStateOf("") }
    var signIn by remember { mutableStateOf<Job?>(null) }
    var browserError by remember { mutableStateOf<String?>(null) }
    val connected = auth as? OneDriveAuthorization.Connected

    Text("OneDrive", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (val state = auth) {
                OneDriveAuthorization.Starting -> {
                    Text("正在申请设备码…")
                    TextButton(onClick = { signIn?.cancel(); session.disconnect() }) { Text("取消") }
                }
                is OneDriveAuthorization.DeviceCode -> {
                    Text("请在微软授权页输入设备码。不要把设备码发给他人。")
                    Text(state.userCode, style = MaterialTheme.typography.headlineSmall)
                    Button(onClick = {
                        browserError = if (runCatching { uriHandler.openUri(state.verificationUrl.toString()) }.isSuccess) null
                            else "无法打开系统浏览器。"
                    }) { Text("打开微软授权页") }
                    browserError?.let { Text(it) }
                    TextButton(onClick = { signIn?.cancel(); session.disconnect() }) { Text("取消") }
                }
                is OneDriveAuthorization.Connected -> {
                    TextButton(onClick = { PlaybackHolder.stopOneDrive(); session.disconnect() }) { Text("断开 OneDrive") }
                    OneDriveDirectory(session, state, onPlay = { item ->
                        PlaybackHolder.playOneDrive(context.applicationContext, item.name, item.mime, session.downloads(state.generation, item.id))
                    })
                }
                else -> {
                    Text("仅在本次应用会话中保留授权。填写启用了公开客户端流的 Microsoft Entra 应用 ID；不填密码或密钥。")
                    OutlinedTextField(id, { id = it }, label = { Text("应用程序（客户端）ID") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false))
                    Button(enabled = validOneDriveAppId(id), onClick = {
                        browserError = null
                        signIn = scope.launch { session.authorize(id) }
                    }) { Text("获取设备码") }
                    when (state) {
                        OneDriveAuthorization.Denied -> Text("未批准 OneDrive 授权。")
                        OneDriveAuthorization.Expired -> Text("设备码已过期，请重新获取。")
                        OneDriveAuthorization.SignInAgain -> Text("授权已失效，请重新连接。")
                        is OneDriveAuthorization.Failure -> Text(state.message)
                        else -> Unit
                    }
                }
            }
        }
    }
    LaunchedEffect(auth) {
        if (connected == null) PlaybackHolder.stopOneDrive()
    }
}

@Composable
private fun OneDriveDirectory(session: OneDriveSession, auth: OneDriveAuthorization.Connected, onPlay: (OneDriveItem) -> Unit) {
    var folders by remember(auth.generation) { mutableStateOf(emptyList<OneDriveItem>()) }
    val folder = folders.lastOrNull()
    var page by remember(auth.generation, folders) { mutableStateOf<OneDrivePage?>(null) }
    var error by remember(auth.generation, folders) { mutableStateOf<String?>(null) }
    var loading by remember(auth.generation, folders) { mutableStateOf(true) }
    var reload by remember(auth.generation, folders) { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    suspend fun load(append: Boolean) {
        loading = true
        error = null
        try {
            val loaded = session.list(auth.generation, folder?.id, if (append) page?.next else null)
            page = if (append) loaded.copy(items = (page?.items.orEmpty() + loaded.items).distinctBy { it.id }) else loaded
        } catch (_: IOException) {
            error = "无法读取 OneDrive 目录，请检查授权或网络后重试。"
        } finally {
            loading = false
        }
    }
    LaunchedEffect(auth.generation, folders, reload) { load(false) }
    Text(folder?.name ?: "根目录", style = MaterialTheme.typography.titleMedium)
    Row {
        if (folder != null) TextButton(onClick = { folders = folders.dropLast(1) }, enabled = !loading) { Text("上一级") }
        TextButton(onClick = { reload++ }, enabled = !loading) { Text("刷新") }
    }
    if (loading) Text("正在读取目录…")
    error?.let { Text(it) }
    if (!loading && error == null && page?.items?.isEmpty() == true) Text("此页无文件夹或音频。")
    page?.items?.forEach { item ->
        TextButton(onClick = {
            if (item.folder) folders = folders + item else onPlay(item)
        }, enabled = !loading, modifier = Modifier.fillMaxWidth()) { Text((if (item.folder) "目录：" else "播放：") + item.name) }
    }
    if (page?.next != null) TextButton(onClick = { scope.launch { load(true) } }, enabled = !loading) { Text("加载下一页") }
}
