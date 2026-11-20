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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
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
import com.podcastapp.data.storage.NetworkStorageLibrary
import com.podcastapp.data.storage.SmbCredentials
import com.podcastapp.data.storage.SmbEntry
import com.podcastapp.data.storage.StorageMedia
import com.podcastapp.data.storage.StorageProvider
import com.podcastapp.data.storage.StorageSource
import com.podcastapp.data.storage.WebDavClient
import com.podcastapp.data.storage.WebDavConnectionCheck
import com.podcastapp.data.storage.WebDavEntry
import com.podcastapp.playback.PlaybackHolder
import com.podcastapp.ui.SharedImport
import com.podcastapp.ui.components.InlineIssue
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    // 网络存储凭据只保存在本次组合的局部状态：不进 ViewModel、不落盘、不用 rememberSaveable。
    var storageProvider by remember { mutableStateOf(StorageProvider.WEBDAV) }
    var storageProviderMenuExpanded by remember { mutableStateOf(false) }
    var storageLabel by remember { mutableStateOf("") }
    var storageBaseUrl by remember { mutableStateOf("") }
    var storageRootPath by remember { mutableStateOf("") }
    var storageAuthKind by remember { mutableStateOf(CredentialKind.NONE) }
    var storageAuthMenuExpanded by remember { mutableStateOf(false) }
    var storageUsername by remember { mutableStateOf("") }
    var storageSecret by remember { mutableStateOf("") }
    var storageSmbDomain by remember { mutableStateOf("") }
    var storageHeaderName by remember { mutableStateOf("X-API-Key") }
    var storageMessage by remember { mutableStateOf<String?>(null) }
    var checkingEnterpriseStorage by remember { mutableStateOf(false) }
    var enterpriseStorageCheck by remember { mutableStateOf<String?>(null) }
    val storageLabelFocus = remember { FocusRequester() }
    val storageBaseUrlFocus = remember { FocusRequester() }
    val storageRootPathFocus = remember { FocusRequester() }
    val storageUsernameFocus = remember { FocusRequester() }
    val storageSecretFocus = remember { FocusRequester() }
    val storageSmbDomainFocus = remember { FocusRequester() }
    val storageHeaderNameFocus = remember { FocusRequester() }
    val context = LocalContext.current
    val appContext = context.applicationContext
    val scope = rememberCoroutineScope()
    val networkLibrary = remember(appContext) { NetworkStorageLibrary(appContext) }
    val storageSources by networkLibrary.sources.collectAsState()
    var activeSourceId by remember { mutableStateOf<String?>(null) }
    var browserPathStack by remember { mutableStateOf(listOf<String>()) }
    var browserEntries by remember { mutableStateOf<List<WebDavEntry>>(emptyList()) }
    var smbBrowserEntries by remember { mutableStateOf<List<SmbEntry>>(emptyList()) }
    var browserLoading by remember { mutableStateOf(false) }
    var browserError by remember { mutableStateOf<String?>(null) }
    val currentBrowserPath = browserPathStack.lastOrNull().orEmpty()
    fun loadStorageDirectory(source: StorageSource, path: String) {
        if (activeSourceId != source.id || browserPathStack.lastOrNull() != path) return
        browserLoading = true
        browserError = null
        scope.launch {
            if (source.provider == StorageProvider.SMB) {
                val result = withContext(Dispatchers.IO) {
                    runCatching { networkLibrary.smbClient(source).list(path) }
                }
                if (activeSourceId != source.id || browserPathStack.lastOrNull() != path) return@launch
                browserLoading = false
                result.onSuccess { smbBrowserEntries = it }
                    .onFailure { error ->
                        smbBrowserEntries = emptyList()
                        browserError = "读取目录失败：${error.message ?: "未知错误"}"
                    }
            } else {
                val result = withContext(Dispatchers.IO) {
                    val (creds, transport) = networkLibrary.credentialsFor(source.id)
                    WebDavClient(source.baseUrl, creds, transport).list(path)
                }
                if (activeSourceId != source.id || browserPathStack.lastOrNull() != path) return@launch
                browserLoading = false
                result.onSuccess { browserEntries = it }
                    .onFailure { error ->
                        browserEntries = emptyList()
                        browserError = "读取目录失败：${error.message ?: "未知错误"}"
                    }
            }
        }
    }
    fun openStorageSource(source: StorageSource) {
        val previous = storageSources.firstOrNull { it.id == activeSourceId }
        if (previous != null && previous.id != source.id && previous.provider == StorageProvider.SMB) {
            PlaybackHolder.stopSmb(previous.id)
        }
        activeSourceId = source.id
        browserPathStack = listOf(source.rootPath.trim().trim('/'))
        browserEntries = emptyList()
        smbBrowserEntries = emptyList()
        browserError = null
        loadStorageDirectory(source, browserPathStack.last())
    }
    fun playStorageEntry(source: StorageSource, entry: WebDavEntry) {
        scope.launch {
            val (creds, transport) = networkLibrary.credentialsFor(source.id)
            runCatching {
                val client = WebDavClient(source.baseUrl, creds, transport)
                val media = StorageMedia(
                    sourceId = source.id,
                    url = client.resolveFileUrl(entry.path),
                    title = entry.name,
                    mime = entry.contentType,
                )
                PlaybackHolder.playStorage(appContext, media, creds, transport)
            }.onFailure { error -> browserError = "播放失败：${error.message ?: "未知错误"}" }
        }
    }
    fun playSmbEntry(source: StorageSource, entry: SmbEntry) {
        scope.launch {
            runCatching {
                PlaybackHolder.playSmb(appContext, source.id, entry.name, entry.mime, networkLibrary.smbClient(source), entry.path)
            }.onFailure { error -> browserError = "播放失败：${error.message ?: "未知错误"}" }
        }
    }
    fun currentStorageCredentials(): SourceCredentials = when (storageAuthKind) {
        CredentialKind.NONE -> SourceCredentials()
        CredentialKind.BASIC -> SourceCredentials(kind = CredentialKind.BASIC, username = storageUsername, secret = storageSecret)
        CredentialKind.BEARER -> SourceCredentials(kind = CredentialKind.BEARER, secret = storageSecret)
        CredentialKind.API_KEY -> SourceCredentials(
            kind = CredentialKind.API_KEY,
            secret = storageSecret,
            header = storageHeaderName.ifBlank { "X-API-Key" },
        )
        CredentialKind.DIGEST -> SourceCredentials(kind = CredentialKind.DIGEST, username = storageUsername, secret = storageSecret)
    }
    fun currentStorageTransport() = SourceTransport(digest = storageAuthKind == CredentialKind.DIGEST)
    fun checkEnterpriseStorage() {
        if (checkingEnterpriseStorage) return
        val base = storageBaseUrl.trim()
        val path = storageRootPath
        val credentials = currentStorageCredentials()
        val transport = currentStorageTransport()
        checkingEnterpriseStorage = true
        enterpriseStorageCheck = null
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    runCatching { WebDavClient(base, credentials, transport).testConnection(path).getOrThrow() }
                }
                enterpriseStorageCheck = result.fold({ it.enterpriseMessage() }, { error ->
                    when (error) {
                        is IllegalArgumentException -> "地址或路径无效。请填写企业分配的 WebDAV 地址。"
                        is java.net.UnknownHostException -> "无法解析企业域名，请检查飞连路由和企业 DNS。"
                        is javax.net.ssl.SSLException -> "TLS 校验失败，请检查企业证书配置。"
                        else -> "无法完成连接，请检查飞连是否已连接、本应用的分流策略及目标服务。"
                    }
                })
            } finally {
                checkingEnterpriseStorage = false
            }
        }
    }
    fun addStorageSource() {
        scope.launch {
            val result = if (storageProvider == StorageProvider.SMB) {
                // SMB 凭据独立构造，不塞进 SourceCredentials。
                val smbCredentials = SmbCredentials(storageUsername.trim(), storageSecret, storageSmbDomain.trim())
                // 提交后立即清空一次性凭据，不留在组合状态里。
                storageSecret = ""
                storageUsername = ""
                storageSmbDomain = ""
                withContext(Dispatchers.IO) {
                    networkLibrary.addSmb(
                        label = storageLabel.ifBlank { storageProviderLabel(storageProvider) },
                        address = storageBaseUrl,
                        path = storageRootPath,
                        credentials = smbCredentials,
                    )
                }
            } else {
                val selectedCredentials = currentStorageCredentials()
                val selectedTransport = currentStorageTransport()
                // 提交后立即清空一次性凭据，不留在组合状态里。
                storageSecret = ""
                storageUsername = ""
                storageHeaderName = "X-API-Key"
                withContext(Dispatchers.IO) {
                    networkLibrary.add(
                        provider = storageProvider,
                        label = storageLabel.ifBlank { storageProviderLabel(storageProvider) },
                        baseUrl = storageBaseUrl,
                        rootPath = storageRootPath,
                        credentials = selectedCredentials,
                        transport = selectedTransport,
                    )
                }
            }
            storageMessage = result.fold(
                onSuccess = { "已添加来源：${it.label}" },
                onFailure = { "添加失败：${it.message ?: "未知错误"}" },
            )
        }
    }
    fun removeStorageSource(source: StorageSource) {
        scope.launch {
            if (source.provider == StorageProvider.SMB) PlaybackHolder.stopSmb(source.id)
            val result = runCatching { withContext(Dispatchers.IO) { networkLibrary.remove(source.id) } }
            if (result.isSuccess && activeSourceId == source.id) {
                activeSourceId = null
                browserPathStack = emptyList()
                browserEntries = emptyList()
                smbBrowserEntries = emptyList()
                browserError = null
            }
            storageMessage = result.fold(
                onSuccess = { "已移除来源：${source.label}" },
                onFailure = { "移除失败：${it.message ?: "未知错误"}" },
            )
        }
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
            "默认同时支持 http 与 https，建议优先使用 https。只有勾选“仅允许 HTTPS”时才会拒绝 http 地址及降级跳转；跨主机或降级跳转仍会剥离认证与客户端证书。系统根证书之外还内置 Let’s Encrypt 公共根证书，用于旧设备的 HTTPS 订阅。",
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

        CloudDriveSection()

        Text("网络存储（可选）", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
        Text(
            "添加 WebDAV 或直连 SMB 来源后可直接浏览目录并播放音频；来源信息保存在本机，凭据加密单独保存。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = { storageProviderMenuExpanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text("来源类型：${storageProviderLabel(storageProvider)}")
        }
        DropdownMenu(
            expanded = storageProviderMenuExpanded,
            onDismissRequest = { storageProviderMenuExpanded = false },
            modifier = Modifier.fillMaxWidth(),
        ) {
            StorageProvider.values().forEach { option ->
                DropdownMenuItem(
                    text = { Text(storageProviderLabel(option)) },
                    onClick = {
                        storageProvider = option
                        enterpriseStorageCheck = null
                        storageBaseUrl = ""
                        storageSmbDomain = ""
                        storageSecret = ""
                        storageProviderMenuExpanded = false
                    },
                )
            }
        }
        when (storageProvider) {
            StorageProvider.JIANGUOYUN -> Text(
                "坚果云官方 WebDAV 地址：https://dav.jianguoyun.com/dav/。用户名填注册邮箱，密码请使用坚果云应用密码（不是登录密码）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            StorageProvider.ZSPACE -> Text(
                "极空间：请先在 NAS 的系统设置中开启 WebDAV，再填写实际的 HTTPS/HTTP 地址与根路径；应用不会自动发现设备。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            StorageProvider.FEILIAN -> Text(
                "先在飞连客户端完成企业登录和网络连接，再填写管理员提供的内网 WebDAV 地址。本应用走系统路由，不接受飞连账号密码或浏览器 SSO 页面。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            StorageProvider.SMB -> Text(
                "直连 NAS/Windows 共享的 SMB 2/3。地址填 smb://主机/共享名（可带端口），子目录另填；使用签名会话，不支持 SMB1 或匿名访问。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            StorageProvider.WEBDAV -> Unit
        }
        OutlinedTextField(
            value = storageLabel,
            onValueChange = { storageLabel = it },
            label = { Text("显示名称（可选）") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next, autoCorrect = false, capitalization = KeyboardCapitalization.None),
            keyboardActions = KeyboardActions(onNext = { moveTo(storageBaseUrlFocus) }),
            modifier = Modifier.fillMaxWidth().focusRequester(storageLabelFocus),
        )
        OutlinedTextField(
            value = storageBaseUrl,
            onValueChange = { storageBaseUrl = it },
            label = { Text(if (storageProvider == StorageProvider.SMB) "SMB 地址，例如 smb://192.168.1.10/媒体" else "WebDAV 地址") },
            placeholder = {
                Text(
                    when (storageProvider) {
                        StorageProvider.JIANGUOYUN -> "https://dav.jianguoyun.com/dav/"
                        StorageProvider.SMB -> "smb://192.168.1.10/媒体"
                        else -> "https://example.com/dav/"
                    }
                )
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next, autoCorrect = false, capitalization = KeyboardCapitalization.None),
            keyboardActions = KeyboardActions(onNext = { moveTo(storageRootPathFocus) }),
            modifier = Modifier.fillMaxWidth().focusRequester(storageBaseUrlFocus),
        )
        OutlinedTextField(
            value = storageRootPath,
            onValueChange = { storageRootPath = it },
            label = { Text("根路径（可选）") },
            placeholder = { Text("例如 Music/有声书") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                imeAction = if (storageProvider == StorageProvider.SMB || storageAuthKind != CredentialKind.NONE) ImeAction.Next else ImeAction.Done,
                autoCorrect = false,
                capitalization = KeyboardCapitalization.None,
            ),
            keyboardActions = KeyboardActions(
                onNext = {
                    moveTo(
                        if (storageProvider == StorageProvider.SMB || storageAuthKind == CredentialKind.BASIC || storageAuthKind == CredentialKind.DIGEST) {
                            storageUsernameFocus
                        } else {
                            storageSecretFocus
                        }
                    )
                },
                onDone = { focusManager.clearFocus(); addStorageSource() },
            ),
            modifier = Modifier.fillMaxWidth().focusRequester(storageRootPathFocus),
        )
        if (storageProvider == StorageProvider.SMB) {
            OutlinedTextField(
                value = storageUsername,
                onValueChange = { storageUsername = it },
                label = { Text("SMB 用户名") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next, autoCorrect = false, capitalization = KeyboardCapitalization.None),
                keyboardActions = KeyboardActions(onNext = { moveTo(storageSecretFocus) }),
                modifier = Modifier.fillMaxWidth().focusRequester(storageUsernameFocus),
            )
            SecureField(
                value = storageSecret,
                onValueChange = { storageSecret = it },
                label = "SMB 密码",
                imeAction = ImeAction.Next,
                keyboardActions = KeyboardActions(onNext = { moveTo(storageSmbDomainFocus) }),
                focusRequester = storageSecretFocus,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = storageSmbDomain,
                onValueChange = { storageSmbDomain = it },
                label = { Text("SMB 域/工作组（可选）") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, autoCorrect = false, capitalization = KeyboardCapitalization.None),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus(); addStorageSource() }),
                modifier = Modifier.fillMaxWidth().focusRequester(storageSmbDomainFocus),
            )
        } else {
            Button(onClick = { storageAuthMenuExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text("来源凭据：${credentialKindLabel(storageAuthKind)}")
            }
            DropdownMenu(
                expanded = storageAuthMenuExpanded,
                onDismissRequest = { storageAuthMenuExpanded = false },
                modifier = Modifier.fillMaxWidth(),
            ) {
                CredentialKind.values().forEach { option ->
                    DropdownMenuItem(
                        text = { Text(credentialKindLabel(option)) },
                        onClick = {
                            storageAuthKind = option
                            storageSecret = ""
                            storageUsername = ""
                            storageAuthMenuExpanded = false
                        },
                    )
                }
            }
            if (storageAuthKind == CredentialKind.BASIC || storageAuthKind == CredentialKind.DIGEST) {
                OutlinedTextField(
                    value = storageUsername,
                    onValueChange = { storageUsername = it },
                    label = { Text("用户名") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next, autoCorrect = false, capitalization = KeyboardCapitalization.None),
                    keyboardActions = KeyboardActions(onNext = { moveTo(storageSecretFocus) }),
                    modifier = Modifier.fillMaxWidth().focusRequester(storageUsernameFocus),
                )
            }
            if (storageAuthKind != CredentialKind.NONE) {
                SecureField(
                    value = storageSecret,
                    onValueChange = { storageSecret = it },
                    label = when (storageAuthKind) {
                        CredentialKind.BASIC -> "密码"
                        CredentialKind.BEARER -> "Bearer Token"
                        CredentialKind.API_KEY -> "API Key"
                        CredentialKind.DIGEST -> "Digest 密码"
                        CredentialKind.NONE -> ""
                    },
                    imeAction = if (storageAuthKind == CredentialKind.API_KEY) ImeAction.Next else ImeAction.Done,
                    keyboardActions = KeyboardActions(
                        onNext = { moveTo(storageHeaderNameFocus) },
                        onDone = { focusManager.clearFocus(); addStorageSource() },
                    ),
                    focusRequester = storageSecretFocus,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (storageAuthKind == CredentialKind.API_KEY) {
                OutlinedTextField(
                    value = storageHeaderName,
                    onValueChange = { storageHeaderName = it },
                    label = { Text("API Key 头名") },
                    placeholder = { Text("X-API-Key") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, autoCorrect = false, capitalization = KeyboardCapitalization.None),
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus(); addStorageSource() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(storageHeaderNameFocus),
                )
            }
            if (storageProvider == StorageProvider.FEILIAN) {
                TextButton(
                    onClick = { focusManager.clearFocus(); checkEnterpriseStorage() },
                    enabled = storageBaseUrl.isNotBlank() && !checkingEnterpriseStorage,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (checkingEnterpriseStorage) "正在测试…" else "测试企业 WebDAV（不保存）") }
                enterpriseStorageCheck?.let { Text("上次测试：$it", style = MaterialTheme.typography.bodySmall) }
            }
        }
        Button(
            onClick = { focusManager.clearFocus(); addStorageSource() },
            enabled = storageProvider == StorageProvider.JIANGUOYUN || storageBaseUrl.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("添加来源") }
        storageMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        Text("已保存网络存储来源", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
        if (storageSources.isEmpty()) {
            Text("还没有网络存储来源。", style = MaterialTheme.typography.bodyMedium)
        }
        storageSources.forEach { source ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(source.label, style = MaterialTheme.typography.titleMedium)
                    Text("${storageProviderLabel(source.provider)} · ${source.baseUrl}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (source.rootPath.isNotBlank()) {
                        Text("根路径：${source.rootPath}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            if (activeSourceId == source.id) {
                                activeSourceId = null
                                browserPathStack = emptyList()
                                browserEntries = emptyList()
                                smbBrowserEntries = emptyList()
                                browserError = null
                                if (source.provider == StorageProvider.SMB) PlaybackHolder.stopSmb(source.id)
                            } else {
                                openStorageSource(source)
                            }
                        }) { Text(if (activeSourceId == source.id) "收起目录" else "浏览目录") }
                        TextButton(onClick = { removeStorageSource(source) }) { Text("移除") }
                    }
                    if (activeSourceId == source.id) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "目录：${if (currentBrowserPath.isBlank()) "/" else "/$currentBrowserPath"}",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                onClick = {
                                    if (browserPathStack.size > 1) {
                                        browserPathStack = browserPathStack.dropLast(1)
                                        loadStorageDirectory(source, browserPathStack.last())
                                    } else {
                                        activeSourceId = null
                                        browserPathStack = emptyList()
                                        browserEntries = emptyList()
                                        smbBrowserEntries = emptyList()
                                        browserError = null
                                        if (source.provider == StorageProvider.SMB) PlaybackHolder.stopSmb(source.id)
                                    }
                                },
                                enabled = !browserLoading,
                            ) { Text("返回") }
                            TextButton(onClick = { loadStorageDirectory(source, currentBrowserPath) }, enabled = !browserLoading) { Text("刷新") }
                        }
                        if (browserLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                        browserError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                        val entriesEmpty = if (source.provider == StorageProvider.SMB) smbBrowserEntries.isEmpty() else browserEntries.isEmpty()
                        if (!browserLoading && browserError == null && entriesEmpty) {
                            Text("此目录为空或没有可访问的条目。", style = MaterialTheme.typography.bodySmall)
                        }
                        if (source.provider == StorageProvider.SMB) {
                            smbBrowserEntries.forEach { entry ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(entry.name, style = MaterialTheme.typography.bodyMedium)
                                        val detail = entry.size?.takeIf { it > 0 }?.let { "%.1f MB".format(it / 1_048_576.0) }.orEmpty()
                                        if (detail.isNotBlank()) {
                                            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                    when {
                                        entry.directory -> TextButton(onClick = {
                                            browserPathStack = browserPathStack + entry.path
                                            loadStorageDirectory(source, entry.path)
                                        }) { Text("进入") }
                                        entry.mime != null -> TextButton(onClick = { playSmbEntry(source, entry) }) { Text("播放") }
                                    }
                                }
                            }
                        } else {
                            browserEntries.forEach { entry ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(entry.name, style = MaterialTheme.typography.bodyMedium)
                                        val detail = listOfNotNull(
                                            entry.size?.takeIf { it > 0 }?.let { "%.1f MB".format(it / 1_048_576.0) },
                                            entry.contentType,
                                        ).joinToString(" · ")
                                        if (detail.isNotBlank()) {
                                            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                    when {
                                        entry.directory -> TextButton(onClick = {
                                            browserPathStack = browserPathStack + entry.path
                                            loadStorageDirectory(source, entry.path)
                                        }) { Text("进入") }
                                        isAudioEntry(entry) -> TextButton(onClick = { playStorageEntry(source, entry) }) { Text("播放") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
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

private fun storageProviderLabel(provider: StorageProvider): String = when (provider) {
    StorageProvider.WEBDAV -> "通用 WebDAV"
    StorageProvider.JIANGUOYUN -> "坚果云"
    StorageProvider.ZSPACE -> "极空间"
    StorageProvider.FEILIAN -> "飞连企业网络（WebDAV）"
    StorageProvider.SMB -> "直连 SMB 2/3（NAS）"
}

private fun WebDavConnectionCheck.enterpriseMessage() = when (this) {
    WebDavConnectionCheck.READY -> "已收到有效 WebDAV 响应；这不是飞连登录状态。"
    WebDavConnectionCheck.AUTH_REQUIRED -> "已到达服务，但需要该 WebDAV 来源自己的凭据。"
    WebDavConnectionCheck.FORBIDDEN -> "已到达服务，但访问被拒绝，请核对企业策略和目录权限。"
    WebDavConnectionCheck.REDIRECT -> "服务要求跳转（可能是网页统一登录），请使用直连 WebDAV 地址。"
    WebDavConnectionCheck.NOT_WEBDAV -> "未收到有效 WebDAV 响应，请核对路径，不要填写网页登录页。"
    WebDavConnectionCheck.BAD_RESPONSE -> "服务返回异常状态，请检查企业网关或 WebDAV 服务。"
}

private fun isAudioEntry(entry: WebDavEntry): Boolean {
    val mime = entry.contentType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
    if (mime?.startsWith("audio/") == true) return true
    if (mime?.startsWith("video/") == true) return false
    val extension = entry.name.substringAfterLast('.').lowercase(Locale.ROOT)
    return extension in setOf("mp3", "m4a", "aac", "ogg", "oga", "opus", "wav", "wave", "flac", "aiff", "aif", "wma")
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
