package com.shahu.tv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/** Tiny Wi-Fi glyph drawn with Canvas. level: -1 = offline, 0..3 = bars, 4 = ethernet. */
class WifiIndicatorView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    var level: Int = -1
        set(v) { if (field != v) { field = v; invalidate() } }

    private val on = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val off = Paint(on).apply { color = 0x40FFFFFF }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val oval = RectF()

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val sw = h * 0.11f
        on.strokeWidth = sw; off.strokeWidth = sw
        if (level == 4) { // ethernet: three small connected squares
            val s = h * 0.26f
            dot.color = 0xFFFFFFFF.toInt()
            c.drawRect(w / 2 - s / 2, h * 0.1f, w / 2 + s / 2, h * 0.1f + s, dot)
            c.drawRect(w * 0.12f, h * 0.64f, w * 0.12f + s, h * 0.64f + s, dot)
            c.drawRect(w * 0.88f - s, h * 0.64f, w * 0.88f, h * 0.64f + s, dot)
            c.drawLine(w / 2, h * 0.1f + s, w / 2, h * 0.5f, on)
            c.drawLine(w * 0.12f + s / 2, h * 0.5f, w * 0.88f - s / 2, h * 0.5f, on)
            c.drawLine(w * 0.12f + s / 2, h * 0.5f, w * 0.12f + s / 2, h * 0.64f, on)
            c.drawLine(w * 0.88f - s / 2, h * 0.5f, w * 0.88f - s / 2, h * 0.64f, on)
            return
        }
        val cx = w / 2; val cy = h * 0.92f
        for (i in 1..3) {
            val r = h * (0.26f + 0.28f * i) - sw
            oval.set(cx - r, cy - r, cx + r, cy + r)
            c.drawArc(oval, 225f, 90f, false, if (level >= i) on else off)
        }
        dot.color = if (level >= 0) 0xFFFFFFFF.toInt() else 0x40FFFFFF
        c.drawCircle(cx, cy - sw * 0.6f, sw * 0.95f, dot)
        if (level < 0) c.drawLine(w * 0.2f, h * 0.1f, w * 0.8f, h * 0.9f, on)
    }
}
