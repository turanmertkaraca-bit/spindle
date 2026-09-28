package dev.spindle.core.ui

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The rope contract. The whole point of the redesign is that expansion is a
 * CONTINUOUS function of scroll: heights flow, they never snap. These tests
 * pin the math that makes that true.
 */
class RopeLayoutTest {

    private val m = RopeLayout.Metrics(
        nodeSize = 10f,
        baseGap = 24f,
        focusGapExtra = 80f,
        focusHalfWidth = 1.5f,
        focusY = 300f,
    )
    private val nodes = (0 until 12).map { RopeLayout.Node("n$it") }

    @Test
    fun `empty rope is safe`() {
        val r = RopeLayout.compute(emptyList(), 0f, m)
        assertEquals(0f, r.contentArc)
        assertTrue(r.placements.isEmpty())
    }

    @Test
    fun `a single droplet sits on the focus and is fully bloomed`() {
        val r = RopeLayout.compute(listOf(RopeLayout.Node("only")), 0f, m)
        assertEquals(1, r.placements.size)
        assertEquals(1f, r.placements[0].bloom)
        assertTrue(r.placements[0].detached)
        assertFalse(r.hasBelow)
    }

    @Test
    fun `spacing opens around the focus`() {
        // focus on droplet 5
        val r = RopeLayout.compute(nodes, RopeLayout.scrollForIndex(5, m), m)
        val arcs = r.placements.map { it.arc }
        // gap right at the focus (between 4 and 5, and 5 and 6)
        val gapLeft = arcs[5] - arcs[4] - m.nodeSize
        val gapRight = arcs[6] - arcs[5] - m.nodeSize
        // a gap far away
        val gapFar = arcs[2] - arcs[1] - m.nodeSize
        assertTrue(gapLeft > gapFar + 20f, "gap at focus ($gapLeft) should be much larger than far ($gapFar)")
        assertTrue(gapRight > gapFar + 20f, "gap at focus ($gapRight) should be much larger than far ($gapFar)")
    }

    @Test
    fun `at rest exactly the focused droplet is bloomed`() {
        val r = RopeLayout.compute(nodes, RopeLayout.scrollForIndex(4, m), m)
        assertEquals("n4", r.focusedKey)
        for (p in r.placements) {
            if (p.index == 4) assertEquals(1f, p.bloom, 0.001f)
            else assertEquals(0f, p.bloom, 0.001f, "only the focused droplet blooms")
        }
    }

    @Test
    fun `arcs are strictly increasing`() {
        val r = RopeLayout.compute(nodes, 137f, m)
        var prev = -1f
        for (p in r.placements) {
            assertTrue(p.arc > prev, "arc must increase: ${p.key}")
            prev = p.arc
        }
    }

    @Test
    fun `bloom is continuous in scroll - no snapping`() {
        // The core promise: a 1px scroll step can never jump a droplet open.
        val maxStep = m.stride
        val maxDelta = 0.12f
        var prev = RopeLayout.compute(nodes, 0f, m)
        var s = 1f
        while (s <= RopeLayout.maxScroll(nodes.size, m)) {
            val cur = RopeLayout.compute(nodes, s, m)
            for (i in nodes.indices) {
                val d = abs(cur.placements[i].bloom - prev.placements[i].bloom)
                assertTrue(d <= maxDelta, "bloom jumped ${d} for ${nodes[i].key} at scroll $s")
            }
            prev = cur
            s += 1f
        }
    }

    @Test
    fun `the focus advances monotonically as the user scrolls`() {
        var last = -1
        var s = 0f
        while (s <= RopeLayout.maxScroll(nodes.size, m)) {
            val idx = RopeLayout.compute(nodes, s, m).focusIndex
            assertTrue(idx >= last, "focus went backwards at $s: $last -> $idx")
            last = idx
            s += m.stride / 3f
        }
    }

    @Test
    fun `hasBelow is false only at the tail`() {
        assertTrue(RopeLayout.compute(nodes, 0f, m).hasBelow)
        assertFalse(RopeLayout.compute(nodes, RopeLayout.scrollForIndex(nodes.lastIndex, m), m).hasBelow)
    }

    @Test
    fun `the rope grows with the number of droplets`() {
        val short = RopeLayout.compute(nodes.take(3), 0f, m).contentArc
        val long = RopeLayout.compute(nodes, 0f, m).contentArc
        assertTrue(long > short)
    }

    @Test
    fun `detent is null when already there`() {
        val target = RopeLayout.scrollForIndex(3, m)
        assertEquals(null, RopeLayout.detent(3, target, m))
        assertNotNull(RopeLayout.detent(3, 0f, m))
        assertEquals(target, RopeLayout.detent(3, 0f, m))
    }

    @Test
    fun `kernel is 1 at center and 0 at the edge`() {
        assertEquals(1f, RopeLayout.kernel(0f, 1.5f))
        assertEquals(0f, RopeLayout.kernel(1.5f, 1.5f))
        assertTrue(RopeLayout.kernel(0.75f, 1.5f) > 0f)
    }
}
