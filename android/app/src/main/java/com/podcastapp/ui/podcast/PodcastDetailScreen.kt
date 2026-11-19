package com.podcastapp.ui.podcast

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
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
                    IconButton(onClick = { viewModel.load(podcastId, forceRefresh = true) }) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "刷新节目")
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
                else -> {
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
                                FilledTonalButton(onClick = viewModel::toggleSubscribe) {
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
                    Spacer(Modifier.height(8.dp))
                    if (state.episodes.isEmpty()) {
                        EmptyState(title = "暂无单集", subtitle = "点击右上角刷新试试")
                    } else {
                        LazyColumn(Modifier.fillMaxSize()) {
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
}
