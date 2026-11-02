package com.shahu.tv

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.View
import android.widget.HorizontalScrollView

/**
 * Horizontal app row with Fire TV-like scrolling: the focused tile is kept away from the screen edges
 * (a fixed "keyline"), so you always see what comes next. Plain HorizontalScrollView otherwise.
 */
class AppRowView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : HorizontalScrollView(ctx, attrs) {

    /** Distance in px the focused tile keeps from the left/right edge while scrolling. */
    var keyline: Int = 0

    init {
        isHorizontalScrollBarEnabled = false
        isSmoothScrollingEnabled = true
        overScrollMode = View.OVER_SCROLL_NEVER
        isFocusable = false
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
    }

    override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect): Int {
        if (childCount == 0) return 0
        val width = width
        val left = scrollX
        val right = left + width
        // Same range HorizontalScrollView uses: content + start/end padding must fit.
        val maxScroll = (getChildAt(0).width - (width - paddingLeft - paddingRight)).coerceAtLeast(0)
        var delta = 0
        if (rect.left < left + keyline) {
            delta = rect.left - keyline - left
        } else if (rect.right > right - keyline) {
            delta = rect.right + keyline - right
        }
        return (left + delta).coerceIn(0, maxScroll) - left
    }

    /** Key repeat on a held D-pad: never let focus escape the row at the ends. */
    override fun executeKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val focused = findFocus()
            if (focused != null) {
                val dir = when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> FOCUS_LEFT
                    KeyEvent.KEYCODE_DPAD_RIGHT -> FOCUS_RIGHT
                    else -> 0
                }
                if (dir != 0) {
                    val next = focused.focusSearch(dir)
                    if (next == null || !isDescendant(next)) return true // swallow: stay on the end tile
                }
            }
        }
        return super.executeKeyEvent(event)
    }

    private fun isDescendant(v: View): Boolean {
        var p = v.parent
        while (p != null) {
            if (p === this) return true
            p = p.parent
        }
        return false
    }
}
