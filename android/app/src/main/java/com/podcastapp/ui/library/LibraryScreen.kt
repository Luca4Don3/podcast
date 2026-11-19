package com.podcastapp.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlaylistRemove
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.podcastapp.data.remote.EpisodeDto
import com.podcastapp.ui.components.EpisodeRow
import com.podcastapp.ui.components.InlineIssue
import com.podcastapp.ui.components.PodcastRow
import com.podcastapp.ui.components.SectionTitle
import com.podcastapp.ui.components.formatDate
import com.podcastapp.ui.components.formatDuration

@Composable
fun LibraryScreen(
    onPodcastClick: (String) -> Unit,
    onEpisodeClick: (EpisodeDto, String, String) -> Unit,
    viewModel: LibraryViewModel = viewModel(),
    onImport: (() -> Unit)? = null,
) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(viewModel) { viewModel.refresh() }

    LazyColumn(Modifier.fillMaxSize()) {
        item { SectionTitle("资料库") }
        if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) }
        state.error?.let { message -> item { InlineIssue(message, viewModel::refresh, modifier = Modifier.padding(16.dp)) } }
        state.actionError?.let { message -> item { InlineIssue(message, viewModel::retryAction, viewModel::dismissActionError, Modifier.padding(16.dp)) } }
        if (state.loaded) {
            val empty = state.recentlyPlayed.isEmpty() && state.subscriptions.isEmpty() && state.starred.isEmpty() && state.queued.isEmpty()
            if (empty) item { LibraryStart(onImport) }
            item { SectionTitle("继续收听") }
            if (state.recentlyPlayed.isEmpty()) item { SectionHint("还没有未听完的单集") }
                if (state.recentlyPlayed.isNotEmpty()) {
                    items(state.recentlyPlayed, key = { "recent-" + it.episode.id }) { item ->
                        val progress = item.progress
                        val episode = item.episode
                        EpisodeRow(
                            title = episode.title,
                            subtitle = "已听 " + formatDuration((progress.positionMs / 1000).toInt()) +
                                " / " + formatDuration(episode.durationSeconds),
                            imageUrl = episode.imageUrl,
                            clickLabel = "继续收听",
                            onClick = { onEpisodeClick(episode, "", episode.imageUrl) },
                        )
                    }
                }
                item { SectionTitle("已订阅（" + state.subscriptions.size + "）") }
                if (state.subscriptions.isEmpty()) {
                    item {
                        if (empty) SectionHint("还没有订阅") else LibraryStart(onImport)
                    }
                } else {
                    items(state.subscriptions, key = { "sub-" + it.id }) { podcast ->
                        PodcastRow(
                            title = podcast.title,
                            author = podcast.author,
                            imageUrl = podcast.imageUrl,
                            onClick = { onPodcastClick(podcast.id) },
                        )
                    }
                }
                item { SectionTitle("收藏") }
                if (state.starred.isEmpty()) item { SectionHint("收藏的单集会出现在这里") }
                if (state.starred.isNotEmpty()) {
                    items(state.starred, key = { "star-" + it.id }) { episode ->
                        EpisodeRow(
                            title = episode.title,
                            subtitle = formatDate(episode.publishedAt) + " · " + formatDuration(episode.durationSeconds),
                            imageUrl = episode.imageUrl,
                            onClick = { onEpisodeClick(episode, "", episode.imageUrl) },
                        )
                    }
                }
                item { SectionTitle("待播") }
                if (state.queued.isEmpty()) item { SectionHint("加入待播的单集会出现在这里") }
                if (state.queued.isNotEmpty()) {
                    items(state.queued, key = { "queue-" + it.id }) { episode ->
                        EpisodeRow(
                            title = episode.title,
                            subtitle = formatDate(episode.publishedAt),
                            imageUrl = episode.imageUrl,
                            trailing = {
                                IconButton(enabled = episode.id !in state.pendingQueueIds, onClick = { viewModel.setQueued(episode.id, false) }) {
                                    Icon(Icons.Outlined.PlaylistRemove, contentDescription = "从待播移除：" + episode.title)
                                }
                            },
                            onClick = { onEpisodeClick(episode, "", episode.imageUrl) },
                        )
                    }
                }
        } else if (state.error != null) {
            item { LibraryStart(onImport) }
        }
        item { Column(Modifier.padding(bottom = 24.dp)) {} }
    }
}

@Composable
private fun SectionHint(text: String) {
    Text(text, Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun LibraryStart(onImport: (() -> Unit)?) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("找点想听的", style = MaterialTheme.typography.titleMedium)
            Text("通过公开 RSS 地址导入播客，订阅与单集保存在本机。", style = MaterialTheme.typography.bodyMedium)
            if (onImport != null) Button(onClick = onImport, modifier = Modifier.fillMaxWidth()) { Text("导入 RSS") }
        }
    }
}
