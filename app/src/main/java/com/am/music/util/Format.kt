package com.am.music.util

import java.util.Locale

fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

/** 合计时长说人话：42 分钟 / 1 小时 42 分 / 12 小时。别把「612:10」丢给用户。 */
fun formatTotalDuration(ms: Long): String {
    val minutes = (ms / 60_000L).toInt()
    return when {
        minutes >= 60 * 60 -> "${minutes / (60 * 60)} 小时"
        minutes >= 60 -> {
            val h = minutes / 60
            val m = minutes % 60
            if (m == 0) "$h 小时" else "$h 小时 $m 分"
        }
        else -> "${minutes.coerceAtLeast(1)} 分钟"
    }
}

/** 缓存占用：1.2 GB / 38.6 MB / 12 KB / 0 B。固定 Locale.US，避免小数点随系统语言变逗号。 */
fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val units = listOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var index = 0
    while (value >= 1024.0 && index < units.lastIndex) {
        value /= 1024.0
        index++
    }
    return if (index == 0) {
        String.format(Locale.US, "%d %s", bytes, units[index])
    } else {
        String.format(Locale.US, "%.1f %s", value, units[index])
    }
}
