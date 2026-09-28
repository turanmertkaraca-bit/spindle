package dev.spindle.core.ui

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RopeCurveTest {

    private val bow = 12f
    private val half = 200f

    @Test
    fun `the bow peaks at the focus and vanishes away from it`() {
        assertEquals(bow, RopeCurve.offset(0f, bow, half), 0.001f)
        assertEquals(0f, RopeCurve.offset(half, bow, half), 0.001f)
        assertEquals(0f, RopeCurve.offset(-half * 2f, bow, half), 0.001f)
        assertTrue(RopeCurve.offset(40f, bow, half) > 0f)
    }

    @Test
    fun `the rope is flat at the peak and leans into the bump`() {
        assertEquals(0f, RopeCurve.slope(0f, bow, half), 0.001f)
        assertTrue(RopeCurve.slope(-half / 2f, bow, half) > 0f, "above the focus it climbs toward the bow")
        assertTrue(RopeCurve.slope(half / 2f, bow, half) < 0f, "below the focus it descends back")
    }

    @Test
    fun `offset is continuous in dy - the rope never kinks`() {
        var prev = RopeCurve.offset(-half, bow, half)
        var dy = -half
        while (dy <= half) {
            val cur = RopeCurve.offset(dy, bow, half)
            assertTrue(abs(cur - prev) < bow / half * 2f, "jump at $dy: $prev -> $cur")
            prev = cur
            dy += 1f
        }
    }

    @Test
    fun `lean is clamped`() {
        val l = RopeCurve.lean(0.1f, 10_000f, 1f)
        assertTrue(abs(l) <= 0.45f)
    }
}
