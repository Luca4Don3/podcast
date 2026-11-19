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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.podcastapp.data.feed.platformLabel
import com.podcastapp.ui.components.InlineIssue

@Composable
@Suppress("UNUSED_PARAMETER")
fun DiscoverScreen(
    onOpenPodcast: (String) -> Unit,
    onOpenFileMedia: () -> Unit,
    viewModel: PlatformImportViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()
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
            keyboardActions = KeyboardActions(onDone = { if (state.input.isNotBlank()) viewModel.`import`() }),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        val error = state.error
        if (error != null) {
            InlineIssue(
                message = error,
                onRetry = { viewModel.`import`() },
                onDismiss = { viewModel.setInput(state.input) },
            )
        } else {
            Button(
                onClick = { viewModel.`import`() },
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

private fun durationLabel(seconds: Int): String {
    if (seconds <= 0) return ""
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val rest = seconds % 60
    return if (hours > 0) "$hours:" + "%02d:%02d".format(minutes, rest)
    else "$minutes:" + "%02d".format(rest)
}
