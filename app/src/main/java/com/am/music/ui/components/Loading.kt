package com.am.music.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * 等待期的统一视觉。
 *
 * 两件事：**占位骨架**让用户先看到「东西正在成形」，**一句人话 + 一条会呼吸的细线**
 * 告诉他系统在忙什么。都用灰度、无卡片、无插画，跟全局极简口径一致；
 * 不用 Material 的转圈 spinner——那是「不确定还要多久」的符号，骨架才像「马上就好」。
 */

/** 骨架块呼吸用的透明度：慢、幅度小，避免闪烁刺眼。 */
@Composable
private fun shimmerAlpha(): Float {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val alpha by transition.animateFloat(
        initialValue = 0.09f,
        targetValue = 0.20f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "shimmerAlpha",
    )
    return alpha
}

/** 一块骨架（封面 / 文字行 / 大区块都可用）。 */
@Composable
fun SkeletonBlock(
    modifier: Modifier = Modifier,
    corner: Dp = 2.dp,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(corner))
            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = shimmerAlpha())),
    )
}

/** 歌曲行骨架：封面方块 + 两行线，尺寸对齐真实 [SongItem]。 */
@Composable
fun SongRowSkeleton(
    withArt: Boolean = true,
    artSize: Dp = 44.dp,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (withArt) {
            SkeletonBlock(modifier = Modifier.size(artSize))
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            SkeletonBlock(
                modifier = Modifier
                    .fillMaxWidth(0.52f)
                    .height(13.dp),
            )
            Spacer(Modifier.height(7.dp))
            SkeletonBlock(
                modifier = Modifier
                    .fillMaxWidth(0.30f)
                    .height(10.dp),
            )
        }
    }
}

/** 一屏列表骨架。 */
@Composable
fun SongListSkeleton(
    rows: Int = 6,
    withArt: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        repeat(rows) { SongRowSkeleton(withArt = withArt) }
    }
}

/** 封面墙骨架：一行三张方图 + 两行歌名占位，用于每日推荐 / 歌单推荐的首屏。 */
@Composable
fun CoverWallSkeleton(
    cells: Int = 3,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        repeat(cells) {
            Column(Modifier.weight(1f)) {
                SkeletonBlock(modifier = Modifier.fillMaxWidth().aspectRatio(1f))
                Spacer(Modifier.height(7.dp))
                SkeletonBlock(Modifier.fillMaxWidth(0.8f).height(10.dp))
            }
        }
    }
}

/**
 * 「正在做什么」的一句话 + 一条细进度线。
 *
 * [text] 一次给一句；要轮播文案用 [RotatingLoadingHint]。
 */
@Composable
fun LoadingHint(
    text: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            trackColor = MaterialTheme.colorScheme.outline,
        )
    }
}

/**
 * 轮播式等待提示：每 [intervalMs] 换一句，淡入淡出。
 *
 * 用户愿意等的前提是「知道在等什么」，所以按阶段换文案（连接音源 → 取榜单 → 整理封面），
 * 而不是一句「加载中」干等。
 */
@Composable
fun RotatingLoadingHint(
    messages: List<String>,
    modifier: Modifier = Modifier,
    intervalMs: Long = 2200L,
) {
    if (messages.isEmpty()) return
    var index by remember(messages) { mutableIntStateOf(0) }
    LaunchedEffect(messages) {
        while (true) {
            delay(intervalMs)
            index = (index + 1) % messages.size
        }
    }
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
        AnimatedContent(
            targetState = index,
            transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
            label = "loadingHint",
        ) { slot ->
            Text(
                text = messages[slot % messages.size],
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            trackColor = MaterialTheme.colorScheme.outline,
        )
    }
}

/** 小尺寸的行内等待（放在区块标题旁边，不占整行）。 */
@Composable
fun InlineLoading(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.padding(horizontal = 24.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(5.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = shimmerAlpha())),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 播放链路的一句话回执：缓存不完整、直链失效、正在重连、缓冲超时……
 *
 * 和 PreparingBar 是同一种「细条」语言（0.5dp 线 + 一行灰字），区别是它不带动画条：
 * 它讲的是已经发生的事，不是正在等的进度。
 */
@Composable
fun NoticeBar(text: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Transparent),
    ) {
        HorizontalDivider(
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outline,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 9.dp),
        )
    }
}

/**
 * 点歌后到出声之间的那 1~2 秒。
 *
 * 这一点最容易让人以为「点了没反应」，所以显式给一行字 + 一条细线：
 * 说清在准备哪一首，进度线在走，用户就知道系统没死。
 */
@Composable
fun PreparingBar(title: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Transparent),
    ) {
        HorizontalDivider(
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outline,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, top = 9.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "正在准备「$title」",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "马上播放",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
        LinearProgressIndicator(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            trackColor = MaterialTheme.colorScheme.outline,
        )
    }
}
