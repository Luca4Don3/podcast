package com.podcastapp.ui.radio

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
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
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.podcastapp.data.OfficialSources
import com.podcastapp.data.remote.StationDto
import com.podcastapp.ui.components.CoverImage
import com.podcastapp.ui.components.FeedbackMessage

@Composable
fun RadioScreen(
    onPlay: (StationDto) -> Unit,
    viewModel: RadioViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()
    val message by viewModel.message.collectAsState()
    var tab by rememberSaveable { mutableStateOf(0) }
    var bulk by rememberSaveable { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<StationDto?>(null) }
    val context = LocalContext.current

    LaunchedEffect(Unit) { viewModel.refresh() }
    FeedbackMessage(message, viewModel::consumeMessage)
    deleting?.let { station ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除音源？") },
            text = { Text("将从我的音源移除「" + station.name + "」，不会删除来源网站的内容。") },
            confirmButton = {
                TextButton(onClick = { viewModel.removeStation(station.id); deleting = null }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
        )
    }
    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("我的音源/导入") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("大陆官方入口") })
        }
        if (tab == 0) {
            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("仅导入自己有权使用的音源；不绕过付费、地区限制或 DRM。地址可达不代表获得授权。",
                            style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { bulk = !bulk }, enabled = !state.saving) {
                            Text(if (bulk) "切换单条保存" else "切换批量粘贴导入")
                        }
                        if (bulk) {
                            OutlinedTextField(
                                value = state.batch,
                                onValueChange = viewModel::setBatch,
                                enabled = !state.saving,
                                label = { Text("每行 URL 或 名称|URL") },
                                supportingText = { Text("最多 100 条；仅 HTTP(S)。无效或重复行会阻止整批提交。") },
                                minLines = 4,
                                maxLines = 8,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Button(onClick = viewModel::importBatch, enabled = !state.saving) {
                                Text(if (state.saving) "正在导入…" else "导入粘贴的音源")
                            }
                        } else {
                            OutlinedTextField(
                                value = state.name,
                                onValueChange = viewModel::setName,
                                enabled = !state.saving,
                                label = { Text("音源名称（可选）") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = state.url,
                                onValueChange = viewModel::setUrl,
                                enabled = !state.saving && !state.probing,
                                label = { Text("自己的 HTTP(S) 音源 URL") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = viewModel::probe, enabled = !state.probing && !state.saving) {
                                    Text(if (state.probing) "检测中…" else "检测连通性")
                                }
                                Button(onClick = viewModel::addStation, enabled = !state.saving && !state.probing) {
                                    Text(if (state.saving) "保存中…" else "保存音源")
                                }
                            }
                            state.probe?.let { probe ->
                                Text(
                                    if (probe.reachable) "地址可达 · " + probe.contentType.ifBlank { "未知类型" } +
                                        "；不代表可播放或获授权。"
                                    else "地址未能连通：" + probe.error.ifBlank { "HTTP " + probe.statusCode },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (probe.reachable) MaterialTheme.colorScheme.onSurfaceVariant
                                        else MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                        if (state.saving || state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("已保存（" + state.stations.size + "）", modifier = Modifier.weight(1f))
                            TextButton(onClick = viewModel::refresh, enabled = !state.loading) { Text("刷新") }
                        }
                        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        if (!state.loading && state.stations.isEmpty()) {
                            Text("还没有音源。可在上方保存或批量导入自己有权使用的音源。")
                        }
                    }
                }
                items(state.stations, key = { it.id }) { station ->
                    StationRow(
                        station = station,
                        onPlay = { onPlay(station) },
                        onFavorite = { viewModel.toggleFavorite(station) },
                        onDelete = { deleting = station },
                    )
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    Text("以下仅提供中国大陆官方网页入口；不内置直播直链，不抓取音频，也不表示本应用获得转播授权。内容与使用条件以官网为准。",
                        modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
                }
                items(OfficialSources.mainland, key = { it.websiteUrl }) { source ->
                    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(source.name, style = MaterialTheme.typography.titleMedium)
                            Text(source.region + " · 来源主体：" + source.publisher, style = MaterialTheme.typography.bodyMedium)
                            Text(source.websiteUrl, style = MaterialTheme.typography.bodySmall)
                            Text("仅在系统浏览器打开官网，不是获得转播授权的预置流。", style = MaterialTheme.typography.bodySmall)
                            Button(onClick = {
                                try {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(source.websiteUrl)).apply {
                                        addCategory(Intent.CATEGORY_BROWSABLE)
                                        // Resolve a browser, not a website's deep-link app; deliver ACTION_VIEW + URL.
                                        selector = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_BROWSER)
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    })
                                } catch (_: ActivityNotFoundException) {
                                    viewModel.showMessage("未找到可用浏览器，请安装或启用浏览器后再打开官网。")
                                } catch (_: SecurityException) {
                                    viewModel.showMessage("系统阻止打开浏览器，请检查设备设置。")
                                }
                            }) { Text("用浏览器打开官网") }
                        }
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun StationRow(
    station: StationDto,
    onPlay: () -> Unit,
    onFavorite: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        CoverImage(url = station.favicon, modifier = Modifier.size(44.dp), corner = 10)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(station.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(if (station.favorite) "已收藏 · 我的音源" else "我的音源", style = MaterialTheme.typography.bodySmall)
        }
        IconButton(onClick = onFavorite) {
            Icon(if (station.favorite) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
                contentDescription = if (station.favorite) "取消收藏" else "收藏")
        }
        // Historical directory imports are user-owned too; never hide their removal action.
        IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, contentDescription = "删除音源") }
        IconButton(onClick = onPlay) { Icon(Icons.Outlined.PlayCircle, contentDescription = "播放音源") }
    }
}
