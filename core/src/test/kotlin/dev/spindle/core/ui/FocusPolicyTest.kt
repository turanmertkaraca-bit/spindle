package dev.spindle.core.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The focus rule: fast = never bloom, at rest = click into place, and the
 * transition cannot flicker. All pure, all on the JVM.
 */
class FocusPolicyTest {

    private val cfg = FocusPolicy.Config(speedFree = 2000f, speedRest = 200f, releaseRatio = 1.5f)

    @Test
    fun `free while flinging`() {
        val r = FocusPolicy.decide(FocusPolicy.Mode.FOCUSED, velocity = 3000f, focusPos = 3.2f, count = 10, config = cfg)
        assertEquals(FocusPolicy.Mode.FREE, r.mode)
        assertFalse(r.bloom, "nothing should bloom while flinging")
    }

    @Test
    fun `focused at rest`() {
        val r = FocusPolicy.decide(FocusPolicy.Mode.SETTLING, velocity = 10f, focusPos = 3.2f, count = 10, config = cfg)
        assertEquals(FocusPolicy.Mode.FOCUSED, r.mode)
        assertTrue(r.bloom)
        assertEquals(3, r.targetIndex)
    }

    @Test
    fun `settling between the thresholds`() {
        val r = FocusPolicy.decide(FocusPolicy.Mode.FREE, velocity = 600f, focusPos = 3.6f, count = 10, config = cfg)
        assertEquals(FocusPolicy.Mode.SETTLING, r.mode)
        assertFalse(r.bloom)
        assertEquals(4, r.targetIndex)
    }

    @Test
    fun `hysteresis - leaving focus needs a clearly faster scroll`() {
        // at 250 px/s a SETTLING rope would still be settling...
        val entering = FocusPolicy.decide(FocusPolicy.Mode.SETTLING, 250f, 2f, 10, cfg)
        assertEquals(FocusPolicy.Mode.SETTLING, entering.mode)
        // ...but a FOCUSED rope holds on, because release needs > 200*1.5 = 300
        val holding = FocusPolicy.decide(FocusPolicy.Mode.FOCUSED, 250f, 2f, 10, cfg)
        assertEquals(FocusPolicy.Mode.FOCUSED, holding.mode)
        // and lets go once it is clearly faster
        val releasing = FocusPolicy.decide(FocusPolicy.Mode.FOCUSED, 350f, 2f, 10, cfg)
        assertEquals(FocusPolicy.Mode.SETTLING, releasing.mode)
    }

    @Test
    fun `target is the nearest droplet and never out of range`() {
        assertEquals(0, FocusPolicy.decide(FocusPolicy.Mode.FOCUSED, 0f, -5f, 4, cfg).targetIndex)
        assertEquals(3, FocusPolicy.decide(FocusPolicy.Mode.FOCUSED, 0f, 99f, 4, cfg).targetIndex)
    }

    @Test
    fun `empty timeline is safe`() {
        val r = FocusPolicy.decide(FocusPolicy.Mode.FREE, 500f, 0f, 0, cfg)
        assertFalse(r.bloom)
    }

    @Test
    fun `decisions are deterministic`() {
        val a = FocusPolicy.decide(FocusPolicy.Mode.FREE, 137f, 2.25f, 8, cfg)
        val b = FocusPolicy.decide(FocusPolicy.Mode.FREE, 137f, 2.25f, 8, cfg)
        assertEquals(a, b)
    }

    @Test
    fun `target scroll is a multiple of the stride`() {
        val m = RopeLayout.Metrics(nodeSize = 10f, baseGap = 20f, focusGapExtra = 60f, focusY = 300f)
        assertEquals(0f, FocusPolicy.targetScroll(0, m.stride))
        assertEquals(60f, FocusPolicy.targetScroll(2, m.stride))
    }
}
