package com.am.music.ui.onboarding

import androidx.compose.ui.geometry.Rect

/**
 * 一步引导。
 *
 * [targetLabel] 为 null 就是「讲一件事」的居中卡片；
 * 有值则根页面把对应控件的位置传进来，这一层会在那个位置挖一个洞、把卡片放在洞的另一侧。
 *
 * [action] 决定这一步怎么过：讲事情的步骤点「下一步」；要求动手的步骤必须真的点中那个控件，
 * 而且点了之后 App 真的会动（进播放页、切 tab、开歌词），不是仅仅把卡片翻过去。
 */
data class OnboardingStep(
    val title: String,
    val body: String,
    val targetLabel: String? = null,
    val icon: OnboardingIcon = OnboardingIcon.Sparkle,
    val action: OnboardingAction = OnboardingAction.Info,
    /** 动手步骤的「现在点这里」提示，比正文短，永远贴在洞的旁边。 */
    val hint: String? = null,
)

/** 这一步要求用户做什么。 */
sealed interface OnboardingAction {

    /** 只讲一件事：挡住全屏，只能点卡片上的「下一步」。 */
    data object Info : OnboardingAction

    /**
     * 必须点中 [target] 对应的真实控件。
     *
     * 通关条件按「App 真的变了」来判断，而不是按「点到了」——否则用户点一下没反应也算过：
     * - [expectRoute] 不是 null 时，等当前路由变成这个路由；
     * - [expectRoute] 为 [ANY_TAB] 时，等用户切到任意一个底部 tab（不再是「在线」）；
     * - [expectSignal] 不是 null 时，等对应信号被置起（比如歌词页被打开过）。
     */
    data class Tap(
        val target: String,
        val expectRoute: String? = null,
        val expectSignal: OnboardingSignal? = null,
    ) : OnboardingAction {
        companion object {
            /** 占位路由名：只要离开「在线」就算切过 tab。 */
            const val ANY_TAB = "__any_tab__"
        }
    }
}

/** 需要页面之间传的完成信号。 */
enum class OnboardingSignal {
    /** 播放页的歌词页被打开过。 */
    LyricsOpened,
}

 /** 卡片左上角的小图标：按主题选，不用一屏一个都不一样。 */
enum class OnboardingIcon {
    Sparkle,
    Tabs,
    Player,
    Lyrics,
    Queue,
}

/** 一个控件在根布局里的位置。 */
typealias OnboardingTargetsMap = Map<String, Rect>
