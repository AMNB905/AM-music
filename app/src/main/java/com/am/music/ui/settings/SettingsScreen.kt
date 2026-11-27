package com.am.music.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.am.music.ui.theme.pressScale
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.am.music.AppContainer
import com.am.music.BuildConfig
import com.am.music.data.cache.CacheSnapshot
import com.am.music.data.settings.AudioQuality
import com.am.music.data.settings.ThemeMode
import com.am.music.data.update.UpdateInfo
import com.am.music.playback.PlaybackNotice
import com.am.music.ui.components.AmAlertDialog
import com.am.music.ui.components.UpdateDialog
import com.am.music.ui.components.bottomBarInset
import com.am.music.ui.components.statusBarTopPadding
import com.am.music.ui.theme.ThemeAccentPresets
import com.am.music.util.formatBytes
import kotlinx.coroutines.launch

/**
 * 设置页。
 *
 * 归类收纳的口径：**四组**——播放（音质）/ 外观（主题）/ 存储与缓存 / 关于，
 * 组标题是可点的收起展开条，标题右侧永远显示「这一组的现状」（音质档位、主题、总占用、版本），
 * 收起后也不会失去信息。原来的一长串行平铺在页面上，滑两屏还找不到「检查更新」。
 *
 * 视觉沿用全局约定：0.5dp 分隔线、24dp 页边距、2dp 圆角，没有卡片、没有阴影。
 */
@Composable
fun SettingsScreen(
    container: AppContainer,
    modifier: Modifier = Modifier,
) {
    val quality by container.settingsRepository.quality.collectAsStateWithLifecycle()
    val themeMode by container.settingsRepository.themeMode.collectAsStateWithLifecycle()
    val accentArgb by container.settingsRepository.accentColor.collectAsStateWithLifecycle()
    val mixWithOthers by container.settingsRepository.mixWithOthers.collectAsStateWithLifecycle()
    val hideSystemIsland by container.settingsRepository.hideSystemMediaIsland.collectAsStateWithLifecycle()
    val sleepRemaining by container.sleepTimer.remainingSeconds.collectAsStateWithLifecycle()
    var showSleepDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val versionName = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()
    }
    var cacheCleared by remember { mutableStateOf(false) }
    var calibCleared by remember { mutableStateOf(false) }
    var cacheSnapshot by remember { mutableStateOf(CacheSnapshot(0L, 0L, 0L)) }
    // 占用还在统计 / 正在清理哪一项：给行内一句状态，别让按钮点了没反应
    var snapshotReady by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf<String?>(null) }
    // 已下载（用户主动下载，不随缓存清理消失）的真实占用
    var downloadBytes by remember { mutableStateOf<Long?>(null) }
    // 手动「检查更新」查到新版本后，用同一套应用内更新弹窗（不跳浏览器）
    var manualUpdate by remember { mutableStateOf<UpdateInfo?>(null) }
    val scope = rememberCoroutineScope()

    // 进设置页时取一次真实占用；每次清理完再取一次，行内数字跟着变。
    LaunchedEffect(Unit) {
        cacheSnapshot = container.cacheRepository.snapshot()
        snapshotReady = true
        downloadBytes = container.downloadsRepository.totalBytes()
    }

    val failure = container.downloadsRepository.lastFailure()
    val storageSummary = when {
        !snapshotReady -> "统计中…"
        else -> formatBytes(cacheSnapshot.totalBytes + (downloadBytes ?: 0L))
    }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(statusBarTopPadding()))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 16.dp, top = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "设置", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
        }

        HorizontalDivider(
            modifier = Modifier.padding(top = 20.dp),
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outline,
        )

        SettingGroup(title = "播放", summary = quality.label) {
            Row(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AudioQuality.entries.forEach { level ->
                    Chip(
                        text = level.label,
                        selected = level == quality,
                        onClick = {
                            container.settingsRepository.setQuality(level)
                            // 切档后旧直链立即失效：清缓存，下一次点播按新档解析
                            container.onlineMusicRepository.clearStreamCache()
                            cacheCleared = false
                        },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = quality.note + "。之后播放才生效。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp),
            )

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
            )

            Text(
                text = "与其他应用同时播放",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = if (mixWithOthers) {
                    "已允许：导航播报、别的播放器出声都不会把这首歌停掉，两路声音同时在。"
                } else {
                    "默认独占：别的应用一出声（导航、通话、另一个播放器）本 App 自动暂停。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Row(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Chip(
                    text = "独占",
                    selected = !mixWithOthers,
                    onClick = { container.settingsRepository.setMixWithOthers(false) },
                )
                Chip(
                    text = "同时播放",
                    selected = mixWithOthers,
                    onClick = { container.settingsRepository.setMixWithOthers(true) },
                )
            }

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
            )

            Text(
                text = "系统媒体岛",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = if (hideSystemIsland) {
                    "已关闭：播放通知不再声明为媒体样式，系统/ROM 不会顶出媒体岛（灵动岛/超级岛）。" +
                        "通知栏仍有一条普通播放通知，上一首/播放/下一首、蓝牙按键、锁屏卡片都照旧。"
                } else {
                    "跟随系统：播放时由系统在状态栏顶出媒体岛，各家叫法不同（灵动岛/超级岛/媒体卡片）。" +
                        "想自己实现岛的话，选「关闭」。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Row(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Chip(
                    text = "跟随系统",
                    selected = !hideSystemIsland,
                    onClick = { container.settingsRepository.setHideSystemMediaIsland(false) },
                )
                Chip(
                    text = "关闭",
                    selected = hideSystemIsland,
                    onClick = { container.settingsRepository.setHideSystemMediaIsland(true) },
                )
            }

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp),
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
            )

            ActionRow(
                title = "定时关闭",
                subtitle = if (sleepRemaining > 0L) {
                    "还剩 ${formatRemaining(sleepRemaining)}，到点渐隐暂停，队列不丢"
                } else {
                    "未开启 · 睡前听歌用，到点自动暂停"
                },
                action = if (sleepRemaining > 0L) "取消" else "设置",
                onAction = {
                    if (sleepRemaining > 0L) {
                        container.sleepTimer.cancel()
                        PlaybackNotice.brief("已取消定时关闭")
                    } else {
                        showSleepDialog = true
                    }
                },
            )
        }

        IslandSettingsGroup()

        FloatingLyricsSettingsGroup()

        SettingGroup(title = "外观", summary = themeMode.label) {
            Row(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ThemeMode.entries.forEach { mode ->
                    Chip(
                        text = mode.label,
                        selected = mode == themeMode,
                        onClick = { container.settingsRepository.setThemeMode(mode) },
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            Text(
                text = "主题色",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Text(
                text = "按钮图标换色，背景文字还是黑白。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp),
            )
            Row(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ThemeAccentPresets.forEach { preset ->
                    val selected = accentArgb == preset
                    // 0 = 默认黑白，就用当前前景色画一颗「黑白点」
                    val fill = if (preset == 0) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        Color(preset)
                    }
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(fill)
                            .border(
                                width = if (selected) 2.dp else 1.dp,
                                color = if (selected) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.outlineVariant
                                },
                                shape = CircleShape,
                            )
                            .clickable {
                                container.settingsRepository.setAccentColor(preset)
                            },
                    )
                }
            }
        }

        // 存储默认收起：四行清理按钮 + 一键清理不需要一直占着屏幕，标题上已经写了总占用
        SettingGroup(title = "存储与缓存", summary = storageSummary, initiallyExpanded = false) {
            ActionRow(
                title = "音频缓存",
                subtitle = when {
                    clearing == "音频缓存" -> "正在清理…"
                    !snapshotReady -> "正在统计占用…"
                    else -> "${formatBytes(cacheSnapshot.audioBytes)} · 听过的秒开、离线也能放"
                },
                action = "清理",
                onAction = {
                    scope.launch {
                        clearing = "音频缓存"
                        container.cacheRepository.clearAudio()
                        cacheSnapshot = container.cacheRepository.snapshot()
                        clearing = null
                    }
                },
            )
            ActionRow(
                title = "封面缓存",
                subtitle = when {
                    clearing == "封面缓存" -> "正在清理…"
                    !snapshotReady -> "正在统计占用…"
                    else -> "${formatBytes(cacheSnapshot.imageBytes)} · 封面存本地，不费流量"
                },
                action = "清理",
                onAction = {
                    scope.launch {
                        clearing = "封面缓存"
                        container.cacheRepository.clearImages()
                        cacheSnapshot = container.cacheRepository.snapshot()
                        clearing = null
                    }
                },
            )
            ActionRow(
                title = "歌词缓存",
                subtitle = when {
                    clearing == "歌词缓存" -> "正在清理…"
                    !snapshotReady -> "正在统计占用…"
                    else -> "${formatBytes(cacheSnapshot.lyricsBytes)} · 含时间轴，清了要重拉"
                },
                action = "清理",
                onAction = {
                    scope.launch {
                        clearing = "歌词缓存"
                        container.cacheRepository.clearLyrics()
                        cacheSnapshot = container.cacheRepository.snapshot()
                        clearing = null
                    }
                },
            )
            ActionRow(
                title = "已下载",
                subtitle = when {
                    clearing == "已下载" -> "正在清理…"
                    downloadBytes == null -> "正在统计占用…"
                    downloadBytes == 0L -> "还没下载，歌曲菜单里点「下载」"
                    else -> "${formatBytes(downloadBytes ?: 0L)} · 断网能放，清了要重下"
                },
                action = "清理",
                onAction = {
                    scope.launch {
                        clearing = "已下载"
                        container.downloadsRepository.clearAll()
                        downloadBytes = container.downloadsRepository.totalBytes()
                        clearing = null
                    }
                },
            )
            ActionRow(
                title = "一键清理",
                subtitle = when {
                    clearing == "一键清理" -> "正在清理…"
                    !snapshotReady -> "正在统计占用…"
                    else -> "${formatBytes(cacheSnapshot.totalBytes)} · 音频封面歌词一起清"
                },
                action = "全清",
                onAction = {
                    scope.launch {
                        clearing = "一键清理"
                        container.cacheRepository.clearAll()
                        cacheSnapshot = container.cacheRepository.snapshot()
                        clearing = null
                    }
                },
            )
            ActionRow(
                title = "播放直链缓存",
                subtitle = if (cacheCleared) "已清空" else "只存内存，退出应用即释放",
                action = "清空",
                onAction = {
                    container.onlineMusicRepository.clearStreamCache()
                    cacheCleared = true
                },
            )
        }

        // 下载失败过就把它摆出来：用户不用回忆，也不用猜是不是「卡死」
        if (failure != null) {
            SettingGroup(
                title = "下载诊断",
                summary = "有 1 条失败记录",
            ) {
                InfoRow("最近一次失败", failure.first)
                InfoRow("发生在", if (failure.second > 0L) formatClock(failure.second) else "未知时间")
                ActionRow(
                    title = "清掉这条记录",
                    subtitle = "只藏提示，不影响已下载文件",
                    action = "清除",
                    onAction = { container.downloadsRepository.clearLastFailure() },
                )
            }
        }

        SettingGroup(title = "关于", summary = versionName, initiallyExpanded = false) {
            AuthorRow()
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            ActionRow(
                title = "检查更新",
                subtitle = "有新版会提示，旧版可能被强制升级",
                action = "检查",
                onAction = {
                    scope.launch {
                        // 手动点检查更新，绕开 30 分钟缓存：刚发版时不能被旧结果骗成「已是最新」
                        val info = container.updateChecker.load(forceRefresh = true)
                        if (info == null) {
                            PlaybackNotice.brief("检查更新失败，请稍后再试")
                            return@launch
                        }
                        if (info.latestCode <= BuildConfig.VERSION_CODE) {
                            PlaybackNotice.brief("已经是最新版本 $versionName")
                            return@launch
                        }
                        PlaybackNotice.brief("发现新版本 ${info.latestName}，可在弹窗里直接下载")
                        manualUpdate = info
                    }
                },
            )
            ActionRow(
                title = "再走一遍新手引导",
                subtitle = "忘了某个手势？把封面、歌词、队列的用法重新放一遍",
                action = "查看",
                onAction = {
                    // 走一次信号，根页面立刻弹层；标记已在仓库里清掉，下次冷启动也会再弹
                    container.onboardingRepository.replay()
                    PlaybackNotice.brief("新手引导已重新播放")
                },
            )
            ActionRow(
                title = "歌词校准",
                subtitle = if (calibCleared) "已清除" else "播放页长按歌词，把这句对齐到此刻",
                action = "清空",
                onAction = {
                    container.lyricsRepository.clearCalibrations()
                    calibCleared = true
                },
            )
            // 不再把接口地址摆出来：普通用户用不到，逆向的人倒是一眼就能抄走
            InfoRow("版本", versionName.ifBlank { "0.6.27" })
            InfoRow("说明", "在线曲目暂不支持收藏与加入歌单")
        }

        if (showSleepDialog) {
            SleepTimerDialog(
                onPick = { minutes ->
                    container.sleepTimer.start(minutes)
                    showSleepDialog = false
                    PlaybackNotice.brief("已设置 $minutes 分钟后暂停")
                },
                onDismiss = { showSleepDialog = false },
            )
        }

        manualUpdate?.let { info ->
            UpdateDialog(
                info = info,
                forced = false,
                currentName = versionName,
                onFinish = { manualUpdate = null },
                onLater = { manualUpdate = null },
            )
        }

        Spacer(Modifier.height(24.dp + bottomBarInset()))
    }
}

/**
 * 一个可收起的分组。
 *
 * **默认一律收起**：设置页要的是「一眼看到有哪些组」，不是把所有选项一次摊开；
 * 点标题才展开，展开状态跟着标题一起记住（rememberSaveable，滚出屏幕再回来不会自己合上）。
 *
 * 标题行永远是「标题 + 这一组的现状」，所以收起也不丢信息；展开/收起用 200ms 的
 * 高度动画，不用弹簧，避免长列表里出现回弹。
 */
@Composable
internal fun SettingGroup(
    title: String,
    summary: String? = null,
    initiallyExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by rememberSaveable(title) { mutableStateOf(initiallyExpanded) }

    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outline,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pressScale(0.985f)
            .clickable { expanded = !expanded }
            .padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (summary != null && summary.isNotBlank()) {
            Text(
                text = summary,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(10.dp))
        }
        // ＋/－ 也不是原地换字：展开时 ＋ 缩着退出、－ 弹出来
        AnimatedContent(
            targetState = expanded,
            transitionSpec = {
                (fadeIn(tween(150, delayMillis = 20)) +
                    scaleIn(spring(dampingRatio = 0.45f, stiffness = 560f), initialScale = 0.5f)) togetherWith
                    fadeOut(tween(120))
            },
            label = "groupSign",
        ) { isExpanded ->
            Text(
                text = if (isExpanded) "－" else "＋",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }

    AnimatedVisibility(
        visible = expanded,
        enter = expandVertically(
            animationSpec = tween(200, easing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)),
        ) + fadeIn(tween(160)),
        exit = shrinkVertically(animationSpec = tween(180)) + fadeOut(tween(140)),
    ) {
        Column(
            modifier = Modifier.padding(bottom = 10.dp),
            content = content,
        )
    }
}

/**
 * 关于页的作者行：头像 + 名字。
 *
 * 头像是 QQ 头像服务的固定地址，用户给的是 http，这里必须是 https——
 * targetSdk 28+ 起明文 http 会被系统直接拦掉，图永远不出现。
 */
@Composable
private fun AuthorRow() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            AsyncImage(
                model = AUTHOR_AVATAR_URL,
                contentDescription = "作者头像",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                text = "作者",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(text = "AM", style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/** 定时关闭的选择弹窗：常用时长一眼可选，也能自己填分钟数。 */
@Composable
private fun SleepTimerDialog(
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var custom by rememberSaveable { mutableStateOf("") }

    AmAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("定时关闭") },
        text = {
            Column {
                Text(
                    text = "到点暂停，进度还在，回来接着放。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(15, 30, 45).forEach { minutes ->
                        Chip(
                            text = "$minutes 分钟",
                            selected = false,
                            onClick = { onPick(minutes) },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(60, 90).forEach { minutes ->
                        Chip(
                            text = "$minutes 分钟",
                            selected = false,
                            onClick = { onPick(minutes) },
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = custom,
                        onValueChange = { input -> custom = input.filter { it.isDigit() }.take(3) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        placeholder = {
                            Text("自定义分钟数", style = MaterialTheme.typography.bodySmall)
                        },
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        onClick = { custom.toIntOrNull()?.let(onPick) },
                        enabled = (custom.toIntOrNull() ?: 0) in 1..999,
                    ) { Text("开始") }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 剩余时间：一小时以内报 mm:ss，超过报 h:mm:ss。 */
private fun formatRemaining(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) {
        "%d:%02d:%02d".format(h, m, s)
    } else {
        "%02d:%02d".format(m, s)
    }
}

/** 作者头像：https 必填（明文 http 会被系统拦掉）。 */
private const val AUTHOR_AVATAR_URL =
    "https://q2.qlogo.cn/headimg_dl?dst_uin=1958832195&spec=5"

@Composable
internal fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (selected) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        modifier = Modifier
            .clip(RoundedCornerShape(2.dp))
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                }
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
private fun ActionRow(
    title: String,
    subtitle: String,
    action: String,
    onAction: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(end = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 24.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(
            onClick = onAction,
            contentPadding = PaddingValues(horizontal = 8.dp),
        ) {
            Text(action, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.width(76.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 失败发生的时刻：一小时以内报分钟，其余报日期，够用户对上「我刚才试的那次」。 */
private fun formatClock(millis: Long): String {
    val diff = System.currentTimeMillis() - millis
    return when {
        diff < 60_000 -> "刚刚"
        diff < 3_600_000 -> "${diff / 60_000} 分钟前"
        else -> {
            val fmt = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA)
            fmt.format(java.util.Date(millis))
        }
    }
}


