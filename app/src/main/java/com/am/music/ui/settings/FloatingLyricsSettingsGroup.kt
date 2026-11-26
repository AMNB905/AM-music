package com.am.music.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.am.music.data.island.FloatingLyricsPrefs
import com.am.music.service.FloatingLyricsService

/**
 * 设置页「悬浮歌词」分组：只有一个开关，外加一段说明。
 *
 * 不复用岛的配色 / 尺寸那套——歌词条压在别的 App 上，越简单越不碍事：
 * 一行字 + 一点点底色，位置靠用户自己拖。
 */
@Composable
internal fun FloatingLyricsSettingsGroup() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var enabled by remember { mutableStateOf(FloatingLyricsPrefs.isEnabled(context)) }
    var hasPermission by remember { mutableStateOf(FloatingLyricsService.canDrawOverlays(context)) }

    // 从系统「显示在其他应用上层」页回来，权限和开关都可能变了
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasPermission = FloatingLyricsService.canDrawOverlays(context)
                enabled = FloatingLyricsPrefs.isEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    SettingGroup(
        title = "悬浮歌词",
        summary = if (enabled) "开启 · 浮在其他应用上" else "关闭",
        initiallyExpanded = false,
    ) {
        Text(
            text = "歌词浮在别的 App 上面，跟着播放走。拖着放，点一下回播放页，长按关掉。",
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
                FloatingLyricsService.setEnabled(context, false)
            }
            Chip("开启", enabled) {
                if (!FloatingLyricsService.canDrawOverlays(context)) {
                    requestFloatingLyricsPermission(context)
                    Toast.makeText(
                        context,
                        "先允许「显示在其他应用上层」，回来再点开启",
                        Toast.LENGTH_LONG,
                    ).show()
                } else {
                    enabled = true
                    FloatingLyricsService.setEnabled(context, true)
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
        Text(
            text = "只显示当前行和下一行；暂停时留在原位，切歌自动换词。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
        )
    }
}

private fun requestFloatingLyricsPermission(context: Context) {
    val intent = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:${context.packageName}"),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }.onFailure {
        Toast.makeText(context, "没授权页，去系统设置里手动开", Toast.LENGTH_LONG).show()
    }
}
