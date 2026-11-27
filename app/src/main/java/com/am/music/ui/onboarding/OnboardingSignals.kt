package com.am.music.ui.onboarding

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect

/**
 * 引导期间，别的页面（目前是播放页）往根页面回传的「我做到这一步了」与「这个控件在哪」。
 *
 * 为什么不走参数？播放页在导航图里被建，要把封面 / ⋯ 的位置和「歌词已打开」这件事
 * 一路显式传回根页面，得给 [com.am.music.ui.player.PlayerScreen]、`AppNavGraph`、
 * `AMMusicRoot` 三个文件都加回调参数，而这些东西只服务于引导。
 *
 * 所以这里存三个 Compose 状态：引导开始时清空，引导结束后不再有人读。
 * 都是可被观察的 state，读到变化会自动重组，不会出现「点了但页面不动」。
 */
object OnboardingSignals {

    /** 播放页封面在根布局里的位置：引导要挖洞指着它。 */
    var coverBounds by mutableStateOf<Rect?>(null)

    /** 播放页右上那颗 ⋯ 的位置：队列那一步指着它。 */
    var menuBounds by mutableStateOf<Rect?>(null)

    /** 歌词页有没有被打开过：这一步的「通关条件」。 */
    var lyricsOpened by mutableStateOf(false)

    /** 每次引导开始 / 结束都清一遍，避免上一轮的残留把第一步直接判成完成。 */
    fun reset() {
        coverBounds = null
        menuBounds = null
        lyricsOpened = false
    }
}
