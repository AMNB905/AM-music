package com.am.music.ui.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect

/**
 * 「小播放器 ↑ 播放页」这一段动作的共享状态。
 *
 * 以前是两段动画各播各的：拖动时封面长起来，松手后播放页再从头放大入镜——
 * 同一张封面被放大两次，看着就像重播了一遍。现在收成一段：
 *
 * 1. 拖动阶段封面由根布局的飞行层代画（跟手）；
 * 2. 松手过线时把 [fromDrag] 置真：播放页不做滑入、先把自己的封面藏起来；
 * 3. 飞行层把同一张封面继续送到 [targetRect]（播放页量出来上报的槽位）；
 * 4. 落位那一帧 [fromDrag] 置假，播放页的封面打开——同一张图、同一个矩形，交接看不见。
 */
object CoverFlight {

    /** 播放页封面槽位，窗口坐标。由 PlayerScreen 每次布局上报。 */
    var targetRect by mutableStateOf(Rect.Zero)

    /** 本次进入是「上滑拖进来的」：播放页不做滑入动画，封面先藏起来，等飞行层落位。 */
    var fromDrag by mutableStateOf(false)
}
