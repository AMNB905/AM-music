package com.am.music.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

private val LightColors = lightColorScheme(
    primary = Ink,
    onPrimary = Paper,
    primaryContainer = Fog,
    onPrimaryContainer = Ink,
    secondary = Graphite,
    onSecondary = Paper,
    secondaryContainer = Fog,
    onSecondaryContainer = Ink,
    tertiary = Ink,
    onTertiary = Paper,
    background = Paper,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = Fog,
    onSurfaceVariant = Graphite,
    surfaceContainer = Fog,
    outline = Line,
    outlineVariant = Line,
    error = Ink,
    onError = Paper,
)

private val DarkColors = darkColorScheme(
    primary = Chalk,
    onPrimary = NightInk,
    primaryContainer = NightFog,
    onPrimaryContainer = Chalk,
    secondary = ChalkMuted,
    onSecondary = NightInk,
    secondaryContainer = NightFog,
    onSecondaryContainer = Chalk,
    tertiary = Chalk,
    onTertiary = NightInk,
    background = NightInk,
    onBackground = Chalk,
    surface = NightInk,
    onSurface = Chalk,
    surfaceVariant = NightSurface,
    onSurfaceVariant = ChalkMuted,
    surfaceContainer = NightFog,
    outline = NightLine,
    outlineVariant = NightLine,
    error = Chalk,
    onError = NightInk,
)

/** 直角/微圆角，避免卡片化。 */
private val MinimalShapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp),
    small = RoundedCornerShape(2.dp),
    medium = RoundedCornerShape(3.dp),
    large = RoundedCornerShape(4.dp),
    extraLarge = RoundedCornerShape(6.dp),
)

@Composable
fun AMMusicTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    accent: Color? = null,
    content: @Composable () -> Unit,
) {
    val base = if (darkTheme) DarkColors else LightColors
    MaterialTheme(
        colorScheme = accent?.let { accentScheme(base, it) } ?: base,
        typography = Typography,
        shapes = MinimalShapes,
        content = content,
    )
}

/**
 * 主题色只替换 primary / tertiary 这一路（图标、选中态、进度、收藏红心这些都用它），
 * 背景、文字、描边仍走黑白灰，所以换任何颜色都不会破坏极简的观感。
 * onPrimary 按亮度自动取黑或白，保证按钮上的字始终看得清。
 */
private fun accentScheme(base: ColorScheme, accent: Color): ColorScheme {
    val darkBackground = base.background.luminance() < 0.5f
    val onAccent = if (accent.luminance() > 0.62f) {
        // 亮色底 → 深字
        if (darkBackground) NightInk else Ink
    } else {
        // 暗色底 → 浅字
        if (darkBackground) Chalk else Paper
    }
    return base.copy(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = accent.copy(alpha = 0.16f),
        onPrimaryContainer = accent,
        // secondary 专门留给「次级控件（图标、操作）」：默认主题下它就是原来的灰，
        // 选了主题色后才跟着变色，这样控件覆盖面能做大，正文仍保持黑白。
        secondary = accent,
        onSecondary = onAccent,
        secondaryContainer = accent.copy(alpha = 0.12f),
        onSecondaryContainer = accent,
        tertiary = accent,
        onTertiary = onAccent,
    )
}
