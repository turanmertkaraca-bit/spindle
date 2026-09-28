package dev.spindle.core.ui

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Droplet deformation is pure geometry, so its "feel" can be checked: tails go
 * down, spouts go right, motion stretches along the rope, and a still droplet
 * stays symmetric and finite.
 */
class DropletShapeTest {

    private val round = DropletShape.Params(radius = 6f, segments = 48)

    @Test
    fun `a round droplet is symmetric and finite`() {
        val pts = DropletShape.outline(100f, 200f, round)
        assertEquals(48, pts.size)
        assertTrue(pts.all { it.x.isFinite() && it.y.isFinite() })
        val (min, max) = DropletShape.bounds(pts)
        assertEquals(100f, (min.x + max.x) / 2f, 0.01f)
        assertEquals(200f, (min.y + max.y) / 2f, 0.01f)
        assertEquals(6f, max.x - 100f, 0.5f)
    }

    @Test
    fun `a tail pulls the droplet downward`() {
        val plain = DropletShape.bounds(DropletShape.outline(0f, 0f, round)).second.y
        val tailed = DropletShape.bounds(DropletShape.outline(0f, 0f, round.copy(tail = 20f))).second.y
        assertTrue(tailed > plain + 15f, "tail should extend down: $plain -> $tailed")
    }

    @Test
    fun `a spout reaches toward the text on the right`() {
        val plain = DropletShape.bounds(DropletShape.outline(0f, 0f, round)).second.x
        val sup = DropletShape.bounds(DropletShape.outline(0f, 0f, round.copy(spout = 24f))).second.x
        assertTrue(sup > plain + 15f, "spout should reach right: $plain -> $sup")
    }

    @Test
    fun `motion stretches along the rope and thins across it`() {
        assertEquals(1f, DropletShape.stretchFor(0f))
        assertTrue(DropletShape.stretchFor(4000f) > 1.5f)

        val still = DropletShape.bounds(DropletShape.outline(0f, 0f, round))
        val fast = DropletShape.bounds(DropletShape.outline(0f, 0f, round.copy(stretch = 2f)))
        assertTrue((fast.second.y - fast.first.y) > (still.second.y - still.first.y))
        assertTrue((fast.second.x - fast.first.x) < (still.second.x - still.first.x))
    }

    @Test
    fun `leaning turns the tail sideways - the bead curls with the rope`() {
        val up = DropletShape.bounds(DropletShape.outline(0f, 0f, round.copy(tail = 20f))).second.y
        val leaned = DropletShape.bounds(DropletShape.outline(0f, 0f, round.copy(tail = 20f, lean = 1.2f)))
        assertTrue(leaned.second.y < up, "leaning should lift the tail off the vertical")
        assertTrue(leaned.first.x < -6f, "leaning should push the tail sideways")
    }

    @Test
    fun `stretch never explodes`() {
        assertTrue(DropletShape.stretchFor(1_000_000f) <= 2.2f)
        assertTrue(abs(DropletShape.stretchFor(-50f) - 1f) < 0.1f)
    }
}
