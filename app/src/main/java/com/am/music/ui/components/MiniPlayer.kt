package com.am.music.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.am.music.playback.PlaybackState
import com.am.music.ui.theme.Motion
import com.am.music.ui.components.MotionIconButton

@Composable
fun MiniPlayer(
    state: PlaybackState,
    onTogglePlayPause: () -> Unit,
    onNext: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    // 浮岛形态下这颗小播放器自己就是一块独立的胶囊，不需要通栏分隔线；
    // 进度轨道也交给调用方（浮岛理态用透明，普通态用 surface），否则会在胶囊里切出一道横线。
    showDivider: Boolean = true,
    progressTrackColor: Color? = null,
) {
    val song = state.currentSong ?: return
    Surface(
        // 半透明浮层由根布局统一铺底，这里保持透明，内容才能从下面滚过去
        color = Color.Transparent,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column {
            if (showDivider) {
                HorizontalDivider(
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            val progress = if (state.durationMs > 0) {
                state.positionMs.toFloat() / state.durationMs
            } else {
                0f
            }
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.5.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = progressTrackColor ?: MaterialTheme.colorScheme.surface,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onClick)
                    .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AlbumArt(
                    uri = song.albumArtUri,
                    size = 38.dp,
                    shape = RoundedCornerShape(2.dp),
                )
                Spacer(Modifier.width(12.dp))
                // 换歌时标题淡出淡入 + 从下方轻推上来，眼睛能跟上「换了一首」这件事
                AnimatedContent(
                    targetState = song,
                    transitionSpec = {
                        // 位移加厚一档（1/10 → 1/6），换歌在迷你条上也能看出来
                        (fadeIn(tween(Motion.SWAP, delayMillis = 50)) +
                            slideInVertically(tween(Motion.ENTER, easing = Motion.EmphasizedDecel)) { it / 6 }) togetherWith
                            fadeOut(tween(170))
                    },
                    modifier = Modifier.weight(1f),
                    label = "miniMeta",
                ) { current ->
                    Column {
                        Text(
                            text = current.title,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = current.artist,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                MotionIconButton(onClick = onTogglePlayPause, modifier = Modifier.size(40.dp)) {
                    AnimatedContent(
                        targetState = state.isPlaying,
                        transitionSpec = {
                            // 和播放页同一套手感：小图标弹出来，旧的缩回去
                            (fadeIn(tween(140, delayMillis = 20)) +
                                scaleIn(Motion.pop(), initialScale = 0.56f)) togetherWith
                                fadeOut(tween(110))
                        },
                        label = "miniPlayPause",
                    ) { playing ->
                        Icon(
                            imageVector = if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (playing) "暂停" else "播放",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                MotionIconButton(onClick = onNext, modifier = Modifier.size(40.dp)) {
                    Icon(
                        imageVector = Icons.Default.SkipNext,
                        contentDescription = "下一首",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
        }
    }
}



