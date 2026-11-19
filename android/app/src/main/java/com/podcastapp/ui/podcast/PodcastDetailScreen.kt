package com.podcastapp.ui.podcast

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.podcastapp.data.feed.OfflinePolicy
import com.podcastapp.data.remote.EpisodeDto
import com.podcastapp.ui.components.CoverImage
import com.podcastapp.ui.components.EmptyState
import com.podcastapp.ui.components.ErrorState
import com.podcastapp.ui.components.EpisodeRow
import com.podcastapp.ui.components.LoadingBox
import com.podcastapp.ui.components.formatDate
import com.podcastapp.ui.components.formatDuration

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PodcastDetailScreen(
    podcastId: String,
    onBack: () -> Unit,
    onPlay: (EpisodeDto, String, String) -> Unit,
    viewModel: PodcastDetailViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(podcastId) { viewModel.load(podcastId) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.podcast?.title ?: "节目", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "返回") }
                },
                actions = {
                    IconButton(enabled = state.subscribed && !state.busy,
                        onClick = { viewModel.load(podcastId, forceRefresh = true) }) {
                        Icon(Icons.Outlined.Refresh, contentDescription = if (state.policy == OfflinePolicy.NONE) "刷新订阅" else "刷新缓存")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                state.loading -> LoadingBox()
                state.error != null && state.podcast == null ->
                    ErrorState(state.error ?: "加载失败", onRetry = { viewModel.load(podcastId) })
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    item {
                        state.podcast?.let { podcast ->
                            Row(Modifier.padding(16.dp)) {
                                CoverImage(url = podcast.imageUrl, modifier = Modifier.size(96.dp), corner = 16)
                                Spacer(Modifier.width(14.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(podcast.title, style = MaterialTheme.typography.titleLarge, maxLines = 2)
                                    Text(
                                        podcast.author,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    FilledTonalButton(enabled = !state.busy, onClick = viewModel::toggleSubscribe) {
                                        Icon(
                                            if (state.subscribed) Icons.Outlined.Check else Icons.Outlined.BookmarkBorder,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp),
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(if (state.subscribed) "取消订阅" else "重新订阅")
                                    }
                                }
                            }
                            if (podcast.description.isNotBlank()) {
                                Text(
                                    podcast.description.take(300),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                )
                            }
                        }
                    }
                    if (state.subscribed) item { OfflineCacheControls(state, viewModel::setPolicy, viewModel::cancelOffline) }
                    if (state.episodes.isEmpty()) {
                        item { EmptyState(title = "暂无单集", subtitle = "点击右上角刷新试试") }
                    } else {
                        items(state.episodes) { episode ->
                            EpisodeRow(
                                title = episode.title,
                                subtitle = formatDate(episode.publishedAt) + " · " + formatDuration(episode.durationSeconds),
                                imageUrl = episode.imageUrl.ifBlank { state.podcast?.imageUrl.orEmpty() },
                                trailing = {
                                    IconButton(onClick = {
                                        onPlay(
                                            episode,
                                            state.podcast?.title.orEmpty(),
                                            state.podcast?.imageUrl.orEmpty(),
                                        )
                                    }) {
                                        Icon(Icons.Outlined.PlayArrow, contentDescription = "播放")
                                    }
                                },
                                onClick = {
                                    onPlay(episode, state.podcast?.title.orEmpty(), state.podcast?.imageUrl.orEmpty())
                                },
                            )
                        }
                        item { Column(Modifier.padding(bottom = 90.dp)) {} }
                    }
                }
            }
        }
    }
}

@Composable
private fun OfflineCacheControls(
    state: PodcastDetailUiState,
    onPolicy: (OfflinePolicy) -> Unit,
    onCancel: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box {
                TextButton(enabled = !state.busy, onClick = { showMenu = true }) {
                    Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("离线缓存：${state.policy.label}")
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    OfflinePolicy.entries.forEach { policy ->
                        DropdownMenuItem(text = { Text(policy.label) }, onClick = { showMenu = false; onPolicy(policy) })
                    }
                }
            }
            if (state.cancellable) TextButton(onClick = onCancel) { Text("取消") }
        }
        if (state.busy && state.cancellable) {
            val progress = state.progress
            if (progress != null && progress.total > 0) {
                val episodeFraction = progress.expectedBytes?.takeIf { it > 0 }
                    ?.let { progress.bytes.toFloat() / it } ?: 0f
                val fraction = ((progress.completed + episodeFraction) / progress.total).coerceIn(0f, 1f)
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                val size = if (progress.bytes > 0) " · 当前 ${progress.bytes / 1024} KiB" else ""
                Text("已处理 ${progress.completed}/${progress.total} 集$size", style = MaterialTheme.typography.bodySmall)
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
        state.offlineStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
