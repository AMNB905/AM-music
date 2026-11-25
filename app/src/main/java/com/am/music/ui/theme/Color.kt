package com.am.music.ui.theme

import androidx.compose.ui.graphics.Color

// 极简配色：只用纸白/墨黑/灰阶，不用品牌色。
val Ink = Color(0xFF111113)
val Paper = Color(0xFFFFFFFF)
val Fog = Color(0xFFF4F4F5)
val Line = Color(0xFFE6E6E8)
val Graphite = Color(0xFF8A8A8F)

val NightInk = Color(0xFF0A0A0B)
val NightSurface = Color(0xFF101011)
val NightFog = Color(0xFF17171A)
val NightLine = Color(0xFF27272A)
val Chalk = Color(0xFFF3F3F4)
val ChalkMuted = Color(0xFF89898E)

/**
 * App 主题色预设，第一项 0 表示「默认黑白」——不走彩色，仍是最初的纸白/墨黑。
 * 其余为 ARGB 值，用于替换 MaterialTheme 的 primary（图标、选中态、进度等都跟它走）。
 */
val ThemeAccentPresets: List<Int> = listOf(
    0,
    0xFFF08FB1.toInt(),
    0xFFFF5A5F.toInt(),
    0xFFFFA94D.toInt(),
    0xFFF2C94C.toInt(),
    0xFF5FD47A.toInt(),
    0xFF4FD6D6.toInt(),
    0xFF4D9BFF.toInt(),
    0xFFB98CFF.toInt(),
)
