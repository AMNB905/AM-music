package com.am.music.util

import java.util.Locale

fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
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
