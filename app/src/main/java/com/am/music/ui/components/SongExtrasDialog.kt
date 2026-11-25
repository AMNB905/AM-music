package com.am.music.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.am.music.AppContainer
import com.am.music.data.model.OnlinePlatform
import com.am.music.data.model.Song
import com.am.music.data.model.SongFacts

/**
 * 新接口（`apps/netease` / `apps/qqmusic`）的两个入口，都从「更多」菜单里进。
 *
 * 设计口径：不新增常驻 UI。菜单里多两行，点开是**满屏纸面**的一页内容——
 * 顶部一行标题 + 一个「关闭」，下面就是列表或资料本身，没有卡片、没有浮层、没有阴影。
 */
@Composable
fun SimilarSongsDialog(
    song: Song,
    container: AppContainer,
    onDismiss: () -> Unit,
) {
    var songs by remember(song.key) { mutableStateOf<List<Song>?>(null) }
    LaunchedEffect(song.key) {
        songs = runCatching { container.onlineMusicRepository.similarSongs(song) }
            .getOrDefault(emptyList())
    }
    val playbackState by container.playbackManager.state.collectAsStateWithLifecycle()
    val play = rememberPlayAction(container)
    val list = songs

    ExtrasShell(title = "相似歌曲", subtitle = song.title, onDismiss = onDismiss) {
        when {
            list == null -> {
                RotatingLoadingHint(
                    messages = listOf("正在找相似的歌…", "正在排序…"),
                    modifier = Modifier.padding(top = 10.dp, bottom = 8.dp),
                )
                SongListSkeleton(rows = 7)
            }

            list.isEmpty() -> EmptyState(
                title = "没有找到相似的歌",
                subtitle = "换一首，或待会儿再试",
                modifier = Modifier.padding(top = 8.dp),
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                itemsIndexed(list, key = { _, item -> item.key }) { index, item ->
                    SongItem(
                        song = item,
                        isCurrent = item.key == playbackState.currentSong?.key,
                        onClick = {
                            play(list, index)
                            onDismiss()
                        },
                    )
                }
            }
        }
    }
}

@Composable
fun SongFactsDialog(
    song: Song,
    container: AppContainer,
    onDismiss: () -> Unit,
) {
    var facts by remember(song.key) { mutableStateOf<SongFacts?>(null) }
    var loaded by remember(song.key) { mutableStateOf(false) }
    LaunchedEffect(song.key) {
        facts = runCatching { container.onlineMusicRepository.songFacts(song) }.getOrNull()
        loaded = true
    }

    ExtrasShell(title = "歌曲资料", subtitle = song.title, onDismiss = onDismiss) {
        when {
            !loaded -> {
                RotatingLoadingHint(
                    messages = listOf("正在取资料…", "整理发行信息…"),
                    modifier = Modifier.padding(top = 10.dp, bottom = 8.dp),
                )
                SongListSkeleton(rows = 4)
            }

            facts == null || (facts!!.rows.isEmpty() && facts!!.desc.isNullOrBlank()) ->
                EmptyState(
                    title = "这首歌还没有资料",
                    subtitle = "冷门歌常没资料",
                    modifier = Modifier.padding(top = 8.dp),
                )

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                item(key = "rows") {
                    Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 6.dp)) {
                        facts!!.rows.forEach { (label, value) ->
                            FactRow(label, value)
                        }
                    }
                }
                facts!!.desc?.takeIf { it.isNotBlank() }?.let { desc ->
                    item(key = "desc") {
                        Column(Modifier.padding(horizontal = 24.dp)) {
                            Spacer(Modifier.height(18.dp))
                            Text(
                                text = desc,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
                item(key = "source") {
                    Text(
                        text = "资料来自 ${if (song.platform == OnlinePlatform.QQ) "QQ 音乐" else "网易云音乐"} 百科",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Start,
                        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 22.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun FactRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(76.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 两个面板共用的外壳：满屏纸面 + 一行标题 + 一个极小的「关闭」。 */
@Composable
private fun ExtrasShell(
    title: String,
    subtitle: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        DialogImmersive()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .padding(top = statusBarTopPadding()),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 16.dp, top = 18.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = "关闭",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clip(RoundedCornerShape(2.dp))
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 4.dp),
            ) {
                Column(Modifier.fillMaxSize(), content = content)
            }
        }
    }
}
