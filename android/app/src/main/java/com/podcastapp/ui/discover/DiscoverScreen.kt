package com.podcastapp.ui.discover

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.podcastapp.data.feed.CredentialKind
import com.podcastapp.data.feed.SourceCredentials
import com.podcastapp.data.feed.platformLabel
import com.podcastapp.ui.SharedImport
import com.podcastapp.ui.components.InlineIssue

@Composable
@Suppress("UNUSED_PARAMETER")
fun DiscoverScreen(
    onOpenPodcast: (String) -> Unit,
    onOpenFileMedia: () -> Unit,
    initialImport: SharedImport? = null,
    onInitialImportConsumed: () -> Unit = {},
    viewModel: PlatformImportViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(initialImport?.id) {
        if (initialImport != null) {
            viewModel.acceptShared(initialImport.text)
            onInitialImportConsumed()
        }
    }
    // 认证输入只保存在本次组合的局部状态：不进入 ViewModel 状态、不落盘、不使用 rememberSaveable。
    var authKind by remember { mutableStateOf(CredentialKind.NONE) }
    var username by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var headerName by remember { mutableStateOf("X-API-Key") }
    var authMenuExpanded by remember { mutableStateOf(false) }
    val credentials = when (authKind) {
        CredentialKind.NONE -> SourceCredentials()
        CredentialKind.BASIC -> SourceCredentials(kind = CredentialKind.BASIC, username = username, secret = secret)
        CredentialKind.BEARER -> SourceCredentials(kind = CredentialKind.BEARER, secret = secret)
        CredentialKind.API_KEY -> SourceCredentials(
            kind = CredentialKind.API_KEY,
            secret = secret,
            header = headerName.ifBlank { "X-API-Key" },
        )
    }
    val startImport = {
        val selected = credentials
        secret = ""
        viewModel.`import`(selected)
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("导入", Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineMedium)
        Button(onClick = onOpenFileMedia, modifier = Modifier.fillMaxWidth()) { Text("文件音源") }

        Text("平台链接导入", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
        Text(
            "粘贴公开 RSS/OPML 地址或 Apple 播客分享链接；Apple 链接通过官方接口查询公开 RSS。",
            style = MaterialTheme.typography.bodyLarge,
        )
        OutlinedTextField(
            value = state.input,
            onValueChange = viewModel::setInput,
            enabled = !state.loading,
            label = { Text("节目链接") },
            supportingText = {
                Text(
                    state.platformHint.ifBlank { "以 https:// 或 http:// 开头" },
                    color = if (state.platformHint.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.primary,
                )
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (state.input.isNotBlank()) startImport() }),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Text("来源认证（可选）", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
        Text(
            "凭据只随请求发送给该 feed 的主机，不经过任何第三方；公开 feed 请选择“无”。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = { authMenuExpanded = true },
            enabled = !state.loading,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("认证方式：${credentialKindLabel(authKind)}")
        }
        DropdownMenu(
            expanded = authMenuExpanded,
            onDismissRequest = { authMenuExpanded = false },
            modifier = Modifier.fillMaxWidth(),
        ) {
            CredentialKind.values().forEach { option ->
                DropdownMenuItem(
                    text = { Text(credentialKindLabel(option)) },
                    onClick = {
                        authKind = option
                        secret = ""
                        authMenuExpanded = false
                    },
                )
            }
        }
        if (authKind == CredentialKind.BASIC) {
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                enabled = !state.loading,
                label = { Text("用户名") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (authKind != CredentialKind.NONE) {
            OutlinedTextField(
                value = secret,
                onValueChange = { secret = it },
                enabled = !state.loading,
                label = {
                    Text(
                        when (authKind) {
                            CredentialKind.BASIC -> "密码"
                            CredentialKind.BEARER -> "Bearer Token"
                            CredentialKind.API_KEY -> "API Key"
                            CredentialKind.NONE -> ""
                        }
                    )
                },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (authKind == CredentialKind.API_KEY) {
            OutlinedTextField(
                value = headerName,
                onValueChange = { headerName = it },
                enabled = !state.loading,
                label = { Text("API Key 头名") },
                placeholder = { Text("X-API-Key") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        val error = state.error
        if (error != null) {
            InlineIssue(
                message = error,
                onRetry = { startImport() },
                onDismiss = { viewModel.setInput(state.input) },
            )
        } else {
            Button(
                onClick = { startImport() },
                enabled = !state.loading && state.input.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state.loading) "导入中…" else "导入并订阅")
            }
        }
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        Text(
            "只导入你有权使用的公开音源。Spotify/小宇宙/喜马拉雅等页面需提供节目公开 RSS 地址；不绕过付费、地区限制或 DRM。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text("我的本地订阅", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
        if (state.feeds.isEmpty()) {
            Text("还没有本地订阅。导入公开 RSS 后即可在此浏览和播放。", style = MaterialTheme.typography.bodyMedium)
        }
        state.feeds.forEach { feed ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(feed.platformLabel(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text(feed.podcast.title, style = MaterialTheme.typography.titleMedium)
                    if (feed.podcast.description.isNotBlank()) {
                        Text(feed.podcast.description, style = MaterialTheme.typography.bodySmall)
                    }
                    Text(feed.feedUrl, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { viewModel.refresh(feed.id) }) { Text("刷新") }
                        TextButton(onClick = { viewModel.remove(feed.id) }) { Text("移除") }
                    }
                    feed.episodes.forEach { episode ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(episode.title, style = MaterialTheme.typography.bodyMedium)
                                val length = durationLabel(episode.durationSeconds)
                                if (length.isNotBlank()) {
                                    Text(length, style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            TextButton(onClick = { viewModel.play(feed, episode) }) { Text("播放") }
                        }
                    }
                }
            }
        }
    }
}

private fun credentialKindLabel(kind: CredentialKind): String = when (kind) {
    CredentialKind.NONE -> "无"
    CredentialKind.BASIC -> "Basic 用户名密码"
    CredentialKind.BEARER -> "Bearer Token"
    CredentialKind.API_KEY -> "API Key"
}

private fun durationLabel(seconds: Int): String {
    if (seconds <= 0) return ""
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val rest = seconds % 60
    return if (hours > 0) "$hours:" + "%02d:%02d".format(minutes, rest)
    else "$minutes:" + "%02d".format(rest)
}
