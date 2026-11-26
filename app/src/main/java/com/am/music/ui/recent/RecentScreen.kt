package com.am.music.ui.recent

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.am.music.ui.theme.Motion
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.am.music.AppContainer
import com.am.music.data.model.Song
import com.am.music.data.recent.PlayedSong
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import com.am.music.ui.components.MotionIconButton
import com.am.music.ui.components.EmptyState
import com.am.music.ui.components.InlineLoading
import com.am.music.ui.components.SongActionsDialog
import com.am.music.ui.components.SongItem
import com.am.music.ui.components.bottomBarInset
import com.am.music.ui.components.SongListSkeleton
import com.am.music.ui.components.rememberPlayAction

@Composable
fun RecentScreen(
    container: AppContainer,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RecentViewModel = viewModel { RecentViewModel(container) },
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val playbackState by container.playbackManager.state.collectAsStateWithLifecycle()
    var actionSong by remember { mutableStateOf<Song?>(null) }
    val playAction = rememberPlayAction(container)

    Column(modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 8.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MotionIconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(
                text = "最近播放",
                style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            if (uiState.entries.isNotEmpty()) {
                TextButton(onClick = viewModel::clear) {
                    Text("清空", style = MaterialTheme.typography.labelLarge)
                }
            }
        }

        // 骨架 → 空态 → 列表：三种状态之间淡入淡出，别让整屏内容硬替换
        val phase = when {
            uiState.isLoading -> 0
            uiState.entries.isEmpty() -> 1
            else -> 2
        }
        Crossfade(targetState = phase, animationSpec = tween(240), label = "recentPhase") { stage ->
        when (stage) {
            0 -> Column(Modifier.fillMaxSize()) {
                InlineLoading("正在读取播放记录…")
                SongListSkeleton(rows = 5)
            }
            1 -> EmptyState(
                title = "还没有播放记录",
                subtitle = "听过就出现在这，按时间倒序，不设上限",
            )
            else -> {
                // 记录不设上限之后，光按时间倒序会变成一条读不完的长河；
                // 按「今天 / 昨天 / 最近 7 天 / 最近 30 天 / 更早」分段，翻旧歌才有着力点
                val flatSongs = remember(uiState.entries) { uiState.entries.map { it.song } }
                val sections = remember(uiState.entries) { groupRecentByDay(uiState.entries) }
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomBarInset())) {
                    sections.forEach { section ->
                        item(key = "recent-hdr-${section.label}") {
                            RecentSectionHeader(section)
                        }
                        items(section.entries, key = { it.song.key }) { entry ->
                            val index = flatSongs.indexOfFirst { it.key == entry.song.key }
                            // animateItem：删掉一首时下面的行滑上来补位，而不是「啪」地跳一格
                            SongItem(
                                song = entry.song,
                                isCurrent = playbackState.currentSong?.key == entry.song.key,
                                onClick = { if (index >= 0) playAction(flatSongs, index) },
                                onMoreClick = { actionSong = entry.song },
                                modifier = Modifier.animateItem(placementSpec = Motion.place(), fadeInSpec = tween(280), fadeOutSpec = tween(170)),
                            )
                        }
                    }
                }
            }
        }
        }
    }

    actionSong?.let { song ->
        SongActionsDialog(
            song = song,
            container = container,
            onDismiss = { actionSong = null },
        )
    }
}

/** 最近页的分段：一段一个标题，条目保持原顺序（新→旧）。 */
private data class RecentSection(val label: String, val entries: List<PlayedSong>)

private fun groupRecentByDay(entries: List<PlayedSong>): List<RecentSection> {
    if (entries.isEmpty()) return emptyList()
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    // LinkedHashMap：分段顺序跟着记录顺序走，不会出现「更早」跑到「今天」前面
    val groups = LinkedHashMap<String, MutableList<PlayedSong>>()
    entries.forEach { entry ->
        val day = Instant.ofEpochMilli(entry.playedAt).atZone(zone).toLocalDate()
        val label = when {
            day == today -> "今天"
            day == today.minusDays(1) -> "昨天"
            day.isAfter(today.minusDays(7)) -> "最近 7 天"
            day.isAfter(today.minusDays(30)) -> "最近 30 天"
            else -> "更早"
        }
        groups.getOrPut(label) { mutableListOf() }.add(entry)
    }
    return groups.map { (label, list) -> RecentSection(label, list) }
}

@Composable
private fun RecentSectionHeader(section: RecentSection) {
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider(
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(top = 12.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, top = 10.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = section.label,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${section.entries.size} 首",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}






