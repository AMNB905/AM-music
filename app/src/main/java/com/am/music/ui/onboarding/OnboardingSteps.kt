package com.am.music.ui.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.am.music.ui.navigation.Screen

/**
 * 引导的步骤表：手把手，一步步逼着用户动手。
 *
 * 前四步是「真的要点的」——点底部入口、点小播放器进播放页、点封面看歌词，
 * 每一步的洞都对着真实控件，点击直接落到控件上，App 真会切 tab / 进播放页 / 开歌词；
 * 只有真变化了这一步才算过。后两步讲完剩下的（队列、怎么再看一次）就收工。
 */
@Composable
fun rememberOnboardingSteps(): List<OnboardingStep> = remember {
    listOf(
        OnboardingStep(
            title = "跟着点，一分钟上手",
            body = "下面几步不是让你看，是让你点：手把手走一遍最常用的几个手势，走完就会用了。点错地方不会有反应，只跟着高亮的那块走就行。",
            icon = OnboardingIcon.Sparkle,
        ),
        OnboardingStep(
            title = "第一步：切个 tab",
            body = "底部这五个入口就是全部内容：在线（推荐 / 搜索 / 每日推荐）、榜单、本地、歌单、设置。先点第二个「榜单」，看看榜单页长什么样。",
            targetLabel = OnboardingTargets.BOTTOM_TABS,
            icon = OnboardingIcon.Tabs,
            action = OnboardingAction.Tap(
                target = OnboardingTargets.BOTTOM_TABS,
                expectRoute = OnboardingAction.Tap.ANY_TAB,
            ),
            hint = "点底部的「榜单」",
        ),
        OnboardingStep(
            title = "第二步：点小播放器进播放页",
            body = "歌在放的时候，底部会浮起一颗小播放器：封面、歌名、一条进度。点它的封面就进播放页。" +
                "还没在播的歌？先回「在线」随便点一首，它会自己浮上来。",
            targetLabel = OnboardingTargets.MINI_PLAYER,
            icon = OnboardingIcon.Player,
            action = OnboardingAction.Tap(
                target = OnboardingTargets.MINI_PLAYER,
                expectRoute = Screen.Player.route,
            ),
            hint = "点这颗小播放器的封面",
        ),
        OnboardingStep(
            title = "第三步：点封面看歌词",
            body = "播放页中间那块封面就是入口：点一下切到歌词。想回封面不用找按钮——从歌词上往下滑一把，或者轻点歌词外的空白处，就回去了。",
            targetLabel = OnboardingTargets.PLAYER_COVER,
            icon = OnboardingIcon.Lyrics,
            action = OnboardingAction.Tap(
                target = OnboardingTargets.PLAYER_COVER,
                expectSignal = OnboardingSignal.LyricsOpened,
            ),
            hint = "点一下封面",
        ),
        OnboardingStep(
            title = "第四步：队列里能拖动、能插队",
            body = "播放条右侧的队列图标：整张列表都在，长按一行拖到想要的位置，拖到边缘会自动滚屏；点正在播的那首回播放页，点别的只切歌。歌曲菜单里的「下一首播放」可以把歌插到队首。",
            icon = OnboardingIcon.Queue,
        ),
        OnboardingStep(
            title = "走完了，开始听歌",
            body = "忘了哪个手势，随时回设置 → 关于 → 「再看一遍新手引导」，它会再带你走一次。",
            icon = OnboardingIcon.Sparkle,
        ),
    )
}

/** 锚点标签：根页面按这些名字登记控件位置，文案表按同名引用。 */
object OnboardingTargets {
    const val BOTTOM_TABS = "bottom_tabs"
    const val MINI_PLAYER = "mini_player"
    const val PLAYER_COVER = "player_cover"
    const val PLAYER_QUEUE_MENU = "player_queue_menu"
}
