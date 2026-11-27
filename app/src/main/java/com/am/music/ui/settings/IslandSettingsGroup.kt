package com.am.music.ui.settings

import com.am.music.ui.components.Chip
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.am.music.data.island.IslandAppearance
import com.am.music.data.island.IslandPrefs
import com.am.music.service.DynamicIslandService
import kotlin.math.roundToInt
import com.am.music.ui.theme.pressFeedback

/**
 * 设置页「灵动岛」分组：开关 / 外观三档 / 自定义图片视频 / 位置与尺寸 / 重新定位。
 *
 * 岛本体跑在悬浮窗服务里（可能和设置页不在同一进程生命周期），所以这里每改一项
 * 都只做两件事：写盘 + 给服务发一条动作广播（服务没开就什么都不发，
 * 避免 startService 顺手把岛拉起来）。
 */
@Composable
internal fun IslandSettingsGroup() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var enabled by remember { mutableStateOf(IslandPrefs.isEnabled(context)) }
    var appearance by remember { mutableStateOf(IslandPrefs.appearance(context)) }
    var accent by remember { mutableStateOf(IslandPrefs.accentColor(context)) }
    var customName by remember { mutableStateOf(displayNameOf(context, IslandPrefs.customUri(context))) }
    var anchorSize by remember { mutableStateOf(IslandPrefs.anchorSize(context).toFloat()) }
    var width by remember { mutableStateOf(IslandPrefs.width(context).toFloat()) }
    var anchorX by remember { mutableStateOf(IslandPrefs.anchorX(context).takeIf { it >= 0f } ?: 50f) }
    var anchorY by remember { mutableStateOf(IslandPrefs.anchorY(context).takeIf { it >= 0f } ?: 12f) }
    var hasPermission by remember { mutableStateOf(DynamicIslandService.canDrawOverlays(context)) }

    // 从系统「显示在其他应用上层」页或文件选择器回来，状态可能变了，重读一遍
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasPermission = DynamicIslandService.canDrawOverlays(context)
                enabled = IslandPrefs.isEnabled(context)
                appearance = IslandPrefs.appearance(context)
                customName = displayNameOf(context, IslandPrefs.customUri(context))
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { applyCustomBackground(context, it) }
            ?.let { name ->
                customName = name
                appearance = IslandAppearance.CUSTOM
                IslandPrefs.setAppearance(context, IslandAppearance.CUSTOM)
                enabled = IslandPrefs.isEnabled(context)
            }
    }
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { applyCustomBackground(context, it) }
            ?.let { name ->
                customName = name
                appearance = IslandAppearance.CUSTOM
                IslandPrefs.setAppearance(context, IslandAppearance.CUSTOM)
                enabled = IslandPrefs.isEnabled(context)
            }
    }

    SettingGroup(
        title = "灵动岛",
        summary = when {
            !enabled -> "关闭"
            else -> "${appearance.label} · 悬浮窗"
        },
        initiallyExpanded = false,
    ) {
        Text(
            text = "顶部小胶囊，点开是播放卡，3 秒收起。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        Row(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Chip("关闭", !enabled) {
                enabled = false
                DynamicIslandService.setEnabled(context, false)
            }
            Chip("开启", enabled) {
                if (!DynamicIslandService.canDrawOverlays(context)) {
                    requestOverlayPermission(context)
                    Toast.makeText(
                        context,
                        "先允许「显示在其他应用上层」，回来再点开启",
                        Toast.LENGTH_LONG,
                    ).show()
                } else {
                    enabled = true
                    DynamicIslandService.setEnabled(context, true)
                }
            }
        }
        if (!hasPermission) {
            Text(
                text = "缺悬浮窗权限，点「开启」去授权",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp),
            )
        }

        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outlineVariant,
        )

        Text(
            text = "外观",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = appearance.note,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        Row(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IslandAppearance.entries.forEach { item ->
                Chip(item.label, appearance == item) {
                    appearance = item
                    IslandPrefs.setAppearance(context, item)
                    DynamicIslandService.refresh(context)
                }
            }
        }

        if (appearance == IslandAppearance.CUSTOM) {
            Text(
                text = customName ?: "还没选，挑张图或视频",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp),
            )
            Row(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Chip("选图片", false) { imagePicker.launch(arrayOf("image/*")) }
                Chip("选视频", false) { videoPicker.launch(arrayOf("video/*")) }
                if (IslandPrefs.customUri(context) != null) {
                    Chip("清除", false) {
                        IslandPrefs.setCustomUri(context, null)
                        customName = null
                        DynamicIslandService.refresh(context)
                    }
                }
            }
        }

        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outlineVariant,
        )

        Text(
            text = "岛内配色",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        Text(
            text = "只染岛上的条、频谱、播放键。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp),
        )
        Row(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IslandPrefs.ACCENT_PRESETS.forEach { preset ->
                val selected = accent == preset
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(Color(preset))
                        .border(
                            width = if (selected) 2.dp else 1.dp,
                            color = if (selected) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.outlineVariant
                            },
                            shape = CircleShape,
                        )
                        .pressFeedback(scale = 0.86f, haptic = true)
                        .clickable {
                            accent = preset
                            IslandPrefs.setAccentColor(context, preset)
                            nudge(context, DynamicIslandService.ACTION_SET_ACCENT)
                        },
                )
            }
        }

        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outlineVariant,
        )

        Text(
            text = "位置与尺寸",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        Text(
            text = "黑点就是锚点，放摄像头那儿。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp),
        )

        IslandSlider(
            title = "锚点直径",
            value = anchorSize,
            valueRange = 8f..40f,
            suffix = "dp",
            onChange = {
                anchorSize = it
                IslandPrefs.setAnchorSize(context, it.roundToInt())
            },
            onFinished = { nudge(context, DynamicIslandService.ACTION_SET_ANCHOR) },
        )
        IslandSlider(
            title = "胶囊长度",
            value = width,
            valueRange = 60f..180f,
            suffix = "dp",
            onChange = {
                width = it
                IslandPrefs.setWidth(context, it.roundToInt())
            },
            onFinished = { nudge(context, DynamicIslandService.ACTION_SET_WIDTH) },
        )
        IslandSlider(
            title = "锚点水平位置",
            value = anchorX,
            valueRange = 0f..100f,
            suffix = "%",
            onChange = {
                anchorX = it
                IslandPrefs.setAnchorPos(context, it, anchorY)
            },
            onFinished = { if (IslandPrefs.isEnabled(context)) nudgeAnchor(context, anchorX, anchorY) },
        )
        IslandSlider(
            title = "锚点垂直位置",
            value = anchorY,
            valueRange = 0f..100f,
            suffix = "%",
            onChange = {
                anchorY = it
                IslandPrefs.setAnchorPos(context, anchorX, it)
            },
            onFinished = { if (IslandPrefs.isEnabled(context)) nudgeAnchor(context, anchorX, anchorY) },
        )

        Row(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Chip("拖动定位", false) {
                if (!DynamicIslandService.canDrawOverlays(context)) {
                    requestOverlayPermission(context)
                } else {
                    runCatching {
                        context.startService(
                            Intent(context, DynamicIslandService::class.java)
                                .setAction(DynamicIslandService.ACTION_LOCATE)
                        )
                    }
                    Toast.makeText(context, "拖到摄像头，点黑点完成", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}

/**
 * 细线滑块：2dp 轨道 + 6dp 小圆点，整行只有 30dp 高。
 * 不用 M3 Slider——它自带 48dp 触摸区和大拇指，四条叠在一起占满一屏，看着笨重。
 * 拖动/点击都直接改值，抬手才给服务发一次指令。
 */
@Composable
private fun IslandSlider(
    title: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    suffix: String,
    onChange: (Float) -> Unit,
    onFinished: (() -> Unit)? = null,
) {
    val trackColor = MaterialTheme.colorScheme.outline
    val accentColor = MaterialTheme.colorScheme.primary
    val span = (valueRange.endInclusive - valueRange.start).takeIf { it > 0f } ?: 1f
    val fraction = ((value - valueRange.start) / span).coerceIn(0f, 1f)

    Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${value.roundToInt()}$suffix",
                style = MaterialTheme.typography.labelSmall,
                color = accentColor,
            )
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(30.dp)
                .pointerInput(valueRange) {
                    fun pick(x: Float) {
                        val f = (x / size.width).coerceIn(0f, 1f)
                        onChange(valueRange.start + f * span)
                    }
                    detectTapGestures { offset ->
                        pick(offset.x)
                        onFinished?.invoke()
                    }
                }
                .pointerInput(valueRange) {
                    fun pick(x: Float) {
                        val f = (x / size.width).coerceIn(0f, 1f)
                        onChange(valueRange.start + f * span)
                    }
                    detectHorizontalDragGestures(
                        onDragStart = { offset -> pick(offset.x) },
                        onHorizontalDrag = { change, _ -> pick(change.position.x) },
                        onDragEnd = { onFinished?.invoke() },
                        onDragCancel = { onFinished?.invoke() },
                    )
                },
        ) {
            val cy = size.height / 2f
            val stroke = 2.dp.toPx()
            val radius = 5.dp.toPx()
            drawLine(
                color = trackColor,
                start = Offset(0f, cy),
                end = Offset(size.width, cy),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = accentColor,
                start = Offset(0f, cy),
                end = Offset(size.width * fraction, cy),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
            drawCircle(
                color = accentColor,
                radius = radius,
                center = Offset(size.width * fraction, cy),
            )
        }
    }
}

/** 服务没开就只写盘不发动作——startService 会把岛顺手拉起来，那不是用户此刻的意思。 */
private fun nudge(context: Context, action: String) {
    if (!IslandPrefs.isEnabled(context)) return
    runCatching {
        context.startService(Intent(context, DynamicIslandService::class.java).setAction(action))
    }
}

private fun nudgeAnchor(context: Context, x: Float, y: Float) {
    if (!IslandPrefs.isEnabled(context)) {
        IslandPrefs.setAnchorPos(context, x, y)
        return
    }
    IslandPrefs.setAnchorPos(context, x, y)
    runCatching {
        context.startService(
            Intent(context, DynamicIslandService::class.java)
                .setAction(DynamicIslandService.ACTION_SET_ANCHOR_POS)
                .putExtra(DynamicIslandService.EXTRA_ANCHOR_X, x)
                .putExtra(DynamicIslandService.EXTRA_ANCHOR_Y, y)
        )
    }
}

/** 选完素材立刻落权限并让服务重建背景；返回展示用的文件名。 */
private fun applyCustomBackground(context: Context, uri: Uri): String? {
    runCatching {
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
    }
    IslandPrefs.setCustomUri(context, uri.toString())
    DynamicIslandService.refresh(context)
    return displayNameOf(context, uri.toString())
}

private fun displayNameOf(context: Context, uriString: String?): String? {
    if (uriString.isNullOrBlank()) return null
    val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return null
    val name = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
    }.getOrNull()
    return name ?: uri.lastPathSegment ?: "已选择的素材"
}

private fun requestOverlayPermission(context: Context) {
    val intent = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:${context.packageName}"),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }.onFailure {
        Toast.makeText(context, "没授权页，去系统设置里手动开", Toast.LENGTH_LONG).show()
    }
}
