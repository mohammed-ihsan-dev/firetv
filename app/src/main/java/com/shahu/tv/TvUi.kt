package com.shahu.tv

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Tiny builder for D-pad friendly settings screens (Settings, Device Manager): a header plus a vertical,
 * scrolling list of focusable rows. Plain Views only — consistent with the rest of the app.
 */
/**
 * True if [intent] opens a real settings page. Fire OS 7 maps several standard Settings actions
 * (Accessibility, Usage access) to Amazon "CTSDummy…" placeholder activities that show nothing useful.
 */
fun Activity.opensRealPage(intent: android.content.Intent): Boolean {
    val ri = packageManager.resolveActivity(intent, 0) ?: return false
    return !ri.activityInfo.name.contains("Dummy", ignoreCase = true)
}

class TvUi(private val a: Activity) {
    private val d = a.resources.displayMetrics.density
    fun dp(v: Int) = (v * d + 0.5f).toInt()

    lateinit var list: LinearLayout
        private set
    lateinit var scroll: ScrollView
        private set

    /** Builds the screen skeleton and sets it as the content view. */
    fun screen(title: String, subtitle: String): TvUi {
        val root = FrameLayout(a).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF22242A.toInt(), 0xFF121317.toInt(), 0xFF08090B.toInt()))
        }
        val col = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(96), dp(36), dp(96), 0)
        }
        col.addView(LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(View(a).apply { setBackgroundColor(a.getColor(R.color.accent_red)) }, LinearLayout.LayoutParams(dp(4), dp(20)).apply { marginEnd = dp(12) })
            addView(text(a.getString(R.string.brand), 13f, a.getColor(R.color.text_secondary), bold = true).apply { letterSpacing = 0.18f })
        })
        col.addView(text(title, 30f, Color.WHITE, medium = true).apply { setPadding(0, dp(14), 0, 0) })
        col.addView(text(subtitle, 13f, a.getColor(R.color.text_tertiary)).apply { setPadding(0, dp(4), 0, dp(14)) })
        scroll = ScrollView(a).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            setPadding(0, dp(4), 0, dp(40))
        }
        list = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        col.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        a.setContentView(root)
        return this
    }

    fun section(title: String): TextView = text(title.uppercase(), 11f, a.getColor(R.color.text_tertiary), bold = true).apply {
        letterSpacing = 0.14f
        setPadding(dp(14), dp(18), 0, dp(6))
        list.addView(this)
    }

    /** A focusable row. [onClick] null → informational (still focusable so it can be read/scrolled). */
    fun row(title: String, subtitle: String? = null, value: String? = null, onClick: (() -> Unit)? = null): Row {
        val r = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(52)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            isFocusable = true
            background = rowBackground()
        }
        val texts = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        val t = text(title, 16f, Color.WHITE, medium = true)
        val s = text(subtitle ?: "", 12f, a.getColor(R.color.text_tertiary)).apply {
            visibility = if (subtitle.isNullOrEmpty()) View.GONE else View.VISIBLE
            setPadding(0, dp(2), 0, 0)
        }
        texts.addView(t); texts.addView(s)
        r.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val v = text(value ?: "", 14f, a.getColor(R.color.text_secondary)).apply {
            gravity = Gravity.END
            visibility = if (value.isNullOrEmpty()) View.GONE else View.VISIBLE
            setPadding(dp(16), 0, 0, 0)
        }
        r.addView(v)
        if (onClick != null) r.setOnClickListener { onClick() }
        list.addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4) })
        return Row(r, t, s, v)
    }

    class Row(val view: View, val title: TextView, val subtitle: TextView, val value: TextView) {
        fun setValue(text: String?) { value.text = text ?: ""; value.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE }
        fun setSubtitle(text: String?) { subtitle.text = text ?: ""; subtitle.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE }
    }

    private fun rowBackground() = StateListDrawable().apply {
        val r = dp(10).toFloat()
        addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
            cornerRadius = r; setColor(0x33FFFFFF); setStroke(dp(2), 0xE6FFFFFF.toInt())
        })
        addState(intArrayOf(), GradientDrawable().apply { cornerRadius = r; setColor(0x0FFFFFFF) })
    }

    fun text(s: String, sp: Float, color: Int, bold: Boolean = false, medium: Boolean = false) = TextView(a).apply {
        text = s
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        includeFontPadding = false
        typeface = when {
            bold -> Typeface.DEFAULT_BOLD
            medium -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
            else -> Typeface.DEFAULT
        }
    }
}
