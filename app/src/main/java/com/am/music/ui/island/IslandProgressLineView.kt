package com.am.music.ui.island

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import kotlin.math.abs

/**
 * 收起态胶囊底部那根 2dp 进度线。
 *
 * 收起之后原本完全没有「播到哪」的信息，展开才能看见 SeekBar。
 * 这里在胶囊底边画一条极细的线：没播的部分是 22% 的主题色，播过的部分是满色。
 * 只在内部 invalidate（不 requestLayout），所以 200ms 一次的位置更新不会让胶囊抖动。
 */
class IslandProgressLineView(context: Context) : View(context) {

    private val density = resources.displayMetrics.density
    private val playedPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val restPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { alpha = 56 }

    private var lineColor: Int = Color.WHITE

    var fraction: Float = 0f
        set(value) {
            val v = value.coerceIn(0f, 1f)
            if (abs(field - v) < 0.002f) return
            field = v
            invalidate()
        }

    var color: Int
        get() = lineColor
        set(value) {
            if (lineColor == value) return
            lineColor = value
            playedPaint.color = value
            restPaint.color = value
            invalidate()
        }

    init {
        playedPaint.color = Color.WHITE
        restPaint.color = Color.WHITE
    }

    override fun onDraw(canvas: Canvas) {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return
        val inset = 9 * density
        val thickness = 2 * density
        val top = h - 5 * density
        val left = inset
        val right = w - inset
        if (right <= left) return
        val radius = thickness / 2f

        canvas.drawRoundRect(left, top, right, top + thickness, radius, radius, restPaint)
        val playedRight = left + (right - left) * fraction
        if (playedRight > left) {
            canvas.drawRoundRect(left, top, playedRight, top + thickness, radius, radius, playedPaint)
        }
    }
}
