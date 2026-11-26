package com.am.music.ui.playlists

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.am.music.AppContainer
import com.am.music.data.db.PlaylistWithCount
import com.am.music.data.playlist.PlaylistCovers
import com.am.music.ui.components.AmAlertDialog
import com.am.music.ui.components.bottomBarInset
import com.am.music.ui.components.rememberCoverModel
import com.am.music.ui.components.rememberUriModel
import com.am.music.ui.components.statusBarTopPadding
import com.am.music.ui.playlistcover.CoverCropDialog
import com.am.music.ui.playlistcover.MediaPickerDialog
import com.am.music.ui.theme.Motion
import com.am.music.ui.theme.pressScale
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    // 选封面：自建选择器（图片/视频）→ 裁成 1:1 → 存私有目录 → 写库。
    // 比系统相册选择器多两步，换来的是「视频抽帧」和「统一方版」这两件它做不到的事。
    var coverTarget by remember { mutableStateOf<PlaylistWithCount?>(null) }
    var cropSource by remember { mutableStateOf<Bitmap?>(null) }
    var preparingCover by remember { mutableStateOf(false) }
    val coverScope = rememberCoroutineScope()

    fun loadCropSource(uri: Uri, isVideo: Boolean) {
        val context = container.appContext
        preparingCover = true
        coverScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                if (isVideo) {
                    PlaylistCovers.videoFrame(context, uri)
                } else {
                    PlaylistCovers.decode(context, uri)
                }
            }
            preparingCover = false
            // 选完先不关掉「要改哪个歌单」的记忆，裁剪确认时才写库
            if (bitmap != null) cropSource = bitmap else coverTarget = null
        }
    }

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
                modifier = posterEnter(0),
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
                modifier = posterEnter(1),
                title = "最近播放",
                subtitle = "${uiState.recentCount} 首",
                placeholder = Icons.Default.History,
                onClick = onRecentClick,
            )
        }
        item(key = "create") {
            NewPlaylistTile(modifier = posterEnter(2), onClick = { showCreateDialog = true })
        }

        items(uiState.playlists, key = { it.id }) { playlist ->
            // animateItem：新建 / 删除歌单时整面墙重排，剩下的格子滑到新位置，而不是瞬间错位
            PosterTile(
                modifier = Modifier
                    .animateItem(
                        placementSpec = Motion.place(),
                        fadeInSpec = tween(280),
                        fadeOutSpec = tween(170),
                    )
                    .then(posterEnter(3 + uiState.playlists.indexOf(playlist))),
                title = playlist.name,
                subtitle = "${playlist.songCount} 首",
                coverPath = playlist.coverPath,
                coverUrl = playlist.coverUrl,
                coverUri = playlist.firstUri,
                onClick = { onPlaylistClick(playlist.id) },
                onPickCover = { coverTarget = playlist },
                onClearCover = playlist.coverPath?.let { path ->
                    { viewModel.clearCover(playlist.id, path) }
                },
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

    // 自建选择器：图片 / 视频两栏，选完抽帧（视频）或直读（图片），再进裁剪
    coverTarget?.let { target ->
        MediaPickerDialog(
            onDismiss = { coverTarget = null },
            onPick = { uri, isVideo -> loadCropSource(uri, isVideo) },
        )
    }

    // 裁剪：确认后写库，取消就什么都不改
    cropSource?.let { source ->
        CoverCropDialog(
            bitmap = source,
            onDismiss = {
                cropSource = null
                coverTarget = null
            },
            onConfirm = { cropped ->
                coverTarget?.let { viewModel.setCover(it.id, cropped) }
                cropSource = null
                coverTarget = null
            },
        )
    }

    if (preparingCover) {
        Dialog(onDismissRequest = {}) {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(12.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text("正在读取封面…", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/**
 * 海报墙的入场：每格按序号错开一点，淡入的同时向上浮 14dp，整面墙像被一口气摊开。
 *
 * 错开只算到第 11 格（450ms 封顶）——再往后排的格子本来就落在屏幕外，
 * 等用户滑到那里时早该是实心的，没必要继续排队。
 * 每个格子只播一次：state 跟着 item key 走，之后新建 / 删除歌单的重排交给 animateItem。
 */
@Composable
private fun posterEnter(index: Int): Modifier {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay((index.coerceIn(0, 11) * 55).toLong())
        progress.animateTo(1f, tween(durationMillis = 420, easing = Motion.EmphasizedDecel))
    }
    return Modifier.graphicsLayer {
        // 在 layer 里读 progress.value：每帧只重画这一层，不会让整个网格重组
        alpha = progress.value
        translationY = (1f - progress.value) * 24.dp.toPx()
        val s = 0.94f + 0.06f * progress.value
        scaleX = s
        scaleY = s
    }
}

/**
 * 墙上的一张海报。
 *
 * 封面优先级：**自定义封面 > 第一首歌的封面（在线 coverUrl / 本地 uri）> 图标占位**。
 * 三者都画在同一块方形里，所以墙的网格永远整齐，不会因为某个歌单没封面就塌一格。
 * 名字最多两行——一行放不下就省略，宁可少写字也别把墙拉得参差不齐。
 */
@Composable
private fun PosterTile(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    coverPath: String? = null,
    coverUrl: String? = null,
    coverUri: String? = null,
    placeholder: ImageVector = Icons.AutoMirrored.Filled.QueueMusic,
    placeholderTint: Color = MaterialTheme.colorScheme.secondary,
    placeholderBackground: Color = MaterialTheme.colorScheme.surfaceVariant,
    onPickCover: (() -> Unit)? = null,
    onClearCover: (() -> Unit)? = null,
    onRename: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    // 按下去整张海报缩一点，松手弹回来——比只闪一下水波纹更像「拿起来又放下」
    Column(modifier = modifier.fillMaxWidth().pressScale()) {
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
            // 自定义封面是私有目录里的文件；文件被清理掉就当没设过，继续走兜底
            val custom = coverPath?.takeIf { File(it).isFile }?.let { Uri.fromFile(File(it)) }
            val model = rememberUriModel(custom)
                ?: rememberCoverModel(coverUrl)
                ?: rememberUriModel(coverUri?.let { Uri.parse(it) })
            if (model != null) {
                // 封面解码完再淡上来，别让墙在入场动画末尾「啪」地贴满一张图
                val context = LocalContext.current
                val request = remember(model) {
                    ImageRequest.Builder(context).data(model).crossfade(220).build()
                }
                AsyncImage(
                    model = request,
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
            if (onPickCover != null || onRename != null || onDelete != null) {
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
                        if (onPickCover != null) {
                            DropdownMenuItem(
                                text = { Text("设置封面") },
                                onClick = {
                                    menuExpanded = false
                                    onPickCover()
                                },
                            )
                        }
                        if (onClearCover != null) {
                            DropdownMenuItem(
                                text = { Text("恢复默认封面") },
                                onClick = {
                                    menuExpanded = false
                                    onClearCover()
                                },
                            )
                        }
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
private fun NewPlaylistTile(modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier = modifier.fillMaxWidth().pressScale()) {
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




