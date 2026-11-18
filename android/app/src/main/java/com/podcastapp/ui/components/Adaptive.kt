package com.podcastapp.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 宽屏（平板 / 折叠屏展开 / 横屏大屏）下限制内容最大宽度，
 * 手机上完全无副作用，保证两种形态都不会出现过宽文案行。
 */
@Composable
fun ContentWidth(
    modifier: Modifier = Modifier,
    maxWidth: Int = 720,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(Modifier.widthIn(max = maxWidth.dp)) {
            content()
        }
    }
}
