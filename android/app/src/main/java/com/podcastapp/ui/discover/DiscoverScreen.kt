package com.podcastapp.ui.discover

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.podcastapp.ui.components.InlineIssue

@Composable
fun DiscoverScreen(
    onOpenPodcast: (String) -> Unit,
    onOpenFileMedia: () -> Unit,
    viewModel: DiscoverViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("导入", Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineMedium)
        Button(onClick = onOpenFileMedia, modifier = Modifier.fillMaxWidth()) { Text("文件音源") }
        Text("导入 RSS", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
        Text("粘贴播客的 RSS 地址，即可导入并订阅节目。", style = MaterialTheme.typography.bodyLarge)
        OutlinedTextField(
            value = state.feedUrl,
            onValueChange = viewModel::setFeedUrl,
            enabled = !state.loading,
            label = { Text("RSS 地址") },
            isError = state.inputError != null,
            supportingText = { Text(state.inputError ?: "以 https:// 或 http:// 开头") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (state.feedUrl.isNotBlank()) viewModel.addFeed() }),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        val importError = state.importError
        if (importError != null) {
            InlineIssue(importError, viewModel::addFeed)
        } else {
            Button(onClick = viewModel::addFeed, enabled = !state.loading && state.feedUrl.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                Text(if (state.loading) "导入中…" else "导入并订阅")
            }
        }
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        Text("仅导入你有权使用的音源。不绕过付费、地区限制或 DRM。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        state.addedPodcast?.let { podcast ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("已导入并订阅", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(podcast.title, style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { onOpenPodcast(podcast.id) }) { Text("查看节目") }
                }
            }
        }
    }
}
