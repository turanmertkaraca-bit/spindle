package dev.lumen.app.ui

/**
 * Frame-sequence metrics for the animation harness.
 *
 * ## Why this is its own type
 *
 * The metrics used to be computed inline in `AnimationSmoothnessTest`, and the
 * result was unusable in the one artefact a human actually reads. `metrics.txt`
 * from CI contained:
 *
 * ```
 * medianConsecutive=0.0000
 * maxConsecutive=2.7651
 * hitchRatio=1797693134862315700000000000000000000000000...000.00
 * ```
 *
 * That is `Double.MAX_VALUE` formatted with `%.2f`, i.e. a 309-character number.
 * The cause: `hitchRatio` fell back to `Double.MAX_VALUE` when the median was ~0,
 * which is exactly the case after a spring settles — and it then tripped the
 * `> 6.0` "possible hitch" warning on a perfectly smooth animation. A metric that
 * reports an infinite hitch on a healthy run teaches people to ignore the metric.
 *
 * Pulling the maths out of the test also makes it checkable on a plain JVM: the
 * `:app` Robolectric harness needs a native graphics stack that is not available
 * in this project's dev sandbox, so anything left inline there is only verified by
 * CI on the day it is written.
 */
data class MotionMetrics(
    val frames: Int,
    /** Mean absolute difference between the first and last frame. 0 = no motion. */
    val firstLast: Double,
    /** Median of consecutive-frame differences. */
    val medianConsecutive: Double,
    /** Largest consecutive-frame difference. */
    val maxConsecutive: Double,
    /**
     * `maxConsecutive / medianConsecutive`, or **null** when the median is
     * negligible and the ratio is therefore meaningless.
     *
     * Null rather than a huge sentinel: the caller must decide what "no ratio" is
     * worth, and a sentinel inevitably gets printed, compared and misread.
     */
    val hitchRatio: Double?,
    /** True when the tail of the sequence settles towards the final frame. */
    val converged: Boolean,
    /** Consecutive pairs that differ by more than [DISTINCT_EPSILON]. */
    val distinctPairs: Int,
) {
    fun summary(): String = buildString {
        append("frames=$frames firstLast=%.4f".format(firstLast))
        append(" medianConsec=%.4f".format(medianConsecutive))
        append(" maxConsec=%.4f".format(maxConsecutive))
        append(" hitchRatio=")
        append(hitchRatio?.let { "%.2f".format(it) } ?: "n/a")
        append(" converged=$converged distinctPairs=$distinctPairs")
    }

    fun report(): String = buildString {
        appendLine("frames=$frames")
        appendLine("firstLast=%.4f".format(firstLast))
        appendLine("medianConsecutive=%.4f".format(medianConsecutive))
        appendLine("maxConsecutive=%.4f".format(maxConsecutive))
        appendLine("hitchRatio=" + (hitchRatio?.let { "%.2f".format(it) } ?: "n/a (median ~ 0)"))
        appendLine("converged=$converged")
        appendLine("distinctPairs=$distinctPairs")
    }

    companion object {
        /** Below this, two frames are visually identical for metric purposes. */
        const val DISTINCT_EPSILON = 0.01

        /** Median consecutive difference under this makes [hitchRatio] meaningless. */
        const val RATIO_MEDIAN_FLOOR = 1e-6

        /** How many trailing frames must look settled. */
        const val CONVERGENCE_TAIL = 8

        /**
         * [diff] returns the mean absolute difference between frame `a` and `b`.
         */
        fun from(frameCount: Int, diff: (Int, Int) -> Double): MotionMetrics {
            if (frameCount <= 1) {
                return MotionMetrics(
                    frames = frameCount,
                    firstLast = 0.0,
                    medianConsecutive = 0.0,
                    maxConsecutive = 0.0,
                    hitchRatio = null,
                    converged = true,
                    distinctPairs = 0,
                )
            }
            val consecutive = (1 until frameCount).map { diff(it - 1, it) }
            val median = median(consecutive)
            val max = consecutive.maxOrNull() ?: 0.0
            val ratio = if (median > RATIO_MEDIAN_FLOOR) max / median else null

            // Distance from every frame to the final one: a settling animation
            // moves monotonically towards its end state.
            val last = frameCount - 1
            val toLast = (0 until frameCount).map { diff(it, last) }
            val tail = toLast.takeLast(minOf(CONVERGENCE_TAIL, toLast.size))
            val tolerance = maxOf(0.25, (tail.firstOrNull() ?: 0.0) * 0.1)
            val converged = tail.zipWithNext().all { (a, b) -> b <= a + tolerance }

            return MotionMetrics(
                frames = frameCount,
                firstLast = diff(0, last),
                medianConsecutive = median,
                maxConsecutive = max,
                hitchRatio = ratio,
                converged = converged,
                distinctPairs = consecutive.count { it > DISTINCT_EPSILON },
            )
        }

        private fun median(xs: List<Double>): Double {
            if (xs.isEmpty()) return 0.0
            val sorted = xs.sorted()
            val mid = sorted.size / 2
            return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2.0 else sorted[mid]
        }
    }
}
