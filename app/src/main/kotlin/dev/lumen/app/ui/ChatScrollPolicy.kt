package dev.lumen.app.ui

/**
 * When the chat transcript should pin itself to the newest message, and by how
 * much to move.
 *
 * ## The two bugs this exists to stop
 *
 * The old code decided this inside a `snapshotFlow { listState.layoutInfo }`
 * collector and then **called `scrollBy` from inside that same collector**.
 * Scrolling invalidates layout, layout changes `layoutInfo`, and the collector
 * fires again — a feedback loop that never settles. It pegged the main thread
 * (the owner saw Android's "app has stopped working") and it also *teleported*
 * the reader, because every partial layout pass issued another corrective scroll.
 *
 * Both symptoms have one root: the decision and the action were fused into a
 * reactive loop with no notion of "I have already corrected this frame".
 *
 * So the policy is split out here, purely:
 *  - [shouldFollow] is a boolean over measured geometry;
 *  - [correction] returns the scroll delta **or null** when no scroll is needed.
 *
 * A null is the important part. The caller only scrolls when there is something
 * to correct, so a settled list produces no scroll, no relayout and no re-emit.
 * And because this is plain Kotlin with no Compose types, the rules are covered
 * by JVM tests instead of only by CI.
 */
object ChatScrollPolicy {

    /**
     * Whether the reader is at the tail, so new content may pull them along.
     *
     * @param canScrollForward  false when the content already fits.
     * @param totalItems        rows in the list.
     * @param lastVisibleIndex  index of the last composed visible row, or null.
     * @param lastVisibleEnd    its bottom edge in viewport coordinates.
     * @param viewportEnd       viewport bottom, minus any content padding.
     * @param scrolling         true while a user gesture or fling is in flight.
     * @param tolerancePx       how close counts as "at the bottom".
     */
    fun shouldFollow(
        canScrollForward: Boolean,
        totalItems: Int,
        lastVisibleIndex: Int?,
        lastVisibleEnd: Int,
        viewportEnd: Int,
        scrolling: Boolean,
        tolerancePx: Float,
    ): Boolean {
        if (totalItems == 0) return true
        // Nothing to scroll: we are by definition at the tail. This is the ONLY
        // reliable "at the bottom" signal, because it comes from the scroll
        // container rather than from geometry we infer.
        if (!canScrollForward) return true
        // Never fight an in-flight gesture.
        if (scrolling) return false
        val index = lastVisibleIndex ?: return true
        val gap = viewportEnd - lastVisibleEnd
        // Geometry says the last visible row reaches the viewport bottom.
        if (kotlin.math.abs(gap) <= tolerancePx) return true
        // Otherwise fall back to the row-index heuristic.
        //
        // The gap check MUST come first: rows can be taller than the viewport, so
        // a reader who is genuinely at the bottom can have a last-visible index far
        // from `totalItems - 1`. Judging by index alone reported "not at the tail"
        // for anyone reading a long answer, which showed the "latest" cue
        // permanently and made the pin effect refuse to follow. The index test only
        // decides the ambiguous case where the geometry is inconclusive.
        return index >= totalItems - 2
    }

    /**
     * How far to scroll to pin the last row's bottom to the viewport bottom, or
     * **null** when it is already pinned.
     *
     * Returning null instead of 0 is deliberate: a `scrollBy(0f)` still counts as
     * a scroll request, still invalidates layout, and still re-triggers the
     * collector this is meant to quiet.
     *
     * @param lastRowIsComposed false when the final row has not been laid out yet;
     *   the caller must jump to it rather than scroll by a delta it cannot measure.
     */
    fun correction(
        lastRowIsComposed: Boolean,
        lastRowBottom: Int,
        viewportEnd: Int,
    ): Correction {
        if (!lastRowIsComposed) return Correction.JumpToLast
        val gap = viewportEnd - lastRowBottom
        // Already aligned, or there is empty space BELOW the last row. The second
        // case cannot be fixed by scrolling: a short tail sits at the top of the
        // viewport and there is nothing further to scroll to. Treating it as a
        // correction is what made the old code loop — it kept asking to scroll to
        // a position that does not exist.
        if (gap >= -QUIET_THRESHOLD_PX) return Correction.None
        // Only a row whose bottom is BELOW the viewport (gap < 0) needs a
        // forward scroll, and by exactly the amount it overflows.
        return Correction.ScrollBy((-gap).toFloat())
    }

    /** What the caller should do after measuring. */
    sealed interface Correction {
        /** Already aligned: do nothing at all. */
        data object None : Correction

        /** The last row is not laid out yet; jump so it can be measured. */
        data object JumpToLast : Correction

        /** Move by [deltaPx]; positive scrolls forward (towards the tail). */
        data class ScrollBy(val deltaPx: Float) : Correction
    }

    /**
     * Movement under this is treated as already aligned.
     *
     * One physical pixel at typical densities is 2-4px; 1px is deliberately
     * smaller so genuine misalignment is still corrected, while float rounding
     * from `Int` viewport math cannot keep the loop alive.
     */
    const val QUIET_THRESHOLD_PX = 1

    /**
     * How many corrective scrolls may be issued for one settled layout before
     * giving up and leaving the position alone.
     *
     * A hard bound is the real safety net: even if some future measurement
     * disagrees with itself, the UI cannot spin forever. If the transcript really
     * is un-pinnable (e.g. a row taller than the viewport with a nested scroll),
     * the user keeps control instead of the app going unresponsive.
     */
    const val MAX_CORRECTIONS_PER_SETTLE = 3

}
