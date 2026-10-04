package dev.lumen.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A burst of rebuild requests collapses to at most the in-flight pass plus one
 * follow-up, instead of stacking a full session re-read per event.
 */
class RebuildCoalescerTest {

    @Test
    fun `the first request claims the slot, later ones only mark it dirty`() {
        val c = RebuildCoalescer()
        assertTrue(c.request(), "first request starts a pass")
        assertTrue(c.inFlight)
        assertFalse(c.request(), "a request during a pass does not start another")
        assertFalse(c.request(), "further requests fold into the same dirty flag")
        assertTrue(c.dirty)
    }

    @Test
    fun `a dirty pass runs exactly once more`() {
        val c = RebuildCoalescer()
        assertTrue(c.request())
        assertFalse(c.request())
        assertTrue(c.finish(), "the dirty flag demands one more pass")
        assertTrue(c.inFlight)
        assertFalse(c.finish(), "with nothing dirty the loop stops")
        assertFalse(c.inFlight)
    }

    @Test
    fun `a clean pass finishes immediately and the slot frees up`() {
        val c = RebuildCoalescer()
        assertTrue(c.request())
        assertFalse(c.finish())
        assertFalse(c.inFlight)
        assertTrue(c.request(), "a fresh request after idle starts a new pass")
    }

    @Test
    fun `reset abandons an in-flight pass`() {
        val c = RebuildCoalescer()
        assertTrue(c.request())
        c.reset()
        assertFalse(c.inFlight)
        assertFalse(c.dirty)
        assertTrue(c.request())
    }
}
