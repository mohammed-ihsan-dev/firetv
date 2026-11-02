package com.shahu.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class CarouselMathTest {
    @Test fun offsetsCoverMinus3To4ForEightItems() {
        for (sel in 0 until 8) {
            val offsets = (0 until 8).map { CarouselMath.offset(it, sel, 8) }.sorted()
            assertEquals(listOf(-3, -2, -1, 0, 1, 2, 3, 4), offsets)
            assertEquals(0, CarouselMath.offset(sel, sel, 8))
        }
    }

    @Test fun wrapAround() {
        assertEquals(7, CarouselMath.wrap(-1, 8))
        assertEquals(0, CarouselMath.wrap(8, 8))
        assertEquals(1, CarouselMath.offset(0, 7, 8))   // RIGHT from More Apps → YouTube is next
        assertEquals(-1, CarouselMath.offset(7, 0, 8))  // More Apps sits left of YouTube
    }

    @Test fun wrapJumpDetection() {
        assertTrue(CarouselMath.isWrapJump(-3, 4))
        assertTrue(CarouselMath.isWrapJump(4, -3))
        assertFalse(CarouselMath.isWrapJump(3, 4))
        assertFalse(CarouselMath.isWrapJump(0, -1))
    }
}

class VariantPickerTest {
    private val yt = listOf("TV YOUTUBE 1.jpg", "YOUTUBE-MAIN.jpg", "YT-WALLPAPER.jpg")

    @Test fun neverSameTwiceInARow() {
        val p = VariantPicker(Random(42))
        var prev: String? = null
        val seen = HashSet<String>()
        repeat(500) {
            val cur = p.pick("yt", yt)!!
            assertNotEquals(prev, cur)
            seen += cur; prev = cur
        }
        assertEquals(yt.toSet(), seen) // all three are used
    }

    @Test fun upcomingIsWhatPickReturns() {
        val p = VariantPicker(Random(7))
        p.pick("yt", yt)
        repeat(50) {
            val up = p.upcoming("yt", yt)
            assertEquals(up, p.upcoming("yt", yt)) // stable until consumed
            assertEquals(up, p.pick("yt", yt))
        }
    }

    @Test fun singleChoiceAlwaysSame() {
        val p = VariantPicker(Random(1))
        repeat(10) { assertEquals("JIO-HOTSTAR.jpg", p.pick("hotstar", listOf("JIO-HOTSTAR.jpg"))) }
    }

    @Test fun respectsRestoredLastPick() {
        val p = VariantPicker(Random(3))
        repeat(100) {
            p.last["yt"] = "YT-WALLPAPER.jpg"; p.clearUpcoming()
            assertNotEquals("YT-WALLPAPER.jpg", p.pick("yt", yt))
        }
    }
}
