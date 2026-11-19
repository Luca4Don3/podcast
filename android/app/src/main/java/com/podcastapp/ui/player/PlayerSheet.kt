package com.podcastapp.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.podcastapp.ui.components.CoverImage
import com.podcastapp.util.Dates
import kotlin.math.abs

@Composable
fun MiniPlayer(
    state: PlayerUiState,
    onExpand: () -> Unit,
    onToggle: () -> Unit,
) {
    if (state.title.isBlank()) return
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val narrow = maxWidth < 240.dp
            val showExpandIcon = maxWidth >= 320.dp && LocalDensity.current.fontScale < 1.45f
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(end = if (narrow) 0.dp else 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        Modifier.weight(1f)
                            .clickable(role = Role.Button, onClickLabel = "展开播放器", onClick = onExpand)
                            .heightIn(min = 48.dp)
                            .padding(start = if (narrow) 8.dp else 12.dp, top = 4.dp, bottom = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (!narrow) {
                            CoverImage(url = state.artworkUrl, modifier = Modifier.size(38.dp), corner = 8)
                        }
                        Column(Modifier.weight(1f)) {
                            Text(state.title, style = MaterialTheme.typography.titleSmall,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val subtitle = when {
                                state.error != null -> "播放失败"
                                state.buffering -> "正在缓冲"
                                state.isLive -> "直播音源"
                                else -> state.podcastTitle
                            }
                            if (subtitle.isNotBlank()) {
                                Text(
                                    subtitle,
                                    modifier = if (state.error != null || state.buffering) {
                                        Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                                    } else Modifier,
                                    color = if (state.error != null) MaterialTheme.colorScheme.error
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        if (showExpandIcon) Icon(Icons.Filled.KeyboardArrowUp, contentDescription = null)
                    }
                    IconButton(onClick = onToggle, modifier = Modifier.size(48.dp)) { PlaybackIcon(state) }
                }
                if (!state.isLive && state.durationMs > 0) {
                    // 迷你播放器仅展示进度；不要把它变成不可操作的 TalkBack 滑块。
                    ProgressLine(state)
                }
            }
        }
    }
}

@Composable
fun PlayerSheet(
    state: PlayerUiState,
    onCollapse: () -> Unit,
    onToggle: () -> Unit,
    onSeek: (Long) -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
    onSpeed: (Float) -> Unit,
    onSleepTimer: (Int) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val availableWidth = maxWidth
        val largeText = LocalDensity.current.fontScale >= 1.45f
        val short = maxHeight < 480.dp
        val compact = short || availableWidth <= 360.dp || largeText
        // Use the width of this sheet/pane, not the width of the window around it.
        val splitMinWidth = if (largeText) 720.dp else 600.dp
        val split = constraints.hasBoundedWidth && availableWidth >= splitMinWidth
        val inlineArtwork = compact && availableWidth >= (if (largeText) 400.dp else 300.dp)
        val colors = MaterialTheme.colorScheme
        val scrollState = rememberScrollState()
        // An unbounded host (e.g. a scrolling pane) must provide its own scrolling.
        val scroll = if (constraints.hasBoundedHeight) Modifier.verticalScroll(scrollState) else Modifier
        Column(
            Modifier.fillMaxWidth()
                .background(Brush.verticalGradient(listOf(colors.secondaryContainer, colors.surface)))
                .then(scroll),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                Modifier.widthIn(max = 760.dp).fillMaxWidth()
                    .padding(horizontal = if (availableWidth < 180.dp) 0.dp else 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onCollapse, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "收起播放器")
                }
                Text("正在播放", style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f).semantics { heading() }, textAlign = TextAlign.Center)
                SleepMenu(state.sleepTimerSeconds, onSleepTimer)
            }

            val horizontalPadding = when {
                availableWidth < 180.dp -> 0.dp
                availableWidth <= 360.dp -> 8.dp
                else -> 16.dp
            }
            val body = Modifier.widthIn(max = if (split) 760.dp else 480.dp).fillMaxWidth()
                .padding(horizontal = horizontalPadding, vertical = if (compact) 8.dp else 12.dp)
            if (split) {
                Row(body, horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(2f), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        CoverImage(state.artworkUrl, Modifier.size(if (compact) 96.dp else 160.dp), corner = 18)
                        PlayerTitle(state, centered = true)
                    }
                    Column(Modifier.weight(3f), horizontalAlignment = Alignment.CenterHorizontally) {
                        PlayerControls(state, onToggle, onSeek, onSkipBack, onSkipForward, onSpeed)
                    }
                }
            } else {
                Column(body, horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 14.dp)) {
                    if (inlineArtwork) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            CoverImage(state.artworkUrl, Modifier.size(60.dp), corner = 12)
                            Column(Modifier.weight(1f)) { PlayerTitle(state, centered = false) }
                        }
                    } else {
                        CoverImage(state.artworkUrl, Modifier.size(if (compact) 64.dp else 208.dp),
                            corner = if (compact) 12 else 24)
                        PlayerTitle(state, centered = true)
                    }
                    PlayerControls(state, onToggle, onSeek, onSkipBack, onSkipForward, onSpeed)
                }
            }
        }
    }
}

@Composable
private fun PlayerTitle(state: PlayerUiState, centered: Boolean) {
    val align = if (centered) TextAlign.Center else TextAlign.Start
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(state.title, style = MaterialTheme.typography.titleLarge, textAlign = align,
            modifier = Modifier.fillMaxWidth().semantics { heading() })
        if (state.isLive || state.podcastTitle.isNotBlank()) {
            Text(if (state.isLive) "直播音源" else state.podcastTitle,
                modifier = Modifier.fillMaxWidth(), textAlign = align,
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlayerControls(
    state: PlayerUiState,
    onToggle: () -> Unit,
    onSeek: (Long) -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
    onSpeed: (Float) -> Unit,
) {
    val canSeek = state.canSeek && state.durationMs > 0 && state.error == null
    when {
        state.error != null -> {
            Text("播放失败。请检查网络或音源后重试。", Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        }
        state.buffering -> {
            Row(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(16.dp).clearAndSetSemantics {}, strokeWidth = 2.dp)
                Text("正在缓冲", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
    if (!state.isLive && state.error == null) {
        if (canSeek) {
            key(state.mediaId, state.durationMs) { SeekBar(state, onSeek) }
        } else if (state.durationMs > 0) {
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ProgressLine(state)
                TimeLabels(state.positionMs.coerceIn(0, state.durationMs), state.durationMs, merged = false)
            }
        } else {
            Text("暂无法定位", style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(vertical = 4.dp))
        }
    }
    if (state.error != null) {
        Button(onClick = onToggle, modifier = Modifier.padding(top = 6.dp).heightIn(min = 48.dp)) { Text("重试播放") }
    } else {
        FlowRow(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (!state.isLive) {
                IconButton(onClick = onSkipBack, enabled = canSeek,
                    modifier = Modifier.size(48.dp).align(Alignment.CenterVertically)) {
                    Icon(Icons.Filled.FastRewind, contentDescription = "后退")
                }
            }
            FilledIconButton(onClick = onToggle, enabled = state.title.isNotBlank(),
                modifier = Modifier.size(60.dp).align(Alignment.CenterVertically)) {
                PlaybackIcon(state, Modifier.size(32.dp))
            }
            if (!state.isLive) {
                IconButton(onClick = onSkipForward, enabled = canSeek,
                    modifier = Modifier.size(48.dp).align(Alignment.CenterVertically)) {
                    Icon(Icons.Filled.FastForward, contentDescription = "前进")
                }
            }
        }
    }
    if (!state.isLive) SpeedMenu(state.speed, onSpeed)
    if (state.sleepTimerSeconds > 0) {
        Text("将在 ${Dates.formatDurationMs(state.sleepTimerSeconds * 1000)} 后暂停",
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center)
    }
    if (state.notificationBlocked) {
        Text("系统通知权限未开启，媒体控件是否可见取决于系统。",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp), textAlign = TextAlign.Center)
    }
}

@Composable
private fun PlaybackIcon(state: PlayerUiState, modifier: Modifier = Modifier) {
    when {
        state.error != null -> Icon(Icons.Filled.Refresh, "重试播放", modifier)
        state.willPause -> Icon(Icons.Filled.Pause, "暂停", modifier)
        else -> Icon(Icons.Filled.PlayArrow, "播放", modifier)
    }
}

@Composable
private fun SeekBar(state: PlayerUiState, onSeek: (Long) -> Unit) {
    val duration = state.durationMs
    var preview by remember { mutableStateOf<Float?>(null) }
    val interactions = remember {
        val source = MutableInteractionSource()
        object : MutableInteractionSource by source {
            // Compose 1.7 的取消也会触发 onDragStopped；同步丢弃预览，避免提交被取消的位置。
            override suspend fun emit(interaction: Interaction) {
                if (interaction is DragInteraction.Cancel) preview = null
                source.emit(interaction)
            }

            override fun tryEmit(interaction: Interaction): Boolean {
                if (interaction is DragInteraction.Cancel) preview = null
                return source.tryEmit(interaction)
            }
        }
    }
    val position = preview?.toLong() ?: state.positionMs.coerceIn(0, duration)
    val description = "已播放 ${Dates.formatDurationMs(position)}，总时长 ${Dates.formatDurationMs(duration)}"
    Slider(
        value = preview ?: position.toFloat(),
        onValueChange = { preview = it },
        onValueChangeFinished = {
            val target = preview?.toLong()
            preview = null
            if (target != null) onSeek(target.coerceIn(0, duration))
        },
        valueRange = 0f..duration.toFloat(),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
            contentDescription = "播放进度"
            stateDescription = description
        },
        interactionSource = interactions,
    )
    TimeLabels(position, duration, merged = true)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TimeLabels(position: Long, duration: Long, merged: Boolean) {
    FlowRow(
        Modifier.fillMaxWidth().then(if (merged) Modifier.clearAndSetSemantics {} else Modifier),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val style = MaterialTheme.typography.labelSmall
        Text(Dates.formatDurationMs(position), Modifier.padding(end = 8.dp), style = style)
        Text(Dates.formatDurationMs(duration), style = style)
    }
}

@Composable
private fun ProgressLine(state: PlayerUiState) {
    val fraction = (state.positionMs.toDouble() / state.durationMs).coerceIn(0.0, 1.0).toFloat()
    Box(Modifier.fillMaxWidth().height(2.dp).background(MaterialTheme.colorScheme.surfaceVariant)) {
        Box(Modifier.fillMaxWidth(fraction).height(2.dp).background(MaterialTheme.colorScheme.primary))
    }
}

@Composable
private fun SpeedMenu(speed: Float, onSpeed: (Float) -> Unit) {
    var show by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { show = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("倍速 $speed×") }
        DropdownMenu(expanded = show, onDismissRequest = { show = false }) {
            listOf(0.8f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f).forEach { option ->
                val isSelected = abs(option - speed) < 0.01f
                DropdownMenuItem(
                    text = { Text("$option×" + if (isSelected) " · 当前" else "") },
                    modifier = Modifier.semantics { selected = isSelected },
                    onClick = { onSpeed(option); show = false },
                )
            }
        }
    }
}

@Composable
private fun SleepMenu(remainingSeconds: Long, onSleepTimer: (Int) -> Unit) {
    var show by remember { mutableStateOf(false) }
    val active = remainingSeconds > 0
    Box {
        IconButton(onClick = { show = true }, modifier = Modifier.size(48.dp).semantics {
            stateDescription = if (active) "已开启" else "已关闭"
        }) {
            Icon(Icons.Outlined.Bedtime, contentDescription = "睡眠定时器",
                tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
        }
        DropdownMenu(expanded = show, onDismissRequest = { show = false }) {
            if (active) {
                Text("将在 ${Dates.formatDurationMs(remainingSeconds * 1000)} 后暂停",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.labelMedium)
            }
            listOf(0, 15, 30, 60, 90).forEach { minutes ->
                DropdownMenuItem(text = { Text(if (minutes == 0) "关闭定时器" else "$minutes 分钟") },
                    onClick = { onSleepTimer(minutes); show = false })
            }
        }
    }
}
