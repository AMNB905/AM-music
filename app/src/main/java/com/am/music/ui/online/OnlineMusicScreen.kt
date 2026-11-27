package com.am.music.ui.online

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.am.music.ui.theme.Motion
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.am.music.AppContainer
import com.am.music.data.model.OnlinePlatform
import com.am.music.data.model.Song
import com.am.music.ui.components.IconAppear
import com.am.music.ui.components.MotionIconButton
import com.am.music.ui.components.CoverWallSkeleton
import com.am.music.ui.components.RotatingLoadingHint
import com.am.music.ui.components.SongActionsDialog
import com.am.music.ui.components.SongItem
import com.am.music.ui.components.SongListSkeleton
import com.am.music.ui.components.bottomBarInset
import com.am.music.ui.components.rememberPlayAction
import com.am.music.ui.components.statusBarTopPadding
import com.am.music.ui.theme.pressFeedback

/**
 * 在线页：顶栏 + 发现（听歌识曲/每日推荐/推荐歌单）+ 搜索结果。
 *
 * 拆分说明（这一层曾经是 1000 多行的单文件）：
 * - 分块部件在 `OnlineDiscoverParts.kt`（入口行、区块标题、封面墙、歌单卡、消息块）；
 * - 歌单抽屉在 `PlaylistDrawer.kt`；
 * - 数据口径在 `OnlineMusicViewModel`（先盘后网、按音源记忆、双音源并行搜索）。
 */
@Composable
fun OnlineMusicScreen(
    container: AppContainer,
    modifier: Modifier = Modifier,
    onRecognize: () -> Unit = {},
    viewModel: OnlineMusicViewModel = viewModel { OnlineMusicViewModel(container) },
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val playbackState by container.playbackManager.state.collectAsStateWithLifecycle()
    val keyboard = LocalSoftwareKeyboardController.current
    var actionSong by remember { mutableStateOf<Song?>(null) }
    val play = rememberPlayAction(container)

    // 顶栏（标题 / 搜索 / 音源切换）整体作为列表第一项：下滑时内容从状态栏下面穿过
    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = statusBarTopPadding(), bottom = 24.dp + bottomBarInset()),
        ) {
            item(key = "topbar") {
                Column {
                    // ---------------------------------------------------------- 顶栏
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 24.dp, end = 16.dp, top = 20.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = if (uiState.openedPlaylist != null) "歌单" else "在线",
                                style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.primary,
                            )
                            if (uiState.openedPlaylist == null) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = uiState.platform.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (uiState.openedPlaylist == null) {
                            ConnectionStatus(reachable = uiState.reachable)
                            Spacer(Modifier.width(14.dp))
                            Text(
                                text = "听歌识曲",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(2.dp))
                                    .pressFeedback(scale = 0.92f, haptic = true)
                                    .clickable(onClick = onRecognize)
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    if (uiState.openedPlaylist == null) {
                        SearchField(
                            query = uiState.query,
                            onQueryChange = viewModel::onQueryChange,
                            onSearch = {
                                keyboard?.hide()
                                viewModel.search()
                            },
                            onClear = { viewModel.onQueryChange("") },
                        )

                        Spacer(Modifier.height(14.dp))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            PlatformTab(
                                label = OnlinePlatform.QQ.label,
                                selected = uiState.platform == OnlinePlatform.QQ,
                                onClick = { viewModel.switchPlatform(OnlinePlatform.QQ) },
                            )
                            Spacer(Modifier.width(26.dp))
                            PlatformTab(
                                label = OnlinePlatform.NETEASE.label,
                                selected = uiState.platform == OnlinePlatform.NETEASE,
                                onClick = { viewModel.switchPlatform(OnlinePlatform.NETEASE) },
                            )
                        }
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 12.dp, end = 24.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            MotionIconButton(onClick = viewModel::closePlaylist) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "返回",
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.secondary,
                                )
                            }
                            Text(
                                text = "返回在线首页",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    HorizontalDivider(
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outline,
                    )

                    // 有内容时后台刷新不遮页面：只在顶栏下留一条 1.5dp 的细线表示「在动」
                    if (uiState.isSearching || uiState.isResolving || uiState.isRefreshing) {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.5.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            trackColor = MaterialTheme.colorScheme.surface,
                        )
                    }
                }
            }

            if (uiState.query.isBlank()) {
                item(key = "recognize-entry") { RecognizeEntry(onClick = onRecognize) }
                // 每日推荐：一行三张封面，**自动轮换 + 可左右滑动**，高度固定；点封面直接起播。
                // 不提供刷新入口：这份数据每进程只拉一次，之后走本地快照。
                if (uiState.dailySongs.isNotEmpty()) {
                    item(key = "daily-label") {
                        Box(Modifier.animateItem(fadeInSpec = tween(220))) {
                            SectionHeader(title = "每日推荐")
                        }
                    }
                    item(key = "daily-pager") {
                        Box(Modifier.animateItem(fadeInSpec = tween(260))) {
                            DailyPager(uiState.dailySongs, play)
                        }
                    }
                } else if (uiState.isSearching) {
                    item(key = "daily-skeleton") {
                        Column(Modifier.animateItem(fadeOutSpec = tween(160))) {
                            SectionHeader(title = "每日推荐")
                            CoverWallSkeleton(cells = 3)
                        }
                    }
                }
                if (uiState.searchHistory.isNotEmpty()) {
                    item(key = "history-label") {
                        SectionHeader(
                            title = "最近搜索",
                            action = "清空",
                            onAction = viewModel::clearSearchHistory,
                        )
                    }
                    item(key = "history-list") {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 24.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(uiState.searchHistory, key = { "history-$it" }) { word ->
                                KeywordChip(word) {
                                    keyboard?.hide()
                                    viewModel.searchKeyword(word)
                                }
                            }
                        }
                    }
                }
                if (uiState.hotKeywords.isNotEmpty()) {
                    item(key = "hot-label") { SectionLabel("热门搜索") }
                    item(key = "hot-list") {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 24.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(uiState.hotKeywords, key = { it }) { word ->
                                KeywordChip(word) {
                                    keyboard?.hide()
                                    viewModel.searchKeyword(word)
                                }
                            }
                        }
                    }
                }
                if (uiState.playlists.isNotEmpty()) {
                    // 唯一保留「刷新」的入口：推荐歌单会变，用户想立刻看到新的就点这里
                    item(key = "playlist-label") {
                        Box(Modifier.animateItem(fadeInSpec = tween(220))) {
                            SectionHeader(
                                title = "推荐歌单",
                                action = "刷新",
                                onAction = viewModel::refreshDiscover,
                            )
                        }
                    }
                    items(
                        uiState.playlists.chunked(2),
                        key = { row -> row.first().key },
                    ) { row ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .animateItem(
                                    fadeInSpec = tween(260),
                                    fadeOutSpec = tween(160),
                                    placementSpec = Motion.place(),
                                )
                                .padding(horizontal = 24.dp, vertical = 7.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            row.forEach { item ->
                                PlaylistCard(
                                    playlist = item,
                                    modifier = Modifier.weight(1f),
                                    onClick = { viewModel.openPlaylist(item) },
                                )
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                } else if (uiState.isSearching) {
                    item(key = "playlist-skeleton") {
                        Column(Modifier.animateItem(fadeOutSpec = tween(160))) {
                            SectionLabel("推荐歌单")
                            CoverWallSkeleton(cells = 2)
                        }
                    }
                }
            } else {
                // 搜索结果：**两个音源各一块**。切音源只是把当前音源排前面，不重新搜。
                if (uiState.searchGroups.isEmpty() && uiState.isSearching) {
                    item(key = "search-skeleton") {
                        Column(Modifier.animateItem(fadeOutSpec = tween(160))) {
                            RotatingLoadingHint(
                                messages = listOf("两个源一起搜…", "正在配对…", "马上就好"),
                                modifier = Modifier.padding(top = 14.dp, bottom = 6.dp),
                            )
                            Spacer(Modifier.height(6.dp))
                            SongListSkeleton(rows = 8)
                        }
                    }
                }
                var cursor = 0
                uiState.searchGroups.forEach { group ->
                    val start = cursor
                    item(key = "group-${group.platform.key}") {
                        Box(Modifier.animateItem(fadeInSpec = tween(220))) {
                            SectionLabel("${group.platform.label} · ${group.songs.size} 首")
                        }
                    }
                    itemsIndexed(group.songs, key = { _, song -> "${group.platform.key}-${song.key}" }) { offset, song ->
                        SongItem(
                            song = song,
                            isCurrent = playbackState.currentSong?.key == song.key,
                            onClick = { viewModel.playAt(start + offset) },
                            onMoreClick = { actionSong = song },
                            modifier = Modifier.animateItem(
                                fadeInSpec = tween(240),
                                fadeOutSpec = tween(160),
                                placementSpec = Motion.place(),
                            ),
                        )
                    }
                    cursor += group.songs.size
                }
            }

            if (uiState.message != null) {
                item(key = "message") {
                    MessageBlock(message = uiState.message.orEmpty(), onRetry = viewModel::retry)
                }
            }
        }

        // 歌单抽屉：常驻声明，开合交给内部动画；关闭后仍留一帧用于滑出
        PlaylistDrawer(
            playlist = uiState.openedPlaylist,
            songs = uiState.songs,
            loading = uiState.isSearching && uiState.songs.isEmpty(),
            currentKey = playbackState.currentSong?.key,
            onPlayAll = viewModel::playAll,
            onPlayAt = { index ->
                keyboard?.hide()
                viewModel.playAt(index)
            },
            onDismiss = viewModel::closePlaylist,
        )
    }

    actionSong?.let { song ->
        SongActionsDialog(
            song = song,
            container = container,
            onDismiss = { actionSong = null },
        )
    }
}

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onClear: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.Search,
            contentDescription = null,
            modifier = Modifier.size(15.dp),
            tint = MaterialTheme.colorScheme.secondary,
        )
        Spacer(Modifier.width(12.dp))
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                color = MaterialTheme.colorScheme.onSurface,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text(
                            text = "搜索歌曲、歌手",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                        )
                    }
                    inner()
                }
            },
        )
        if (query.isNotEmpty()) {
            // 清空按钮弹出来（不是突然多一格），顺带按下会缩
            IconAppear(visible = true) {
                MotionIconButton(onClick = onClear, modifier = Modifier.size(24.dp)) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "清空",
                        modifier = Modifier.size(13.dp),
                        tint = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
        } else {
            // 清空之后「搜索」两个字弹回来，和清空按钮是同一套进出
            IconAppear(visible = true) {
                Text(
                    text = "搜索",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .pressFeedback(scale = 0.96f)
                        .clickable(onClick = onSearch)
                        .padding(start = 8.dp),
                )
            }
        }
    }
}




