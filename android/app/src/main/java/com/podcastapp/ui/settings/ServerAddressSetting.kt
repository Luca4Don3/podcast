package com.podcastapp.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.podcastapp.data.Session
import com.podcastapp.ui.AppViewModel

private enum class ServerAddressDialog { Address, LinkImport }

@Composable
fun ServerAddressSetting(viewModel: AppViewModel, session: Session, busy: Boolean) {
    var dialog by remember { mutableStateOf<ServerAddressDialog?>(null) }
    var address by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    val working = busy || saving
    val status = when {
        session.baseUrl.isBlank() -> "未连接 · 未设置地址"
        session.customBaseUrl == null -> "默认 · ${session.baseUrl}"
        else -> "自定义 · ${session.baseUrl}"
    }

    val dismiss = {
        dialog = null
        viewModel.clearServerFeedback()
    }

    ListItem(
        headlineContent = { Text("服务器地址（可选）") },
        supportingContent = { Text(status, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        trailingContent = { Icon(Icons.Filled.ChevronRight, contentDescription = null) },
        modifier = Modifier.fillMaxWidth().clickable(enabled = !working, role = Role.Button) {
            address = session.customBaseUrl.orEmpty()
            viewModel.clearServerFeedback()
            dialog = ServerAddressDialog.Address
        },
    )

    if (dialog == ServerAddressDialog.Address) {
        val feedback by viewModel.serverFeedback.collectAsState()
        val defaultUrl = viewModel.defaultServerUrl
        val canTest = !working && (address.isNotBlank() || defaultUrl.isNotBlank())
        AlertDialog(
            onDismissRequest = { if (!working) dismiss() },
            title = { Text("服务器地址（可选）") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = address,
                        onValueChange = {
                            address = it
                            viewModel.clearServerFeedback()
                        },
                        enabled = !working,
                        label = { Text("自定义地址") },
                        placeholder = { Text("https://podcast.example.com/") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        if (defaultUrl.isBlank()) "留空后不连接服务器。"
                        else "留空则使用默认地址：$defaultUrl",
                    )
                    Text("支持 HTTPS 和显式 http:// 地址。使用 Tailscale 时，先连接手机上的 Tailscale，再填写服务器的 MagicDNS 名称或 IP 与端口。")
                    Text("这里需要播客后端的 API 地址，不是 NAS 管理页或网盘分享页。更换服务器不会删除原有云端数据。")
                    if (address.trimStart().startsWith("http://", ignoreCase = true) ||
                        (address.isBlank() && defaultUrl.startsWith("http://"))) {
                        Text("HTTP 本身不加密，只在可信内网或明确受保护的路径使用；推荐 HTTPS。", color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(
                        enabled = !working,
                        onClick = {
                            if (!saving && !viewModel.busy.value) {
                                viewModel.clearServerFeedback()
                                dialog = ServerAddressDialog.LinkImport
                            }
                        },
                    ) { Text("从链接导入") }
                    TextButton(
                        enabled = canTest,
                        onClick = {
                            viewModel.clearServerFeedback()
                            viewModel.testServer(address)
                        },
                    ) { Text("测试连接") }
                    if (working) {
                        Text("正在处理…", style = MaterialTheme.typography.bodySmall)
                    }
                    feedback?.let {
                        Text(it, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = !working, onClick = {
                    if (!saving) {
                        saving = true
                        viewModel.clearServerFeedback()
                        viewModel.applyServer(address) { success ->
                            saving = false
                            if (success) dismiss()
                        }
                    }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(enabled = !working, onClick = dismiss) { Text("取消") } },
        )
    } else if (dialog == ServerAddressDialog.LinkImport) {
        ServerLinkImportDialog(
            viewModel = viewModel,
            busy = busy,
            onBack = {
                viewModel.clearServerFeedback()
                dialog = ServerAddressDialog.Address
            },
            onSuccess = dismiss,
        )
    }
}
