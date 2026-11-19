package com.podcastapp.ui.discover

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.podcastapp.data.feed.CredentialKind
import com.podcastapp.data.feed.SourceCredentials
import com.podcastapp.data.feed.SourceTransport
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
    // 传输设置同样只保存在本次组合的局部状态：不进入 ViewModel 的 UI state、不落盘、不使用 rememberSaveable。
    // 客户端私钥与私钥密码尤其敏感：只在这里短暂驻留，提交后立即清空。
    var requireHttps by remember { mutableStateOf(false) }
    var digest by remember { mutableStateOf(false) }
    var customCaPem by remember { mutableStateOf("") }
    var clientCertPem by remember { mutableStateOf("") }
    var clientKeyPem by remember { mutableStateOf("") }
    var clientKeyPassword by remember { mutableStateOf("") }
    // 焦点只用于系统输入法的 Next 导航，不保存任何输入内容。
    val focusManager = LocalFocusManager.current
    val usernameFocus = remember { FocusRequester() }
    val secretFocus = remember { FocusRequester() }
    val headerNameFocus = remember { FocusRequester() }
    val caFocus = remember { FocusRequester() }
    val certFocus = remember { FocusRequester() }
    val clientKeyFocus = remember { FocusRequester() }
    val clientKeyPasswordFocus = remember { FocusRequester() }
    fun moveTo(target: FocusRequester) {
        runCatching { target.requestFocus() }
    }
    val credentials = when (authKind) {
        CredentialKind.NONE -> SourceCredentials()
        CredentialKind.BASIC -> SourceCredentials(kind = CredentialKind.BASIC, username = username, secret = secret)
        CredentialKind.BEARER -> SourceCredentials(kind = CredentialKind.BEARER, secret = secret)
        CredentialKind.API_KEY -> SourceCredentials(
            kind = CredentialKind.API_KEY,
            secret = secret,
            header = headerName.ifBlank { "X-API-Key" },
        )
        // Digest 只带用户名/口令，不生成静态 Authorization 头；由服务端 401 挑战后再计算。
        CredentialKind.DIGEST -> SourceCredentials(kind = CredentialKind.DIGEST, username = username, secret = secret)
    }
    val transport = SourceTransport(
        requireHttps = requireHttps,
        customCaPem = customCaPem,
        clientCertPem = clientCertPem,
        clientKeyPem = clientKeyPem,
        clientKeyPassword = clientKeyPassword,
        digest = digest,
    )
    val startImport = {
        val selectedCredentials = credentials
        val selectedTransport = transport
        // 提交后立即清空一次性凭据与敏感传输材料，不留在组合状态里。
        secret = ""
        clientKeyPem = ""
        clientKeyPassword = ""
        viewModel.`import`(selectedCredentials, selectedTransport)
    }
    fun submitAndHideKeyboard() {
        focusManager.clearFocus()
        if (state.input.isNotBlank()) startImport()
    }
    Column(
        // 外层已按 safeDrawing 预留系统栏；此处只补 IME 高度，避免叠加导航条留白。
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 16.dp, vertical = 20.dp),
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
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Done,
                autoCorrect = false,
                capitalization = KeyboardCapitalization.None,
            ),
            keyboardActions = KeyboardActions(onDone = { submitAndHideKeyboard() }),
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
                        // 认证方式与传输层的 Digest 开关保持一致。
                        digest = option == CredentialKind.DIGEST
                        secret = ""
                        authMenuExpanded = false
                    },
                )
            }
        }
        if (authKind == CredentialKind.BASIC || authKind == CredentialKind.DIGEST) {
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                enabled = !state.loading,
                label = { Text("用户名") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    imeAction = ImeAction.Next,
                    autoCorrect = false,
                    capitalization = KeyboardCapitalization.None,
                ),
                keyboardActions = KeyboardActions(onNext = { moveTo(secretFocus) }),
                modifier = Modifier.fillMaxWidth().focusRequester(usernameFocus),
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
                            CredentialKind.DIGEST -> "Digest 密码"
                            CredentialKind.NONE -> ""
                        }
                    )
                },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = if (authKind == CredentialKind.API_KEY) ImeAction.Next else ImeAction.Done,
                    autoCorrect = false,
                    capitalization = KeyboardCapitalization.None,
                ),
                keyboardActions = KeyboardActions(
                    onNext = { moveTo(headerNameFocus) },
                    onDone = { submitAndHideKeyboard() },
                ),
                singleLine = true,
                modifier = Modifier.fillMaxWidth().focusRequester(secretFocus),
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
                keyboardOptions = KeyboardOptions(
                    imeAction = ImeAction.Next,
                    autoCorrect = false,
                    capitalization = KeyboardCapitalization.None,
                ),
                keyboardActions = KeyboardActions(onNext = { moveTo(caFocus) }),
                modifier = Modifier.fillMaxWidth().focusRequester(headerNameFocus),
            )
        }

        Text("传输加密（可选）", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
        Text(
            "默认同时支持 http 与 https，建议优先使用 https。只有勾选“仅允许 HTTPS”时才会拒绝 http 地址及降级跳转；跨主机或降级跳转仍会剥离认证与客户端证书。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = requireHttps,
                onCheckedChange = { requireHttps = it },
                enabled = !state.loading,
            )
            Text("仅允许 HTTPS（可选，默认关闭）")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = digest,
                onCheckedChange = { checked ->
                    digest = checked
                    if (checked) {
                        authKind = CredentialKind.DIGEST
                    } else if (authKind == CredentialKind.DIGEST) {
                        authKind = CredentialKind.NONE
                    }
                },
                enabled = !state.loading,
            )
            Text("Digest 认证（仅服务端 401 挑战后计算，不发送静态 Authorization 头）")
        }
        Text(
            "自签名或私有 CA 证书需你自行在系统/设备中信任；应用不会关闭证书校验。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = customCaPem,
            onValueChange = { customCaPem = it },
            enabled = !state.loading,
            label = { Text("自定义 CA 证书（PEM）") },
            placeholder = { Text("-----BEGIN CERTIFICATE-----") },
            minLines = 3,
            keyboardOptions = KeyboardOptions(
                imeAction = ImeAction.Next,
                autoCorrect = false,
                capitalization = KeyboardCapitalization.None,
            ),
            keyboardActions = KeyboardActions(onNext = { moveTo(certFocus) }),
            modifier = Modifier.fillMaxWidth().focusRequester(caFocus),
        )
        OutlinedTextField(
            value = clientCertPem,
            onValueChange = { clientCertPem = it },
            enabled = !state.loading,
            label = { Text("mTLS 客户端证书（PEM）") },
            placeholder = { Text("-----BEGIN CERTIFICATE-----") },
            minLines = 3,
            keyboardOptions = KeyboardOptions(
                imeAction = ImeAction.Next,
                autoCorrect = false,
                capitalization = KeyboardCapitalization.None,
            ),
            keyboardActions = KeyboardActions(onNext = { moveTo(clientKeyFocus) }),
            modifier = Modifier.fillMaxWidth().focusRequester(certFocus),
        )
        SecureField(
            value = clientKeyPem,
            onValueChange = { clientKeyPem = it },
            enabled = !state.loading,
            label = "mTLS 客户端私钥（PEM）",
            imeAction = ImeAction.Next,
            keyboardActions = KeyboardActions(onNext = { moveTo(clientKeyPasswordFocus) }),
            focusRequester = clientKeyFocus,
            modifier = Modifier.fillMaxWidth(),
        )
        SecureField(
            value = clientKeyPassword,
            onValueChange = { clientKeyPassword = it },
            enabled = !state.loading,
            label = "客户端私钥密码（可选）",
            keyboardActions = KeyboardActions(onDone = { submitAndHideKeyboard() }),
            focusRequester = clientKeyPasswordFocus,
            modifier = Modifier.fillMaxWidth(),
        )

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

/** 敏感文本输入：掩码显示，值由调用方持有，不进入 ViewModel/持久化状态。 */
@Composable
private fun SecureField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    enabled: Boolean = true,
    imeAction: ImeAction = ImeAction.Done,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    focusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier,
) {
    val fieldModifier = if (focusRequester == null) modifier else modifier.focusRequester(focusRequester)
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        label = { Text(label) },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            imeAction = imeAction,
            autoCorrect = false,
            capitalization = KeyboardCapitalization.None,
        ),
        keyboardActions = keyboardActions,
        modifier = fieldModifier,
    )
}

private fun credentialKindLabel(kind: CredentialKind): String = when (kind) {
    CredentialKind.NONE -> "无"
    CredentialKind.BASIC -> "Basic 用户名密码"
    CredentialKind.BEARER -> "Bearer Token"
    CredentialKind.API_KEY -> "API Key"
    CredentialKind.DIGEST -> "Digest 用户名密码"
}

private fun durationLabel(seconds: Int): String {
    if (seconds <= 0) return ""
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val rest = seconds % 60
    return if (hours > 0) "$hours:" + "%02d:%02d".format(minutes, rest)
    else "$minutes:" + "%02d".format(rest)
}
