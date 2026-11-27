package com.am.music.ui.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * 引导的文案表。
 *
 * 只讲「不看会走弯路」的东西：五个入口、底部小播放器、播放页两条手势、队列的排序与点按规则。
 * 有锚点的步骤给 [OnboardingStep.targetLabel]，根页面按同名标签传控件位置进来；
 * 讲别的页面的步骤不给锚点，用居中卡片，不硬指一个屏幕上不存在的位置。
 */
@Composable
fun rememberOnboardingSteps(): List<OnboardingStep> = remember {
    listOf(
        OnboardingStep(
            title = "一分钟上手",
            body = "AMMusic 是一个「点哪首、整张列表就是队列」的播放器。下面几步把最常用的手势和入口走一遍，随时可以跳过。",
            icon = OnboardingIcon.Sparkle,
        ),
        OnboardingStep(
            title = "五个入口",
            body = "在线：推荐、搜索、每日推荐；榜单：各类排行；本地：手机里的音乐；歌单：自己建的与收藏的；设置：音质、歌词样式、灵动岛。",
            targetLabel = OnboardingTargets.BOTTOM_TABS,
            icon = OnboardingIcon.Tabs,
        ),
        OnboardingStep(
            title = "底部这颗小播放器",
            body = "点一下封面进播放页；左右滑动切歌，往右滑是上一首；进度条一直跟着走，随时知道放到哪儿了。",
            targetLabel = OnboardingTargets.MINI_PLAYER,
            icon = OnboardingIcon.Player,
        ),
        OnboardingStep(
            title = "播放页的两条手势",
            body = "点封面看歌词，左右拖封面切歌，双击封面收藏。歌词页想回封面：下滑，或者轻点空白处——不用找按钮。",
            icon = OnboardingIcon.Lyrics,
        ),
        OnboardingStep(
            title = "播放队列",
            body = "长按一行拖动排序，拖到列表边缘会自动滚屏；点正在播的那首回播放页，点别的只切歌。「下一首播放」可以把歌插队。",
            icon = OnboardingIcon.Queue,
        ),
        OnboardingStep(
            title = "随时可以再看一次",
            body = "设置 → 关于 → 「再看一次新手引导」。用的过程中如果忘了某个手势，回来放一遍就行。",
            icon = OnboardingIcon.Sparkle,
        ),
    )
}

/** 锚点标签：根页面按这些名字登记控件位置，文案表按同名引用。 */
object OnboardingTargets {
    const val BOTTOM_TABS = "bottom_tabs"
    const val MINI_PLAYER = "mini_player"
}
