package dev.spindle.core.ui

import kotlin.math.abs

/**
 * Pure geometry for the centred-spine timeline.
 *
 * The timeline is a vertical column of nodes on a spine down the middle of the
 * screen. Whichever node sits nearest the focus line (the vertical centre) is
 * the focused one and expands into its text; everything else collapses to a
 * dot. This object only holds the arithmetic, so it is unit-tested on a JVM.
 *
 * @see dev.spindle.core.ui.RopeLayout the predecessor this replaced.
 */
object SpineLayout {

    /**
     * Index of the node whose centre is nearest [viewportCenter].
     * [centers] is (index, centre-px-on-screen). Returns 0 for an empty list.
     */
    fun nearestCenter(centers: List<Pair<Int, Float>>, viewportCenter: Float): Int {
        if (centers.isEmpty()) return 0
        var best = centers.first()
        var bestDistance = Float.MAX_VALUE
        for (c in centers) {
            val d = abs(c.second - viewportCenter)
            if (d < bestDistance) {
                bestDistance = d
                best = c
            }
        }
        return best.first
    }

    /**
     * Scroll delta that brings an item whose centre currently sits at
     * [itemCenter] onto the [viewportCenter]. Positive means scroll forward
     * (content moves up).
     */
    fun centerDelta(itemCenter: Float, viewportCenter: Float): Float = itemCenter - viewportCenter

    /**
     * The scroll nudge that keeps a node centred while its height changes by
     * [sizeDelta] (growing text). Half the growth, because the node grows away
     * from its centre in both directions.
     */
    fun growthRecenter(sizeDelta: Float): Float = sizeDelta / 2f
}
