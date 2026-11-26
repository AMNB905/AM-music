package com.am.music.ui.local

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import android.content.Context
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.am.music.ui.theme.Motion
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.am.music.AppContainer
import com.am.music.data.download.DownloadsRepository
import com.am.music.data.model.Song
import com.am.music.ui.components.MotionIconButton
import com.am.music.ui.components.IconAppear
import com.am.music.ui.components.RotatingLoadingHint
import com.am.music.ui.components.SongActionsDialog
import com.am.music.ui.components.SongItem
import com.am.music.ui.components.SongListSkeleton
import com.am.music.ui.components.bottomBarInset
import com.am.music.ui.components.statusBarTopPadding
import com.am.music.util.formatBytes
import com.am.music.util.formatTotalDuration

@Composable
fun LocalMusicScreen(
    hasPermission: Boolean,
    onRequestPermission: () -> Unit,
    container: AppContainer,
    modifier: Modifier = Modifier,
    viewModel: LocalMusicViewModel = viewModel { LocalMusicViewModel(container) },
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val playbackState by container.playbackManager.state.collectAsStateWithLifecycle()
    var actionSong by remember { mutableStateOf<Song?>(null) }

    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    // 排序选择要记住：每次进本地页都重选一遍太烦
    val viewPrefs = remember { context.getSharedPreferences("am_local_view", Context.MODE_PRIVATE) }
    var sortIndex by remember { mutableIntStateOf(viewPrefs.getInt("sort", 0)) }
    var showSortMenu by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(hasPermission) {
        if (hasPermission) viewModel.load()
    }

    val hasDownloads = uiState.downloads.isNotEmpty()

    // 「添加顺序」= 保持扫描顺序；其余按用户选的维度过
    val comparator: Comparator<Song>? = when (sortIndex) {
        1 -> compareBy<Song> { it.title.lowercase() }.thenBy { it.artist.lowercase() }
        2 -> compareBy<Song> { it.artist.lowercase() }.thenBy { it.title.lowercase() }
        3 -> compareByDescending<Song> { it.durationMs }
        else -> null
    }
    val downloads = comparator?.let { uiState.downloads.sortedWith(it) } ?: uiState.downloads
    val localSongs = comparator?.let { uiState.songs.sortedWith(it) } ?: uiState.songs

    val q = query.trim()
    val searching = q.isNotEmpty()
    // 搜索时把「已下载」和「本机」并成一条结果流：用户心里只有一个曲库，不该自己在两段里找
    val results = remember(downloads, localSongs, q, sortIndex) {
        if (q.isEmpty()) emptyList() else (downloads + localSongs)
            .distinctBy { it.key }
            .filter { s ->
                s.title.contains(q, ignoreCase = true) ||
                    s.artist.contains(q, ignoreCase = true) ||
                    s.album.contains(q, ignoreCase = true)
            }
    }
    // 「播放全部」和点歌都基于当前看到的那一份：搜索结果里点一首，队列就是搜索结果
    val visible = if (searching) results else downloads + localSongs
    val localTotalMs = remember(localSongs) { localSongs.sumOf { it.durationMs } }

    when {
        // 没有任何可显示的曲目时，才把权限当障碍；已下载的歌不需要任何权限就能放
        !hasPermission && !hasDownloads -> PermissionPlaceholder(onRequestPermission, modifier)
        // 扫库期间先给骨架 + 一句「在扫什么」，不转圈、不假装卡住
        uiState.isLoading && !hasDownloads -> Column(
            modifier = modifier
                .fillMaxSize()
                .padding(top = statusBarTopPadding() + 22.dp),
        ) {
            RotatingLoadingHint(
                messages = listOf("正在扫描本机音乐…", "正在读取时长与封面…", "马上就好"),
            )
            Spacer(Modifier.height(18.dp))
            SongListSkeleton(rows = 7)
        }

        uiState.songs.isEmpty() && !hasDownloads -> EmptyPlaceholder(modifier)

        // 头部作为列表第一项：往下滑标题跟着走，歌曲从状态栏下面穿过（全局沉浸）
        else -> LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = statusBarTopPadding(), bottom = 24.dp + bottomBarInset()),
        ) {
            item(key = "header") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 24.dp, end = 8.dp, top = 18.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "本地",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = when {
                                searching -> "在 ${downloads.size + localSongs.size} 首里找到 ${results.size} 首"
                                hasDownloads -> "${downloads.size} 首已下载 · ${formatBytes(uiState.downloadBytes)}"
                                else -> "${localSongs.size} 首 · ${formatTotalDuration(localTotalMs)}"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (visible.isNotEmpty()) {
                        TextButton(
                            onClick = { container.playbackManager.playSongs(visible, 0) },
                        ) {
                            Text("播放全部", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    Box {
                        MotionIconButton(
                            onClick = { showSortMenu = true },
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                Icons.Default.Sort,
                                contentDescription = "排序",
                                modifier = Modifier.size(16.dp),
                                tint = if (sortIndex != 0) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.secondary
                                },
                            )
                        }
                        DropdownMenu(
                            expanded = showSortMenu,
                            onDismissRequest = { showSortMenu = false },
                        ) {
                            LOCAL_SORT_LABELS.forEachIndexed { index, label ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = label,
                                            color = if (index == sortIndex) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurface
                                            },
                                        )
                                    },
                                    trailingIcon = if (index == sortIndex) {
                                        {
                                            Icon(
                                                Icons.Default.Check,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp),
                                                tint = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                    } else {
                                        null
                                    },
                                    onClick = {
                                        sortIndex = index
                                        viewPrefs.edit().putInt("sort", index).apply()
                                        showSortMenu = false
                                    },
                                )
                            }
                        }
                    }
                    MotionIconButton(onClick = viewModel::load, modifier = Modifier.size(36.dp)) {
                        // 扫描时图标自己转：转一圈是「在动」，停住是「扫完了」，不用另加进度条。
                        // 不在扫描时压根不建这个无限动画，省得空转掉帧。
                        val angle = if (uiState.isLoading) {
                            val spin = rememberInfiniteTransition(label = "scanSpin")
                            val a by spin.animateFloat(
                                initialValue = 0f,
                                targetValue = 720f,
                                animationSpec = infiniteRepeatable(
                                    animation = tween(1400, easing = LinearEasing),
                                    repeatMode = RepeatMode.Restart,
                                ),
                                label = "scanAngle",
                            )
                            a
                        } else {
                            0f
                        }
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "刷新",
                            modifier = Modifier
                                .size(16.dp)
                                .graphicsLayer { rotationZ = angle },
                            tint = if (uiState.isLoading) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.secondary
                            },
                        )
                    }
                }
            }

            if (downloads.isNotEmpty() || localSongs.isNotEmpty()) {
                item(key = "search") {
                    LocalSearchField(
                        query = query,
                        onQueryChange = { query = it },
                        onClear = {
                            query = ""
                            focusManager.clearFocus()
                        },
                    )
                }
            }

            if (searching) {
                if (results.isEmpty()) {
                    item(key = "no-result") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp, vertical = 48.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = "没找到「$q」",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "换个歌名、歌手或专辑名试试；已下载和本机都会搜",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    item(key = "section-results") {
                        SectionTitle(title = "搜索结果", meta = "${results.size} 首")
                    }
                    itemsIndexed(results, key = { _, song -> "hit:${song.key}" }) { index, song ->
                        SongItem(
                            song = song,
                            isCurrent = playbackState.currentSong?.key == song.key,
                            modifier = Modifier.animateItem(
                                placementSpec = Motion.place(),
                                fadeInSpec = tween(280),
                                fadeOutSpec = tween(170),
                            ),
                            onClick = { container.playbackManager.playSongs(results, index) },
                            onMoreClick = { actionSong = song },
                            badge = song.downloadedQuality?.let { DownloadsRepository.qualityLabel(it) },
                        )
                    }
                }
            } else if (hasDownloads || localSongs.isNotEmpty()) {
                if (hasDownloads) {
                    item(key = "section-downloads") {
                        SectionTitle(
                            title = "已下载",
                            meta = "${downloads.size} 首 · ${formatBytes(uiState.downloadBytes)}",
                        )
                    }
                    itemsIndexed(downloads, key = { _, song -> "dl:${song.key}" }) { index, song ->
                        SongItem(
                            song = song,
                            isCurrent = playbackState.currentSong?.key == song.key,
                            modifier = Modifier.animateItem(placementSpec = Motion.place(), fadeInSpec = tween(280), fadeOutSpec = tween(170)),
                            onClick = { container.playbackManager.playSongs(visible, index) },
                            onMoreClick = { actionSong = song },
                            badge = DownloadsRepository.qualityLabel(song.downloadedQuality ?: ""),
                        )
                    }
                }

                if (uiState.isLoading && hasDownloads) {
                    item(key = "section-scanning") {
                        RotatingLoadingHint(messages = listOf("正在扫描本机音乐…"))
                    }
                }

                if (localSongs.isNotEmpty()) {
                    item(key = "section-local") {
                        SectionTitle(title = "本机音乐", meta = "${localSongs.size} 首")
                    }
                    itemsIndexed(localSongs, key = { _, song -> "local:${song.key}" }) { index, song ->
                        SongItem(
                            song = song,
                            isCurrent = playbackState.currentSong?.key == song.key,
                            modifier = Modifier.animateItem(placementSpec = Motion.place(), fadeInSpec = tween(280), fadeOutSpec = tween(170)),
                            onClick = {
                                container.playbackManager.playSongs(visible, downloads.size + index)
                            },
                            onMoreClick = { actionSong = song },
                        )
                    }
                }
            }

            if (!hasPermission) {
                item(key = "permission-hint") {
                    PermissionHintRow(onRequestPermission)
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

@Composable
private fun PermissionPlaceholder(onRequestPermission: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Default.LibraryMusic,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(16.dp))
        Text("要音频权限", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            text = "给权限才能扫手机里的歌",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRequestPermission) {
            Text("授予权限")
        }
    }
}

@Composable
private fun EmptyPlaceholder(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("未找到本地音乐", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            text = "把歌放进手机，再点右上角刷新",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 小节标题：一条 0.5dp 线 + 左标题右计数，和整站的「列表分段」语言一致。 */
@Composable
private fun SectionTitle(title: String, meta: String) {
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider(
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(top = 14.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(
                text = meta,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 本地页的搜索行：样式和在线页那个搜索框同一套，只是在列表里跟着滚。 */
@Composable
private fun LocalSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, top = 10.dp, bottom = 6.dp)
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
            keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text(
                            text = "搜索本机歌曲、歌手、专辑",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                        )
                    }
                    inner()
                }
            },
        )
        if (query.isNotEmpty()) {
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
        }
    }
}

/** 本地页排序项：0 保持扫描顺序，其余按字段。 */
private val LOCAL_SORT_LABELS = listOf("添加顺序", "歌名", "艺人", "时长")

@Composable
private fun PermissionHintRow(onRequestPermission: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider(
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(top = 14.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onRequestPermission)
                .padding(horizontal = 24.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "扫手机里的歌，先给音频权限",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text("授予", style = MaterialTheme.typography.labelSmall)
        }
    }
}





