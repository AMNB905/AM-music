package com.am.music.ui.online

import android.net.Uri
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.am.music.data.model.Song
import com.am.music.data.online.OnlinePlaylist
import com.am.music.ui.components.rememberCoverModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 在线页的分块部件（原本全挤在 `OnlineMusicScreen.kt` 里，文件 1000 多行）。
 *
 * 这里只放「一块界面」：入口行、区块标题、每日推荐封面墙、歌单卡、消息块。
 * 页面骨架与状态在 `OnlineMusicScreen.kt`，歌单抽屉在 `PlaylistDrawer.kt`。
 */

@Composable
internal fun ConnectionStatus(reachable: Boolean?) {
    val label = when (reachable) {
        true -> "已连接"
        false -> "未连接"
        null -> "检测中"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(5.dp)
                .clip(RoundedCornerShape(0.dp))
                .background(
                    if (reachable == false) {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                ),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun PlatformTab(label: String, selected: Boolean, onClick: () -> Unit) {
    Column(modifier = Modifier.clickable(onClick = onClick)) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Spacer(Modifier.height(5.dp))
        Box(
            modifier = Modifier
                .height(1.dp)
                .width(if (selected) 24.dp else 0.dp)
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}

@Composable
internal fun RecognizeEntry(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.MusicNote,
            contentDescription = null,
            modifier = Modifier.size(15.dp),
            tint = MaterialTheme.colorScheme.secondary,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = "听歌识曲",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "听一段，帮你找歌",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = "→",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 24.dp),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outline,
    )
}

@Composable
internal fun SectionHeader(
    title: String,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 16.dp, top = 22.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (action != null && onAction != null) {
            Text(
                text = action,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(2.dp))
                    .clickable(onClick = onAction)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
internal fun SectionLabel(text: String) {
    SectionHeader(title = text)
}

@Composable
internal fun KeywordChip(word: String, onClick: () -> Unit) {
    Text(
        text = word,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(2.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

/** 每日推荐：一行三张封面，自动轮换翻页，也可左右滑动；高度固定，不随歌曲数量撑高页面。 */
@Composable
internal fun DailyPager(
    songs: List<Song>,
    onPlay: (List<Song>, Int) -> Unit,
) {
    val pages = (songs.size + DAILY_PER_PAGE - 1) / DAILY_PER_PAGE
    val pagerState = rememberPagerState(pageCount = { pages })
    val scope = rememberCoroutineScope()

    // 自动轮换：停 4.2 秒翻一页；手指正在拖（isScrollInProgress）就跳过这一拍，不跟人抢
    LaunchedEffect(pagerState, pages) {
        if (pages <= 1) return@LaunchedEffect
        while (true) {
            delay(DAILY_AUTO_SCROLL_MS)
            if (!pagerState.isScrollInProgress) {
                // 自动轮换的曲线单独指定：默认 spring 在长距离翻页时会「冲一下再回弹」，
                // 看着像卡顿；改成 760ms 的加速-减速曲线，一页滑到位、不回头
                pagerState.animateScrollToPage(
                    page = (pagerState.currentPage + 1) % pages,
                    animationSpec = tween(DAILY_SWIPE_MS, easing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)),
                )
            }
        }
    }

    Column(Modifier.padding(top = 4.dp)) {
        HorizontalPager(
            state = pagerState,
            contentPadding = PaddingValues(horizontal = 24.dp),
            pageSpacing = 10.dp,
            modifier = Modifier.fillMaxWidth(),
        ) { page ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                repeat(DAILY_PER_PAGE) { column ->
                    val index = page * DAILY_PER_PAGE + column
                    if (index < songs.size) {
                        DailySongCell(
                            song = songs[index],
                            modifier = Modifier.weight(1f),
                            onClick = { onPlay(songs, index) },
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }

        // 页码指示：细线长度表示总页数，实线是当前页；点击可直达
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(pages) { index ->
                val active = index == pagerState.currentPage
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(2.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(
                            if (active) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outline
                            }
                        )
                        .clickable {
                            scope.launch {
                                pagerState.animateScrollToPage(
                                    page = index,
                                    animationSpec = tween(
                                        DAILY_SWIPE_MS,
                                        easing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f),
                                    ),
                                )
                            }
                        },
                )
            }
        }
    }
}

/** 每日推荐三列封面里的单个格子：方形封面 + 固定两行歌名，点封面直接起播。 */
@Composable
private fun DailySongCell(
    song: Song,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(modifier = modifier.clickable(onClick = onClick)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f),
                modifier = Modifier.size(18.dp),
            )
            val cover = song.coverUrl ?: song.albumArtUri?.toString()
            if (cover != null) {
                // 走本地封面缓存：每日推荐的封面墙在离线/弱网时也要有图
                AsyncImage(
                    model = rememberCoverModel(cover),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Spacer(Modifier.height(7.dp))
        // 歌名固定占两行高度：一首一行、一首两行也不会让整排高度跳动（避免轮换时上下切割感）
        val titleLineHeight = with(LocalDensity.current) {
            MaterialTheme.typography.bodySmall.lineHeight.toDp()
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(titleLineHeight * 2),
        ) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 两列封面网格里的单张歌单卡：方形封面 + 名称 + 曲目数。 */
@Composable
internal fun PlaylistCard(
    playlist: OnlinePlaylist,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(modifier = modifier.clickable(onClick = onClick)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(22.dp),
            )
            if (playlist.coverUrl != null) {
                AsyncImage(
                    model = rememberCoverModel(playlist.coverUrl),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = playlist.name,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = buildList {
                if (playlist.trackCount > 0) add("${playlist.trackCount} 首")
                if (playlist.playCount > 0) add("${formatCount(playlist.playCount)} 播放")
            }.joinToString(" · ").ifBlank { playlist.creator },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun MessageBlock(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 28.dp),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        TextButton(onClick = onRetry, contentPadding = PaddingValues(0.dp)) {
            Text("重试", style = MaterialTheme.typography.labelLarge)
        }
    }
}

internal fun formatCount(count: Long): String =
    if (count >= 10000L) "%.1f万".format(count / 10000.0) else count.toString()

/** 每日推荐翻页时，每页铺三张封面（一行）。 */
private const val DAILY_PER_PAGE = 3

/** 每日推荐自动轮换的停留时长。 */
private const val DAILY_AUTO_SCROLL_MS = 4200L

/** 每日推荐翻页动画时长（760ms，加速-减速，比默认 spring 稳）。 */
private const val DAILY_SWIPE_MS = 760
