package com.am.music.ui.recognize

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.am.music.AppContainer
import com.am.music.data.model.ClipHit
import com.am.music.data.model.ClipStage
import com.am.music.data.model.VideoClip
import com.am.music.ui.components.MotionIconButton
import com.am.music.ui.components.AlbumArt
import kotlin.math.max

@Composable
fun RecognizeScreen(
    container: AppContainer,
    onBack: () -> Unit,
    viewModel: RecognizeViewModel = viewModel { RecognizeViewModel(container) },
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { result ->
        granted = result
        if (result) viewModel.start()
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 24.dp, top = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MotionIconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.secondary,
                )
            }
            Spacer(Modifier.width(4.dp))
            Text(
                text = "听歌识曲",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = "录一段，帮你找这首歌",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp),
        )

        Spacer(Modifier.height(18.dp))
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 24.dp),
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outline,
        )

        val song = uiState.song
        if (uiState.hits.size > 1 && (uiState.phase == RecognizePhase.DONE || uiState.linkStage != null)) {
            HitListBlock(
                hits = uiState.hits,
                source = uiState.clip?.sourceLine,
                playingKey = uiState.playingKey,
                running = uiState.phase != RecognizePhase.DONE,
                progress = uiState.progress,
                onPlay = viewModel::playHit,
                onAgain = viewModel::reset,
                modifier = Modifier.weight(1f),
            )
        } else if (uiState.phase == RecognizePhase.DONE && song != null) {
            ResultBlock(
                title = song.title,
                artist = song.artist,
                album = song.album,
                coverUrl = song.coverUrl,
                source = uiState.clip?.sourceLine,
                playing = uiState.playing,
                onPlay = viewModel::playRecognized,
                onAgain = viewModel::reset,
            )
        } else {
            Spacer(Modifier.weight(1f))
            RecordButton(
                phase = uiState.phase,
                elapsedSeconds = uiState.elapsedSeconds,
                onClick = {
                    when {
                        uiState.phase == RecognizePhase.RECORDING -> viewModel.stopAndRecognize()
                        uiState.phase == RecognizePhase.UPLOADING -> Unit
                        uiState.linkStage != null -> Unit
                        granted -> viewModel.start()
                        else -> permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
            )
            Spacer(Modifier.height(18.dp))
            Text(
                text = when {
                    uiState.linkStage != null -> listOfNotNull(
                        stageText(uiState.linkStage),
                        uiState.progress,
                    ).joinToString(" · ")
                    uiState.phase == RecognizePhase.IDLE -> "点上面的圆开始录音；也可以粘贴视频链接"
                    uiState.phase == RecognizePhase.RECORDING ->
                        "已录 ${uiState.elapsedSeconds} 秒 · 点停止开始识别（最长 ${RecognizeViewModel.MAX_SECONDS} 秒）"
                    uiState.phase == RecognizePhase.UPLOADING ->
                        uiState.progress ?: "正在识别录音 · 大约几秒"
                    else -> ""
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            )
            Spacer(Modifier.height(20.dp))
            LinkSection(
                link = uiState.link,
                stage = uiState.linkStage,
                clip = uiState.clip,
                range = uiState.range,
                onLinkChange = viewModel::setLink,
                onRangeChange = viewModel::setRange,
                onRecognize = viewModel::recognizeLink,
            )
            Spacer(Modifier.height(14.dp))
            Spacer(Modifier.weight(1f))
        }

        if (uiState.message != null) {
            Text(
                text = uiState.message.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
            )
        }

        Text(
            text = "靠近声源、别太吵；录音用完就删",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
        )
    }
}

/** 链接识别的四个阶段，每个都给一句"还要等什么"的说法。 */
private fun stageText(stage: ClipStage?): String = when (stage) {
    ClipStage.PARSING -> "正在解析链接…（平台风控时可能要等十几秒）"
    ClipStage.DOWNLOADING -> "正在下载音轨…"
    ClipStage.DECODING -> "正在从视频里抽音频…"
    ClipStage.UPLOADING -> "正在识别…"
    null -> ""
}

/**
 * 粘贴链接识别。
 *
 * 不弹对话框、不占整屏：一行输入 + 两个文字按钮，和录音入口并存，
 * 用户想用哪个用哪个。识别中的状态文字在上方那行 label 里更新。
 */
@Composable
private fun LinkSection(
    link: String,
    stage: ClipStage?,
    clip: VideoClip?,
    range: String,
    onLinkChange: (String) -> Unit,
    onRangeChange: (String) -> Unit,
    onRecognize: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val busy = stage != null
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
        Text(
            text = "或粘视频链接 · 抖音/快手/B站",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = link,
                onValueChange = onLinkChange,
                singleLine = true,
                enabled = !busy,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    Box {
                        if (link.isEmpty()) {
                            Text(
                                text = "粘贴分享文案或链接",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                            )
                        }
                        inner()
                    }
                },
            )
            Text(
                text = "粘贴",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clickable(enabled = !busy) {
                        clipboard.getText()?.text?.trim()?.let { onLinkChange(it) }
                    }
                    .padding(start = 12.dp, top = 6.dp, bottom = 6.dp),
            )
            Text(
                text = "识别",
                style = MaterialTheme.typography.labelSmall,
                color = if (busy || link.isBlank()) {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                } else {
                    MaterialTheme.colorScheme.primary
                },
                modifier = Modifier
                    .clickable(enabled = !busy && link.isNotBlank(), onClick = onRecognize)
                    .padding(start = 14.dp, top = 6.dp, bottom = 6.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)
        // 长视频可以只扫一段：请求数直接等于这段里的有声窗数
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "只听这一段",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(72.dp),
            )
            BasicTextField(
                value = range,
                onValueChange = onRangeChange,
                singleLine = true,
                enabled = !busy,
                textStyle = MaterialTheme.typography.bodySmall.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    Box {
                        if (range.isEmpty()) {
                            Text(
                                text = "如 0:30-2:00，空着就整条",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                            )
                        }
                        inner()
                    }
                },
            )
        }
        val detail = clip?.sourceLine.orEmpty()
        if (detail.isNotBlank() && !busy) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "上一次：$detail",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun RecordButton(
    phase: RecognizePhase,
    elapsedSeconds: Int,
    onClick: () -> Unit,
) {
    val progress = when (phase) {
        RecognizePhase.RECORDING ->
            elapsedSeconds.toFloat() / RecognizeViewModel.MAX_SECONDS
        RecognizePhase.UPLOADING -> 1f
        else -> 0f
    }
    val outlineColor = MaterialTheme.colorScheme.outline
    val ringColor = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(176.dp)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val radius = max(size.width, size.height) / 2f - 2.dp.toPx()
                val center = Offset(size.width / 2f, size.height / 2f)
                drawCircle(
                    color = outlineColor,
                    radius = radius,
                    center = center,
                    style = Stroke(width = 1.dp.toPx()),
                )
                if (progress > 0f) {
                    drawArc(
                        color = ringColor,
                        startAngle = -90f,
                        sweepAngle = 360f * progress.coerceIn(0f, 1f),
                        useCenter = false,
                        topLeft = Offset(
                            center.x - radius,
                            center.y - radius,
                        ),
                        size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2),
                        style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Butt),
                    )
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = when (phase) {
                        RecognizePhase.IDLE -> "开始"
                        RecognizePhase.RECORDING -> "停止"
                        RecognizePhase.UPLOADING -> "识别中"
                        RecognizePhase.DONE -> ""
                    },
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (phase == RecognizePhase.RECORDING) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "${elapsedSeconds}s",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * 长视频的分段识别结果：一条视频里连着好几首，按时间顺序列出来。
 *
 * 为什么不做「一次全部加入队列」：这些歌多半来自不同专辑，直接串起来播并不是用户要的；
 * 点哪首播哪首更符合「我在刷到一条合辑，想知道里面都是什么歌」的场景。
 */
@Composable
private fun HitListBlock(
    hits: List<ClipHit>,
    source: String?,
    playingKey: String?,
    running: Boolean,
    progress: String?,
    onPlay: (Int) -> Unit,
    onAgain: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 20.dp),
    ) {
        Text(
            text = if (running) "已经听出 ${hits.size} 首，继续扫描…" else "这条视频里听出 ${hits.size} 首",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = when {
                running && !progress.isNullOrBlank() -> progress
                running -> "后面可能还有，边扫边列"
                else -> "点一首直接播；时间是它在视频里的位置"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!source.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "来自 · $source",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(14.dp))
        hits.forEachIndexed { index, hit ->
            if (index > 0) {
                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !running && playingKey == null) { onPlay(index) }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = formatPosition(hit.startSec),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(46.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        text = hit.song.title,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = hit.song.artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = when {
                        running -> "扫描中"
                        playingKey == hit.song.key -> "解析中"
                        else -> "播放"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        TextButton(onClick = onAgain, enabled = !running) {
            Text("换一条链接再试", style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** 12 → "0:12"，312 → "5:12"。 */
private fun formatPosition(seconds: Int): String = "%d:%02d".format(seconds / 60, seconds % 60)

@Composable
private fun ResultBlock(
    title: String,
    artist: String,
    album: String,
    coverUrl: String?,
    source: String?,
    playing: Boolean,
    onPlay: () -> Unit,
    onAgain: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AlbumArt(
            uri = coverUrl?.let { android.net.Uri.parse(it) },
            size = 132.dp,
            shape = RoundedCornerShape(2.dp),
        )
        Spacer(Modifier.height(22.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = listOf(artist, album).filter { it.isNotBlank() }.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (!source.isNullOrBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = "来自 · $source",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
            Box(
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
                    .clickable(enabled = !playing, onClick = onPlay)
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            ) {
                Text(
                    text = if (playing) "解析中" else "播放",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
            TextButton(onClick = onAgain) {
                Text("再试一次", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

