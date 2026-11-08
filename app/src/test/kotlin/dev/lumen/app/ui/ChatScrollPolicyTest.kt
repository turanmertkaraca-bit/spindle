package dev.lumen.app.ui

import dev.lumen.app.ui.ChatScrollPolicy.Correction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The transcript's scroll behaviour.
 *
 * Two owner-reported bugs come from this code, and both are covered here:
 *
 *  1. **"it froze, Android said the app stopped working"** — the correction was
 *     computed *and applied* inside a `snapshotFlow { layoutInfo }` collector, so
 *     scrolling invalidated layout, which re-emitted `layoutInfo`, which scrolled
 *     again. An unbounded loop on the main thread.
 *  2. **"it teleports me to the middle of the chat"** — every partial layout pass
 *     issued another corrective scroll, and a positive "gap" (empty space below a
 *     short tail, which cannot be fixed by scrolling) was treated as something to
 *     correct.
 *
 * The fix is that [ChatScrollPolicy.correction] returns [Correction.None] unless
 * there is a real, scrollable misalignment. The tests below pin exactly that,
 * because "returns null often enough to be quiescent" is the property the old
 * code lacked.
 */
class ChatScrollPolicyTest {

    // ---- shouldFollow ----

    @Test
    fun `an empty list is at the tail`() {
        assertEquals(
            true,
            ChatScrollPolicy.shouldFollow(
                canScrollForward = false, totalItems = 0, lastVisibleIndex = null,
                lastVisibleEnd = 0, viewportEnd = 0, scrolling = false, tolerancePx = 4f,
            ),
        )
    }

    @Test
    fun `content that fits is at the tail`() {
        assertEquals(
            true,
            ChatScrollPolicy.shouldFollow(
                canScrollForward = false, totalItems = 3, lastVisibleIndex = 2,
                lastVisibleEnd = 500, viewportEnd = 800, scrolling = false, tolerancePx = 4f,
            ),
        )
    }

    @Test
    fun `scrolling up past the last two rows stops following`() {
        assertEquals(
            false,
            ChatScrollPolicy.shouldFollow(
                canScrollForward = true, totalItems = 50, lastVisibleIndex = 20,
                // Genuinely away from the bottom: the last visible row ends well
                // above the viewport bottom AND the index is far from the end.
                lastVisibleEnd = 400, viewportEnd = 800, scrolling = false, tolerancePx = 4f,
            ),
            "a reader looking at old messages must not be dragged to the bottom",
        )
    }

    /**
     * The bug that made the "latest" cue stick permanently: rows taller than the
     * viewport mean a reader at the bottom can have a last-visible index far from
     * `totalItems - 1`. Judging by index alone said "not at the tail" even though
     * the last row reached the bottom.
     */
    @Test
    fun `a tall row reaching the bottom counts as the tail even with a low index`() {
        assertEquals(
            true,
            ChatScrollPolicy.shouldFollow(
                canScrollForward = true, totalItems = 50, lastVisibleIndex = 40,
                lastVisibleEnd = 800, viewportEnd = 800, scrolling = false, tolerancePx = 4f,
            ),
            "geometry (the row reaches the bottom) must win over the index heuristic",
        )
    }

    @Test
    fun `an in-flight gesture is inconclusive, not "away from the tail"`() {
        // Returning false here would flip a reader who is mid-fling-towards-the-
        // tail into "not following" and stick the latest-cue on screen.
        assertEquals(
            null,
            ChatScrollPolicy.shouldFollow(
                canScrollForward = true, totalItems = 50, lastVisibleIndex = 49,
                lastVisibleEnd = 700, viewportEnd = 800, scrolling = true, tolerancePx = 4f,
            ),
        )
    }

    @Test
    fun `an ambiguous near-end measurement is inconclusive`() {
        // Near the end by index, but the measured gap disagrees. The caller must
        // keep its previous belief rather than being forced to "not following".
        assertEquals(
            null,
            ChatScrollPolicy.shouldFollow(
                canScrollForward = true, totalItems = 50, lastVisibleIndex = 48,
                lastVisibleEnd = 700, viewportEnd = 800, scrolling = false, tolerancePx = 4f,
            ),
        )
    }

    @Test
    fun `being within the tolerance counts as the tail`() {
        assertEquals(
            true,
            ChatScrollPolicy.shouldFollow(
                canScrollForward = true, totalItems = 50, lastVisibleIndex = 49,
                lastVisibleEnd = 797, viewportEnd = 800, scrolling = false, tolerancePx = 4f,
            ),
        )
    }

    // ---- correction: the freeze ----

    /**
     * THE freeze regression. A short tail leaves empty space below the last row.
     * Scrolling cannot fix that, so the answer must be "do nothing" -- the old
     * code returned a delta here and looped forever.
     */
    @Test
    fun `empty space below the last row is not a correction`() {
        val c = ChatScrollPolicy.correction(
            lastRowIsComposed = true,
            lastRowBottom = 400,   // last row ends well above the viewport bottom
            viewportEnd = 800,
        )
        assertEquals(Correction.None, c, "a short tail must not trigger a scroll that can never complete")
    }

    @Test
    fun `an aligned last row is not a correction`() {
        assertEquals(
            Correction.None,
            ChatScrollPolicy.correction(lastRowIsComposed = true, lastRowBottom = 800, viewportEnd = 800),
        )
    }

    @Test
    fun `sub-pixel rounding is not a correction`() {
        // The guard that makes a settled list fully quiescent: without it, layout
        // rounding alone keeps the collector alive.
        assertEquals(
            Correction.None,
            ChatScrollPolicy.correction(lastRowIsComposed = true, lastRowBottom = 799, viewportEnd = 800),
        )
    }

    @Test
    fun `a row overflowing the viewport scrolls forward by the overflow`() {
        val c = ChatScrollPolicy.correction(
            lastRowIsComposed = true,
            lastRowBottom = 950, // 150px past the bottom
            viewportEnd = 800,
        )
        val scroll = assertIs<Correction.ScrollBy>(c)
        assertEquals(150f, scroll.deltaPx, "must scroll FORWARD by exactly the overflow")
        assertTrue(scroll.deltaPx > 0f, "positive is towards the tail")
    }

    @Test
    fun `an uncomposed last row jumps instead of scrolling`() {
        assertEquals(
            Correction.JumpToLast,
            ChatScrollPolicy.correction(lastRowIsComposed = false, lastRowBottom = 0, viewportEnd = 800),
        )
    }

    /**
     * The property the whole fix rests on: a settled transcript is quiescent, so
     * the collector cannot re-trigger itself.
     */
    @Test
    fun `a settled list produces no correction on repeated measurement`() {
        // Measure the same aligned geometry many times; every answer is None, so
        // there is no scroll, no relayout, no re-emit.
        repeat(100) {
            assertEquals(
                Correction.None,
                ChatScrollPolicy.correction(lastRowIsComposed = true, lastRowBottom = 800, viewportEnd = 800),
            )
        }
    }
}
