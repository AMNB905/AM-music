package com.am.music.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.am.music.data.model.Song
import com.am.music.ui.components.AlbumArt
import kotlinx.coroutines.delay

/**
 * 清屏模式：只留封面（或歌词），其余界面全部隐去，点击任意位置退出。
 * 提示文字 2.6 秒后自动淡出，避免长期遮挡画面。
 */
@Composable
internal fun ClearScreenPlayer(
    song: Song,
    showLyrics: Boolean,
    lyricsState: LyricsUiState,
    positionMs: Long,
    isPlaying: Boolean,
    onSeek: (Long) -> Unit,
    onAlignLine: (Long) -> Unit,
    onExit: () -> Unit,
) {
    var hintVisible by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        delay(2600)
        hintVisible = false
    }

    // 系统栏的隐藏/恢复统一由 AMMusicRoot 按路由处理，这里只管画面本身。

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onExit,
            ),
        contentAlignment = Alignment.Center,
    ) {
        val side = minOf(maxWidth, maxHeight) * 0.9f
        if (showLyrics && lyricsState is LyricsUiState.Ready) {
            LyricsView(
                lines = lyricsState.lines,
                positionMs = positionMs,
                isPlaying = isPlaying,
                onSeek = onSeek,
                onAlignLine = onAlignLine,
                coverUrl = song.coverUrl ?: song.albumArtUri?.toString(),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 32.dp, vertical = 64.dp),
            )
        } else {
            AlbumArt(
                uri = song.albumArtUri,
                size = side,
                shape = RoundedCornerShape(3.dp),
            )
        }

        // 提示文字淡出，而不是到点就凭空消失
        AnimatedVisibility(
            visible = hintVisible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(tween(300)),
            exit = fadeOut(tween(420)),
        ) {
            Text(
                text = "点击任意位置退出",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                modifier = Modifier.padding(bottom = 44.dp),
            )
        }
    }
}
