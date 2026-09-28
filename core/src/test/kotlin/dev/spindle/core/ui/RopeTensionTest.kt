package dev.spindle.core.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RopeTensionTest {

    private val viewport = 1000f
    private val minReach = 40f

    @Test
    fun `an empty side only reaches the minimum`() {
        assertEquals(minReach, RopeTension.reach(0, viewport, minReach), 0.001f)
        assertEquals(minReach, RopeTension.reach(-4, viewport, minReach), 0.001f)
    }

    @Test
    fun `reach grows with the count and saturates`() {
        val r1 = RopeTension.reach(1, viewport, minReach)
        val r5 = RopeTension.reach(5, viewport, minReach)
        val r10 = RopeTension.reach(10, viewport, minReach)
        val r50 = RopeTension.reach(50, viewport, minReach)
        assertTrue(r5 > r1, "reach must grow with the count")
        assertTrue(r50 > r5, "reach must keep growing, just slower")
        val ceiling = viewport * 0.62f
        assertTrue(r10 < ceiling, "reach must never exceed the ceiling")
        assertTrue(r50 <= ceiling, "reach must never exceed the ceiling")
        assertTrue(r50 > ceiling * 0.9f, "a large count should approach the ceiling")
    }

    @Test
    fun `the two sides are independent`() {
        val p = RopeTension.pull(aboveCount = 0, belowCount = 10, viewport = viewport, minReach = minReach)
        assertTrue(p.reachAbove < p.reachBelow)
        assertEquals(minReach, p.reachAbove, 0.001f)
    }

    @Test
    fun `leanBias is signed toward the fuller side`() {
        val below = RopeTension.pull(0, 10, viewport, minReach, lean = 20f)
        assertTrue(below.leanBias > 0f)
        val above = RopeTension.pull(10, 0, viewport, minReach, lean = 20f)
        assertTrue(above.leanBias < 0f)
        val equal = RopeTension.pull(5, 5, viewport, minReach, lean = 20f)
        assertEquals(0f, equal.leanBias, 0.001f)
    }

    @Test
    fun `degenerate inputs are finite`() {
        assertTrue(RopeTension.reach(0, 0f, minReach).isFinite())
        assertTrue(RopeTension.reach(-3, -100f, minReach).isFinite())
        val p = RopeTension.pull(-3, -7, 0f, minReach)
        assertTrue(p.reachAbove.isFinite() && p.reachBelow.isFinite() && p.leanBias.isFinite())
        assertEquals(minReach, p.reachAbove, 0.001f)
        assertEquals(minReach, p.reachBelow, 0.001f)
        assertEquals(0f, p.leanBias, 0.001f)
    }
}
