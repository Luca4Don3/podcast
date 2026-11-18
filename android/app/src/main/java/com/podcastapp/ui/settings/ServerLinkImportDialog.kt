package com.podcastapp.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.podcastapp.data.serversetup.ServerSetupLink
import com.podcastapp.ui.AppViewModel

@Composable
internal fun ServerLinkImportDialog(
    viewModel: AppViewModel,
    busy: Boolean,
    onBack: () -> Unit,
    onSuccess: () -> Unit,
) {
    // Neither the raw link nor the parsed credentials belong in saved instance state.
    var raw by remember { mutableStateOf("") }
    var link by remember { mutableStateOf<ServerSetupLink?>(null) }
    var invalidLink by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    var submitFailed by remember { mutableStateOf(false) }
    val hasFeedback = viewModel.serverFeedback.collectAsState().value != null
    val preview = link
    val working = busy || submitting

    fun canInteract() = !working && !submitting && !viewModel.busy.value

    Dialog(
        onDismissRequest = { if (canInteract()) onBack() },
        properties = DialogProperties(
            dismissOnBackPress = !working,
            dismissOnClickOutside = false,
            securePolicy = SecureFlagPolicy.SecureOn,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        val focusManager = LocalFocusManager.current
        val scroll = key(preview == null) { rememberScrollState() }
        Box(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge,
                tonalElevation = 6.dp,
            ) {
                Column(
                    modifier = Modifier.verticalScroll(scroll).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "从链接导入",
                        modifier = Modifier.semantics { heading() },
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text("请核对您信任的播客服务器地址。普通地址可用 HTTP 或 HTTPS；含密码的链接仅支持 HTTPS。导入成功会替换当前配置。")

                    if (preview == null) {
                        Text("请长按输入框手动粘贴链接。识别只在本机进行，不会连接服务器。")
                        OutlinedTextField(
                            value = raw,
                            onValueChange = {
                                raw = it
                                invalidLink = false
                            },
                            enabled = !working,
                            isError = invalidLink,
                            label = { Text("服务器链接") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (invalidLink) {
                            ImportStatus("无法识别链接，请检查后重试。")
                        }
                        Button(
                            enabled = !working && raw.isNotBlank(),
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                if (canInteract() && raw.isNotBlank()) {
                                    val parsed = try {
                                        ServerSetupLink.parse(raw)
                                    } catch (_: IllegalArgumentException) {
                                        null
                                    }
                                    invalidLink = parsed == null
                                    if (parsed != null) {
                                        raw = ""
                                        focusManager.clearFocus()
                                        link = parsed
                                    }
                                }
                            },
                        ) { Text("识别链接") }
                    } else {
                        val withAccount = preview.email != null
                        val previewStyle = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Ltr)
                        Text("服务器地址", style = MaterialTheme.typography.labelLarge)
                        Text(preview.baseUrl, style = previewStyle)
                        Text("账号", style = MaterialTheme.typography.labelLarge)
                        Text(preview.email ?: "未包含账号", style = previewStyle)
                        Text(
                            if (withAccount) "确认后会将账号和密码发送到上述服务器并登录。"
                            else "仅保存上述地址，不会登录。",
                        )
                        if (working) {
                            ImportStatus("正在处理…")
                        } else if (submitFailed || hasFeedback) {
                            // Do not display server or exception text that could echo the link or credentials.
                            ImportStatus(
                                if (withAccount) "导入失败，请检查服务器、账号和密码后重试。"
                                else "保存失败，请检查服务器地址后重试。",
                            )
                        }
                        Button(
                            enabled = !working,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                if (canInteract() && link === preview) {
                                    submitting = true
                                    submitFailed = false
                                    viewModel.clearServerFeedback()
                                    val done: (Boolean) -> Unit = { success ->
                                        submitting = false
                                        if (success) {
                                            link = null
                                            onSuccess()
                                        } else {
                                            submitFailed = true
                                        }
                                    }
                                    if (withAccount) viewModel.importServer(preview, done)
                                    else viewModel.applyServer(preview.baseUrl, done)
                                }
                            },
                        ) { Text(if (withAccount) "确认导入并登录" else "仅保存地址") }
                        TextButton(
                            enabled = !working,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                if (canInteract()) {
                                    link = null
                                    submitFailed = false
                                    viewModel.clearServerFeedback()
                                }
                            },
                        ) { Text("重新粘贴") }
                    }
                    TextButton(
                        enabled = !working,
                        onClick = { if (canInteract()) onBack() },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("返回服务器地址") }
                }
            }
        }
    }
}

@Composable
private fun ImportStatus(message: String) {
    Text(message, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
}
