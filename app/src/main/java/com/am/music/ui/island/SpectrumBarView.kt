package com.am.music.ui.island

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

/**
 * 收起态那 12 根模拟频谱柱。
 *
 * 用自绘而不是 12 个子 View：高度每帧都在变，走子 View 就得每帧 requestLayout，
 * 120Hz 设备上会明显看到胶囊跟着抖。这里只 invalidate（只重绘），由服务的
 * ValueAnimator 按 vsync 驱动。
 */
class SpectrumBarView(context: Context) : View(context) {

    /** 12 柱高度（0..1），由外部每帧写入。 */
    var bars: FloatArray = FloatArray(12)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    /** 主题色：跟着设置走，改颜色不需要重建视图。 */
    var barColor: Int = Color.WHITE
        set(value) {
            if (field == value) return
            field = value
            paint.color = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        val w = width
        val h = height
        if (w <= 0 || h <= 0 || bars.isEmpty()) return
        val density = resources.displayMetrics.density
        val gap = w / bars.size
        val barW = (gap * 0.6f).coerceAtLeast(density)
        val radius = 0.5f * density
        val minH = 3 * density
        for (i in bars.indices) {
            val v = bars[i].coerceIn(0f, 1f)
            val barH = (h * v).coerceAtLeast(minH)
            val left = i * gap + (gap - barW) / 2f
            val top = (h - barH) / 2f
            canvas.drawRoundRect(left, top, left + barW, top + barH, radius, radius, paint)
        }
    }
}
