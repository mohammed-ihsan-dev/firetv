package com.shahu.tv

import kotlin.math.abs
import kotlin.random.Random

/** Pure carousel geometry (unit-tested; no Android types). */
object CarouselMath {
    /** Wraps any index into 0 until [count]. */
    fun wrap(i: Int, count: Int): Int = if (count <= 0) 0 else ((i % count) + count) % count

    /**
     * Signed distance of [index] from [selected] on a wrap-around ring of [count] items.
     * For 8 items the range is -3..4 (the single item at +4 is the hidden one "behind" the ring).
     */
    fun offset(index: Int, selected: Int, count: Int): Int {
        if (count <= 0) return 0
        var d = wrap(index - selected, count)
        if (d > count / 2) d -= count
        return d
    }

    /** True when an item jumps across the ring (e.g. -3 → +4) and must be repositioned without animating. */
    fun isWrapJump(oldOffset: Int, newOffset: Int): Boolean = abs(newOffset - oldOffset) > 1
}

/**
 * Random wallpaper variants per app key: a new pick each time the key gains focus, never the same
 * image twice in a row (when there are at least two choices). The *next* pick is decided in advance
 * so it can be preloaded.
 */
class VariantPicker(private val random: Random = Random.Default) {
    val last = HashMap<String, String>()
    private val next = HashMap<String, String>()

    fun pick(key: String, choices: List<String>): String? {
        if (choices.isEmpty()) return null
        if (choices.size == 1) return choices[0].also { last[key] = it }
        val prev = last[key]
        val chosen = next.remove(key)?.takeIf { it in choices && it != prev } ?: randomExcluding(choices, prev)
        last[key] = chosen
        next[key] = randomExcluding(choices, chosen)
        return chosen
    }

    /** What [pick] will return for [key] next time (for preloading); does not consume it. */
    fun upcoming(key: String, choices: List<String>): String? {
        if (choices.size <= 1) return choices.firstOrNull()
        return next.getOrPut(key) { randomExcluding(choices, last[key]) }
    }

    fun clearUpcoming() = next.clear()

    private fun randomExcluding(choices: List<String>, exclude: String?): String {
        val pool = choices.filter { it != exclude }.ifEmpty { choices }
        return pool[random.nextInt(pool.size)]
    }
}
