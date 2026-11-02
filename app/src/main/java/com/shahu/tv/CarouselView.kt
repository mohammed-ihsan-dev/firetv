package com.shahu.tv

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import kotlin.math.abs
import kotlin.math.min

/**
 * Flat, wrap-around circular-icon carousel (no 3D wheel).
 *
 *   ·  ◯  ◯  ⬤  ◯  ◯  ·          selected icon in the centre, smaller icons either side.
 *
 * One focusable view that handles LEFT / RIGHT itself, so focus can never "get lost".
 * DPAD_CENTER / ENTER (on key-up, like a normal View click) → [onClick].
 * Every icon is a plain ImageView moved with render-thread properties (translationX/scale/alpha):
 * no layout passes, no blur, no per-frame allocation. A soft brand-coloured halo sits behind the centre.
 */
class CarouselView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : FrameLayout(ctx, attrs) {

    class Item(val iconRes: Int, val label: String, val glowColor: Int)

    var onSelectionChanged: ((index: Int) -> Unit)? = null
    var onClick: ((index: Int) -> Unit)? = null

    var selectedIndex = 0
        private set

    private var items: List<Item> = emptyList()
    private val icons = ArrayList<ImageView>()
    private val offsets = ArrayList<Int>()
    private val density = resources.displayMetrics.density
    private val iconSize = (136 * density).toInt()

    // Slot geometry by |offset| (0 = centre). Offset 4 is the one hidden item "behind" the ring.
    private val slotX = floatArrayOf(0f, 130f, 226f, 304f, 374f).map { it * density }
    private val slotScale = floatArrayOf(1f, 0.66f, 0.5f, 0.4f, 0.32f)
    private val slotAlpha = floatArrayOf(1f, 0.82f, 0.55f, 0.3f, 0f)

    private val haloDrawable = GradientDrawable().apply {
        gradientType = GradientDrawable.RADIAL_GRADIENT
        gradientRadius = 108 * density
        colors = intArrayOf(0x55FFFFFF, 0x00FFFFFF)
    }
    private val halo = View(ctx).apply { background = haloDrawable; isFocusable = false; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    private val ring = View(ctx).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setStroke((2.5f * density).toInt(), 0xE6FFFFFF.toInt())
        }
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private var haloColor = 0x55FFFFFF
    private var haloAnim: ValueAnimator? = null
    private var centerDown = false

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        clipChildren = false
        clipToPadding = false
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        val haloSize = (216 * density).toInt()
        addView(halo, LayoutParams(haloSize, haloSize, Gravity.CENTER))
        val ringSize = iconSize + (12 * density).toInt()
        addView(ring, LayoutParams(ringSize, ringSize, Gravity.CENTER))
    }

    fun setItems(list: List<Item>, selected: Int) {
        icons.forEach { removeView(it) }
        icons.clear(); offsets.clear()
        items = list
        selectedIndex = CarouselMath.wrap(selected, list.size)
        list.forEachIndexed { i, item ->
            val iv = ImageView(context).apply {
                setImageResource(item.iconRes)
                scaleType = ImageView.ScaleType.FIT_CENTER
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            addView(iv, LayoutParams(iconSize, iconSize, Gravity.CENTER))
            icons += iv
            offsets += CarouselMath.offset(i, selectedIndex, list.size)
        }
        ring.bringToFront()
        layoutIcons(animate = false)
        setHalo(list.getOrNull(selectedIndex)?.glowColor ?: 0xFFFFFFFF.toInt(), animate = false)
        contentDescription = list.getOrNull(selectedIndex)?.label
    }

    fun select(index: Int, animate: Boolean = true) {
        if (items.isEmpty()) return
        val idx = CarouselMath.wrap(index, items.size)
        if (idx == selectedIndex && animate) return
        selectedIndex = idx
        layoutIcons(animate)
        setHalo(items[idx].glowColor, animate)
        contentDescription = items[idx].label
        onSelectionChanged?.invoke(idx)
    }

    private fun layoutIcons(animate: Boolean) {
        icons.forEachIndexed { i, iv ->
            val newOff = CarouselMath.offset(i, selectedIndex, icons.size)
            val oldOff = offsets[i]
            offsets[i] = newOff
            val slot = min(abs(newOff), 4)
            val tx = if (newOff < 0) -slotX[slot] else slotX[slot]
            val sc = slotScale[slot]
            val al = slotAlpha[slot]
            iv.animate().cancel()
            if (!animate || CarouselMath.isWrapJump(oldOff, newOff)) {
                // Jump across the ring: reposition invisibly, then fade in if the new slot is visible.
                iv.translationX = tx; iv.scaleX = sc; iv.scaleY = sc
                if (animate && al > 0f) {
                    iv.alpha = 0f
                    iv.animate().alpha(al).setDuration(MOVE_MS).setInterpolator(EASE).start()
                } else iv.alpha = al
            } else {
                iv.animate().translationX(tx).scaleX(sc).scaleY(sc).alpha(al)
                    .setDuration(MOVE_MS).setInterpolator(EASE).start()
            }
        }
        // Draw order: centre on top (bringToFront only reorders children; no layout pass needed here).
        icons.getOrNull(selectedIndex)?.let { it.bringToFront(); ring.bringToFront() }
    }

    private fun setHalo(color: Int, animate: Boolean) {
        val target = (color and 0x00FFFFFF) or (HALO_ALPHA shl 24)
        haloAnim?.cancel()
        if (!animate) { haloColor = target; haloDrawable.colors = intArrayOf(target, target and 0x00FFFFFF); return }
        val from = haloColor
        haloAnim = ValueAnimator.ofObject(ArgbEvaluator(), from, target).apply {
            duration = MOVE_MS
            interpolator = EASE
            addUpdateListener {
                val c = it.animatedValue as Int
                haloColor = c
                haloDrawable.colors = intArrayOf(c, c and 0x00FFFFFF)
            }
            start()
        }
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        ring.animate().alpha(if (gainFocus) 1f else 0f).setDuration(150).start()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> { select(selectedIndex - 1); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { select(selectedIndex + 1); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (event.repeatCount == 0) centerDown = true
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> return true // single row: keep focus here
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            if (centerDown && !event.isCanceled && items.isNotEmpty()) {
                centerDown = false
                // Small "press" pulse on the centre icon, then click.
                icons.getOrNull(selectedIndex)?.animate()?.scaleX(0.94f)?.scaleY(0.94f)?.setDuration(70)
                    ?.withEndAction { icons.getOrNull(selectedIndex)?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(110)?.start() }
                    ?.start()
                onClick?.invoke(selectedIndex)
            }
            centerDown = false
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    companion object {
        private const val MOVE_MS = 300L
        private const val HALO_ALPHA = 0x66
        private val EASE = PathInterpolator(0.25f, 0.1f, 0.25f, 1f)
    }
}
