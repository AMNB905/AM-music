package com.am.music.ui.playlists

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.am.music.AppContainer
import com.am.music.data.db.PlaylistWithCount
import com.am.music.ui.components.AmAlertDialog
import com.am.music.ui.components.bottomBarInset
import com.am.music.ui.components.rememberCoverModel
import com.am.music.ui.components.rememberUriModel
import com.am.music.ui.components.statusBarTopPadding

/**
 * 歌单页 = 一面海报墙。
 *
 * 原来的行式列表（小图标 + 标题 + 首数 + 更多）把「歌单」这个内容压成了文字条目，
 * 满屏都是「歌单名 / 12 首」——看得出有几个歌单，看不出它们是些什么。
 * 现在一格一格铺开：封面占满一张方版，名字和首数在下面，像唱片店里贴的那面墙。
 *
 * 两个特殊入口（我喜欢、最近播放）也做成同样的方版，混在墙的最前面，
 * 保证第一行永远有三格，不会出现「两个入口孤零零挂在一列里」的断层。
 */
@Composable
fun PlaylistsScreen(
    container: AppContainer,
    onFavoritesClick: () -> Unit,
    onRecentClick: () -> Unit,
    onPlaylistClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PlaylistsViewModel = viewModel { PlaylistsViewModel(container) },
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val importState by viewModel.importState.collectAsStateWithLifecycle()
    var showCreateDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<PlaylistWithCount?>(null) }
    var deleteTarget by remember { mutableStateOf<PlaylistWithCount?>(null) }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 108.dp),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 14.dp,
            end = 14.dp,
            top = statusBarTopPadding(),
            bottom = 20.dp + bottomBarInset(),
        ),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 标题也放进网格里（跨满整行）：往下滑时海报从状态栏下面穿过（全局沉浸）
        item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 18.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "歌单",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { viewModel.dismissImport(); showImportDialog = true }) {
                    Text("导入", style = MaterialTheme.typography.labelLarge)
                }
                TextButton(onClick = { showCreateDialog = true }) {
                    Text("新建", style = MaterialTheme.typography.labelLarge)
                }
            }
        }

        item(key = "favorites") {
            PosterTile(
                title = "我喜欢的音乐",
                subtitle = "${uiState.favoriteCount} 首",
                placeholder = Icons.Default.Favorite,
                placeholderTint = MaterialTheme.colorScheme.onPrimary,
                placeholderBackground = MaterialTheme.colorScheme.primary,
                onClick = onFavoritesClick,
            )
        }
        item(key = "recent") {
            PosterTile(
                title = "最近播放",
                subtitle = "${uiState.recentCount} 首",
                placeholder = Icons.Default.History,
                onClick = onRecentClick,
            )
        }
        item(key = "create") {
            NewPlaylistTile(onClick = { showCreateDialog = true })
        }

        items(uiState.playlists, key = { it.id }) { playlist ->
            PosterTile(
                title = playlist.name,
                subtitle = "${playlist.songCount} 首",
                coverUrl = playlist.coverUrl,
                coverUri = playlist.firstUri,
                onClick = { onPlaylistClick(playlist.id) },
                onRename = { renameTarget = playlist },
                onDelete = { deleteTarget = playlist },
            )
        }

        if (uiState.playlists.isEmpty()) {
            item(key = "empty-hint", span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    text = "还没歌单。点上面那格「新建」自己建一个，或「导入」粘歌单链接。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp, bottom = 8.dp),
                )
            }
        }
    }

    if (showImportDialog) {
        ImportPlaylistDialog(
            state = importState,
            onImport = { viewModel.importFromLink(it) },
            onCancel = { viewModel.cancelImport() },
            onDismiss = {
                viewModel.dismissImport()
                showImportDialog = false
            },
        )
    }

    if (showCreateDialog) {
        PlaylistNameDialog(
            title = "新建歌单",
            initialName = "",
            onConfirm = {
                viewModel.create(it)
                showCreateDialog = false
            },
            onDismiss = { showCreateDialog = false },
        )
    }
    renameTarget?.let { target ->
        PlaylistNameDialog(
            title = "重命名歌单",
            initialName = target.name,
            onConfirm = {
                viewModel.rename(target.id, it)
                renameTarget = null
            },
            onDismiss = { renameTarget = null },
        )
    }
    deleteTarget?.let { target ->
        AmAlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = {
                Text("删除歌单")
            },
            text = { Text("删掉「${target.name}」？里面的歌不会删。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.delete(target.id)
                        deleteTarget = null
                    }
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            },
        )
    }
}

/**
 * 墙上的一张海报。
 *
 * 封面方版占满格子；没有封面（空歌单、纯本地无内嵌图）时退回图标，
 * 图标底也是 surfaceVariant，所以「占位」和「真封面」是同一块方形，不会有的高有的矮。
 * 名字最多两行——一行放不下就省略，宁可少写字也别把墙拉得参差不齐。
 */
@Composable
private fun PosterTile(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    coverUrl: String? = null,
    coverUri: String? = null,
    placeholder: ImageVector = Icons.AutoMirrored.Filled.QueueMusic,
    placeholderTint: Color = MaterialTheme.colorScheme.secondary,
    placeholderBackground: Color = MaterialTheme.colorScheme.surfaceVariant,
    onRename: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(4.dp))
                .background(placeholderBackground)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = placeholder,
                contentDescription = null,
                modifier = Modifier.size(26.dp),
                tint = placeholderTint,
            )
            val model = rememberCoverModel(coverUrl)
                ?: rememberUriModel(coverUri?.let { Uri.parse(it) })
            if (model != null) {
                AsyncImage(
                    model = model,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize(),
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onClick),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            if (onRename != null || onDelete != null) {
                Box {
                    IconButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "更多操作",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.secondary,
                        )
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        if (onRename != null) {
                            DropdownMenuItem(
                                text = { Text("重命名") },
                                onClick = {
                                    menuExpanded = false
                                    onRename()
                                },
                            )
                        }
                        if (onDelete != null) {
                            DropdownMenuItem(
                                text = { Text("删除") },
                                onClick = {
                                    menuExpanded = false
                                    onDelete()
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 「新建」也用一格方版：墙上的空位就是入口，比藏在标题栏里的按钮好找。 */
@Composable
private fun NewPlaylistTile(onClick: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(4.dp))
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outline,
                    shape = RoundedCornerShape(4.dp),
                )
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = null,
                modifier = Modifier.size(26.dp),
                tint = MaterialTheme.colorScheme.secondary,
            )
        }
        Text(
            text = "新建歌单",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun PlaylistNameDialog(
    title: String,
    initialName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    AmAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(title)
        },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                placeholder = { Text("歌单名称") },
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim()) },
                enabled = name.isNotBlank(),
            ) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
