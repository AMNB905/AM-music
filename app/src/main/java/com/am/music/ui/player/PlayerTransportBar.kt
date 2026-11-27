package com.am.music.ui.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.am.music.data.model.Song
import com.am.music.playback.PlayMode
import com.am.music.ui.components.MotionIconButton
import com.am.music.ui.components.PlaybackProgress
import com.am.music.ui.components.rememberSmoothPosition
import com.am.music.ui.theme.Motion
import com.am.music.util.formatDuration

/**
 * 播放页下半部分：进度条 + 时间 + 一排控制键。
 *
 * 单独成文件是因为进度条拖动（scrub）有自己的临时状态，
 * 跟页面级的「封面 / 歌词切换」没有关系。
 */
@Composable
internal fun PlayerTransportBar(
    positionMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    playMode: PlayMode,
    onSeek: (Long) -> Unit,
    onTogglePlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onCyclePlayMode: () -> Unit,
    onOpenQueue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 进度：按帧插值后的位置喂给细线进度条，拖动时才出现圆点；数字跟着手指走。
    val smoothPosition = rememberSmoothPosition(positionMs, isPlaying)
    var scrubMs by remember { mutableStateOf<Long?>(null) }
    // 拖动时左侧时间染成主色：眼睛不用来回找，就知道现在显示的是「将要跳到的位置」
    val timeColor by animateColorAsState(
        targetValue = if (scrubMs != null) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = tween(160),
        label = "scrubTimeColor",
    )

    Column(modifier) {
        PlaybackProgress(
            progressMs = scrubMs ?: smoothPosition,
            durationMs = durationMs,
            onSeek = onSeek,
            isPlaying = isPlaying,
            onScrub = { scrubMs = it },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth()) {
            Text(
                text = formatDuration(scrubMs ?: smoothPosition),
                style = MaterialTheme.typography.labelSmall,
                // 等宽字体：每帧刷新时数字宽度不变，整行不会跟着左右抽动
                fontFamily = FontFamily.Monospace,
                color = timeColor,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = formatDuration(durationMs),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MotionIconButton(onClick = onCyclePlayMode, modifier = Modifier.size(40.dp)) {
                AnimatedContent(
                    targetState = playMode,
                    transitionSpec = {
                        (fadeIn(tween(150, delayMillis = 20)) +
                            scaleIn(Motion.pop(), initialScale = 0.56f)) togetherWith
                            (fadeOut(tween(120)) + scaleOut(tween(120), targetScale = 0.8f))
                    },
                    label = "playMode",
                ) { mode ->
                    Icon(
                        imageVector = when (mode) {
                            PlayMode.LIST_LOOP -> Icons.Default.Repeat
                            PlayMode.SINGLE_LOOP -> Icons.Default.RepeatOne
                            PlayMode.SHUFFLE -> Icons.Default.Shuffle
                        },
                        contentDescription = mode.label,
                        modifier = Modifier.size(17.dp),
                        tint = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
            MotionIconButton(onClick = onPrevious, modifier = Modifier.size(48.dp), haptic = true) {
                Icon(
                    imageVector = Icons.Default.SkipPrevious,
                    contentDescription = "上一首",
                    modifier = Modifier.size(24.dp),
                )
            }
            MotionIconButton(onClick = onTogglePlayPause, modifier = Modifier.size(64.dp), haptic = true) {
                // 播放 / 暂停换图标：新图标从 56% 弹到 100%，落位带一下过冲；
                // 旧图标同时缩小淡出。点一下能明显感到「按到了」。
                AnimatedContent(
                    targetState = isPlaying,
                    transitionSpec = {
                        (fadeIn(tween(140, delayMillis = 20)) +
                            scaleIn(Motion.pop(), initialScale = 0.56f)) togetherWith
                            (fadeOut(tween(110)) + scaleOut(tween(110), targetScale = 0.82f))
                    },
                    label = "playPause",
                ) { playing ->
                    Icon(
                        imageVector = if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (playing) "暂停" else "播放",
                        modifier = Modifier.size(36.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            MotionIconButton(onClick = onNext, modifier = Modifier.size(48.dp), haptic = true) {
                Icon(
                    imageVector = Icons.Default.SkipNext,
                    contentDescription = "下一首",
                    modifier = Modifier.size(24.dp),
                )
            }
            MotionIconButton(onClick = onOpenQueue, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                    contentDescription = "播放队列",
                    modifier = Modifier.size(17.dp),
                    tint = MaterialTheme.colorScheme.secondary,
                )
            }
        }
    }
}

/** 标题 / 艺术家 / 专辑：换歌时整块上推，位移给足才看得出「换了一首」。 */
@Composable
internal fun PlayerSongMeta(
    song: Song,
    modifier: Modifier = Modifier,
) {
    AnimatedContent(
        targetState = song,
        transitionSpec = {
            (fadeIn(tween(Motion.SWAP, delayMillis = 70)) +
                slideInVertically(tween(Motion.ENTER, easing = Motion.EmphasizedDecel)) { it / 6 }) togetherWith
                (fadeOut(tween(180)) + slideOutVertically(tween(Motion.EXIT)) { -it / 10 })
        },
        label = "songMeta",
        modifier = modifier,
    ) { current ->
        Column {
            Text(
                text = current.title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = listOf(current.artist, current.album)
                    .filter { it.isNotBlank() }
                    .joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
