package com.podcastapp.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.podcastapp.BuildConfig

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    settingsViewModel: SettingsViewModel = viewModel(),
) {
    val settings by settingsViewModel.state.collectAsState()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("偏好设置", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))

        Text("播放速度：" + settings.playbackSpeed + "x", style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0.8, 1.0, 1.25, 1.5, 2.0).forEach { speed ->
                OutlinedButton(onClick = { settingsViewModel.saveSettings(settings.copy(playbackSpeed = speed)) }) {
                    Text(speed.toString() + "x")
                }
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Text("跳转秒数", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "快退 " + settings.skipBack + " 秒 / 快进 " + settings.skipForward + " 秒",
            style = MaterialTheme.typography.bodyMedium,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(15 to 30, 30 to 30, 10 to 20).forEach { option ->
                OutlinedButton(onClick = {
                    settingsViewModel.saveSettings(
                        settings.copy(skipBack = option.first, skipForward = option.second),
                    )
                }) { Text(option.first.toString() + " / " + option.second.toString()) }
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Text("主题", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色").forEach { option ->
                OutlinedButton(onClick = { settingsViewModel.saveSettings(settings.copy(theme = option.first)) }) {
                    Text(option.second)
                }
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("播放通知", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "显示前台播放通知与播放控制",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = settings.notificationOn,
                onCheckedChange = { enabled ->
                    settingsViewModel.saveSettings(settings.copy(notificationOn = enabled))
                },
            )
        }

        Spacer(Modifier.height(16.dp))
        Text(
            "版本 " + BuildConfig.VERSION_NAME,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(100.dp))
    }
}
