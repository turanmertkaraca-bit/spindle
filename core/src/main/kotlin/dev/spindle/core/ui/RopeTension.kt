package dev.spindle.core.ui

import kotlin.math.exp

/**
 * How far the rope is drawn and tugged on each side of the focus, as pure math.
 *
 * The rope reaches further toward the side that holds more droplets, so an
 * unbalanced list visibly leans toward its bulk. Reach saturates: each extra
 * droplet adds less than the one before, so a side never runs off the viewport.
 *
 * With `c = max(count, 0)`, `max = max(viewport * halfFactor, minReach)` and
 * `t = 1 - exp(-c / saturation)`, the reach is
 * `minReach + (max - minReach) * t`.
 */
object RopeTension {

    data class Pull(
        /** how far the rope is drawn above the focus (px). */
        val reachAbove: Float,
        /** how far the rope is drawn below the focus (px). */
        val reachBelow: Float,
        /** signed lateral bias (px) that tugs the rope toward the fuller side. */
        val leanBias: Float,
    )

    /**
     * Saturating reach for a side with [count] droplets.
     *
     * `c = max(count, 0)`, `max = max(viewport * halfFactor, minReach)`, and
     * `t = 1 - exp(-c / saturation)`, giving `minReach` at zero and approaching
     * `max` as the count grows.
     */
    fun reach(
        count: Int,
        viewport: Float,
        minReach: Float,
        halfFactor: Float = 0.62f,
        saturation: Float = 2.2f,
    ): Float {
        val c = count.coerceAtLeast(0)
        val max = (viewport * halfFactor).coerceAtLeast(minReach)
        val t = 1f - exp(-c / saturation)
        return minReach + (max - minReach) * t
    }

    /**
     * The rope's draw on both sides plus a lateral [lean]-scaled bias.
     *
     * `total = max(aboveCount + belowCount, 1)` and
     * `imbalance = belowCount - aboveCount`, so
     * `leanBias = lean * (imbalance / (total + saturation))`. The bias is
     * positive when more droplets sit below (tugging down) and negative when
     * more sit above. Degenerate viewports return [minReach] on both sides with
     * a zero bias.
     */
    fun pull(
        aboveCount: Int,
        belowCount: Int,
        viewport: Float,
        minReach: Float,
        halfFactor: Float = 0.62f,
        saturation: Float = 2.2f,
        lean: Float = 0f,
    ): Pull {
        if (viewport <= 0f) return Pull(minReach, minReach, 0f)
        val reachAbove = reach(aboveCount, viewport, minReach, halfFactor, saturation)
        val reachBelow = reach(belowCount, viewport, minReach, halfFactor, saturation)
        val total = (aboveCount + belowCount).coerceAtLeast(1)
        val imbalance = (belowCount - aboveCount).toFloat()
        val leanBias = lean * (imbalance / (total + saturation))
        return Pull(reachAbove, reachBelow, leanBias)
    }
}
