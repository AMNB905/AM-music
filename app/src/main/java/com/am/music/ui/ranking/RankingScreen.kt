package com.am.music.ui.ranking

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.am.music.ui.theme.Motion
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.am.music.AppContainer
import com.am.music.data.model.OnlinePlatform
import com.am.music.data.model.RankingBoard
import com.am.music.data.model.Song
import com.am.music.ui.components.AlbumArt
import com.am.music.ui.components.IconAppear
import com.am.music.ui.components.animatedIconColor
import com.am.music.ui.components.EmptyState
import com.am.music.ui.components.RotatingLoadingHint
import com.am.music.ui.components.keyedRows
import com.am.music.util.CrashBreadcrumb
import com.am.music.ui.components.SongListSkeleton
import com.am.music.ui.components.rememberPlayAction
import com.am.music.ui.components.bottomBarInset
import com.am.music.ui.components.statusBarTopPadding
import com.am.music.util.formatDuration
import com.am.music.ui.theme.pressFeedback

/**
 * 排行榜：四个官方榜切换，纵向排名的歌曲列表。
 *
 * 视觉沿用全局极简口径：无卡片、0.5dp 分隔线、页边距 24dp、2dp 圆角；
 * 排名数字用等宽感的 labelSmall 贴左，前三名加深到 onSurface，其余保持弱化色。
 */
@Composable
fun RankingScreen(
    container: AppContainer,
    modifier: Modifier = Modifier,
    viewModel: RankingViewModel = viewModel(key = "ranking") { RankingViewModel(container) },
) {
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val boards by viewModel.boards.collectAsStateWithLifecycle()
    val platform by viewModel.platform.collectAsStateWithLifecycle()
    val songs by viewModel.songs.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val playbackState by container.playbackManager.state.collectAsStateWithLifecycle()
    val play = rememberPlayAction(container)
    // 上行 key 与曲目绑成一条记录：上游重复也唯一，且不会出现「key 数组和列表错位」
    val rankRows = remember(songs) { songs.keyedRows { it.key } }

    // 崩溃面包屑补上这一页的数据现场：下一次崩了能直接看出「几首、是否还在 loading」
    LaunchedEffect(selected.key, songs.size, loading) {
        CrashBreadcrumb.action("榜单 ${selected.name} · ${songs.size} 首 · loading=$loading")
    }

    // 头部（标题 / 平台 / 榜单切换 / 说明）作为列表第一项，下滑时榜单歌曲从状态栏下面穿过
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = statusBarTopPadding(), bottom = 24.dp + bottomBarInset()),
    ) {
        item(key = "header") {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 24.dp, end = 16.dp, top = 20.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "排行榜",
                        style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = viewModel::refresh,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = "刷新",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                HorizontalDivider(
                    modifier = Modifier.padding(top = 18.dp),
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outline,
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 24.dp, end = 24.dp, top = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    PlatformTab(
                        text = "QQ 音乐",
                        selected = platform == OnlinePlatform.QQ,
                        onClick = { viewModel.switchPlatform(OnlinePlatform.QQ) },
                    )
                    PlatformTab(
                        text = "网易云音乐",
                        selected = platform == OnlinePlatform.NETEASE,
                        onClick = { viewModel.switchPlatform(OnlinePlatform.NETEASE) },
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(start = 24.dp, end = 24.dp, top = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    boards.forEach { board ->
                        BoardChip(
                            board = board,
                            selected = board.key == selected.key,
                            onClick = { viewModel.open(board) },
                        )
                    }
                }

                Text(
                    text = selected.note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 10.dp),
                )
            }
        }

        when {
            loading -> item(key = "loading") {
                // 骨架退场 / 榜单进场都走淡入淡出，别在刷新的一瞬间「唰」地整屏换掉
                LoadingBlock(
                    boardName = selected.name,
                    modifier = Modifier.animateItem(
                        fadeInSpec = tween(200),
                        fadeOutSpec = tween(140),
                    ),
                )
            }
            songs.isEmpty() -> item(key = "empty") {
                Column(Modifier.fillMaxWidth().height(240.dp)) {
                    EmptyState(
                        title = "这个榜单没拿到",
                        subtitle = "上游限流或暂时挂了，待会儿再试。",
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = viewModel::retry,
                        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
                    ) {
                        Text("重试", color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }

            else -> itemsIndexed(rankRows, key = { _, row -> row.first }) { index, row ->
                val song = row.second
                RankSongRow(
                    rank = index + 1,
                    song = song,
                    isCurrent = song.key == playbackState.currentSong?.key,
                    onClick = { play(songs, index) },
                    // 换榜（新 key 进场）淡入；同名歌曲换位走 placement 弹簧，行会滑过去而不是瞬移
                    modifier = Modifier.animateItem(
                        fadeInSpec = tween(240),
                        fadeOutSpec = tween(160),
                        placementSpec = Motion.place(),
                    ),
                )
            }
        }
    }
}

@Composable
private fun LoadingBlock(boardName: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        RotatingLoadingHint(
            messages = listOf(
                "正在取「$boardName」…",
                "正在合并榜单数据…",
                "马上就好",
            ),
            modifier = Modifier.padding(top = 18.dp),
        )
        Spacer(Modifier.height(10.dp))
        SongListSkeleton(rows = 6, withArt = true)
    }
}

@Composable
private fun PlatformTab(text: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier.pressFeedback(scale = 0.94f).clickable(onClick = onClick),
        horizontalAlignment = Alignment.Start,
    ) {
        // 选中态：文字颜色渐变，下面的短线从 0 长到 20dp（不是一帧之内出现）
        val textColor = animatedIconColor(
            target = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        val underlineWidth by animateDpAsState(
            targetValue = if (selected) 20.dp else 0.dp,
            animationSpec = spring(dampingRatio = 0.7f, stiffness = 380f),
            label = "tabUnderline",
        )
        val underlineColor = animatedIconColor(
            target = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = textColor,
        )
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .width(underlineWidth)
                .height(1.dp)
                .background(underlineColor),
        )
    }
}

@Composable
private fun BoardChip(board: RankingBoard, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = board.name,
        style = MaterialTheme.typography.bodySmall,
        color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .clip(RoundedCornerShape(2.dp))
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                }
            )
            .pressFeedback(scale = 1f, dim = 0.06f)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
private fun RankSongRow(
    rank: Int,
    song: Song,
    isCurrent: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .pressFeedback(scale = 1f, dim = 0.05f)
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.width(28.dp)) {
            Text(
                text = rank.toString().padStart(2, '0'),
                style = MaterialTheme.typography.labelSmall,
                color = if (rank <= 3) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                textAlign = TextAlign.Start,
            )
        }
        Spacer(Modifier.width(10.dp))
        AlbumArt(uri = song.albumArtUri, size = 44.dp, shape = RoundedCornerShape(2.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = listOf(song.artist, formatDuration(song.durationMs))
                    .filter { it.isNotBlank() && it != "0:00" }
                    .joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconAppear(visible = isCurrent) {
            Text(
                text = "▶",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}




