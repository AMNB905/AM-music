package com.am.music.ui.onboarding

import androidx.compose.ui.geometry.Rect

/**
 * 一步引导。
 *
 * [targetLabel] 为 null 就是「讲一件事」的居中卡片（讲别的页面上的手势时用它）；
 * 有值则根页面把对应控件的位置传进来，这一层会在那个位置挖一个洞、把卡片放在洞的另一侧。
 */
data class OnboardingStep(
    val title: String,
    val body: String,
    val targetLabel: String? = null,
    val icon: OnboardingIcon = OnboardingIcon.Sparkle,
)

/** 卡片左上角的小图标：按主题选，不用一屏一个都不一样。 */
enum class OnboardingIcon {
    Sparkle,
    Tabs,
    Player,
    Lyrics,
    Queue,
}
