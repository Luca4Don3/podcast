package com.podcastapp.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.podcastapp.BuildConfig
import com.podcastapp.data.Session
import com.podcastapp.ui.AppViewModel

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    viewModel: AppViewModel,
    session: Session,
    busy: Boolean,
    settingsViewModel: SettingsViewModel = viewModel(),
) {
    val settings by settingsViewModel.state.collectAsState()
    val changingPassword by settingsViewModel.changingPassword.collectAsState()
    var displayName by remember(session.displayName) { mutableStateOf(session.displayName.orEmpty()) }
    var currentPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var showLogout by remember { mutableStateOf(false) }

    val message by settingsViewModel.message.collectAsState()
    LaunchedEffect(Unit) { settingsViewModel.load() }
    LaunchedEffect(message) {
        message?.let {
            viewModel.showToast(it)
            settingsViewModel.consumeMessage()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("账号", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(session.email.orEmpty(), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = displayName,
            onValueChange = { displayName = it },
            label = { Text("昵称") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { settingsViewModel.updateProfile(displayName) }) { Text("保存昵称") }
        }

        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        ServerAddressSetting(viewModel = viewModel, session = session, busy = busy)

        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Text("播放", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text("播放速度：" + settings.playbackSpeed + "x", style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0.8, 1.0, 1.25, 1.5, 2.0).forEach { speed ->
                OutlinedButton(onClick = { settingsViewModel.saveSettings(settings.copy(playbackSpeed = speed)) }) {
                    Text(speed.toString() + "x")
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "快退 " + settings.skipBack + " 秒 / 快进 " + settings.skipForward + " 秒",
            style = MaterialTheme.typography.bodyMedium,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                settingsViewModel.saveSettings(settings.copy(skipBack = 15, skipForward = 30))
            }) { Text("15 / 30") }
            OutlinedButton(onClick = {
                settingsViewModel.saveSettings(settings.copy(skipBack = 30, skipForward = 30))
            }) { Text("30 / 30") }
        }

        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Text("修改密码", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = currentPassword,
            onValueChange = { currentPassword = it },
            label = { Text("当前密码") },
            singleLine = true,
            enabled = !changingPassword && !busy,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = newPassword,
            onValueChange = { newPassword = it },
            label = { Text("新密码（至少 8 位）") },
            singleLine = true,
            enabled = !changingPassword && !busy,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(enabled = !changingPassword && !busy, onClick = {
                settingsViewModel.changePassword(currentPassword, newPassword) {
                    currentPassword = ""
                    newPassword = ""
                }
            }) { Text(if (changingPassword) "正在更新…" else "更新密码") }
        }

        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Button(enabled = !busy && !changingPassword, onClick = { showLogout = true }, modifier = Modifier.fillMaxWidth()) { Text("退出登录") }
        Spacer(Modifier.height(16.dp))
        Text(
            "版本 " + BuildConfig.VERSION_NAME,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(100.dp))
    }

    if (showLogout) {
        AlertDialog(
            onDismissRequest = { showLogout = false },
            title = { Text("退出登录") },
            text = { Text("退出后可随时重新登录，本地不会保留密码。") },
            confirmButton = {
                TextButton(onClick = {
                    showLogout = false
                    viewModel.logout()
                }) { Text("退出") }
            },
            dismissButton = { TextButton(onClick = { showLogout = false }) { Text("取消") } },
        )
    }
}
