package dev.spindle.core.ui

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Positions the droplets along the rope, as a PURE function of scroll.
 *
 * The rope is a one-dimensional arc coordinate. A droplet sits at an arc
 * position; the focus line sits where `arc == scroll`. Spacing is *dynamic*:
 * gaps open up around the focus so the active droplet has room to bloom, and
 * compress away from it, so the rope looks like it is being pulled rather than
 * a rigid list. Everything here is plain math -> plain JVM tests.
 *
 * The caller maps arc -> screen: `y = focusY + arc - scroll`.
 */
object RopeLayout {

    /** One droplet. [bodyHeight] is the measured expanded text height (px). */
    data class Node(
        val key: String,
        val bodyHeight: Float = 0f,
        val merged: Int = 1,
    )

    data class Metrics(
        val nodeSize: Float,
        val baseGap: Float,
        /** extra gap added right at the focus, fading out over [focusHalfWidth]. */
        val focusGapExtra: Float,
        /** how many droplet-slots (indices) the focus gap field reaches. */
        val focusHalfWidth: Float = 1.5f,
        /** where in the viewport the focus line sits (px from the top). */
        val focusY: Float,
        /** px of scroll travel per index; larger = calmer. Defaults to the visual [stride]. */
        val scrollStride: Float = nodeSize + baseGap,
    ) {
        val stride get() = nodeSize + baseGap
    }

    data class Placement(
        val key: String,
        val index: Int,
        /** distance along the rope (px) of this droplet's center. */
        val arc: Float,
        val size: Float,
        /** 0 = collapsed dot, 1 = fully bloomed at the focus. */
        val bloom: Float,
        val merged: Int,
    ) {
        val detached get() = bloom > 0.6f
    }

    data class Result(
        val placements: List<Placement>,
        val focusPos: Float,
        val focusIndex: Int,
        val focusedKey: String?,
        val hasBelow: Boolean,
        /** arc of the last droplet's center at the current scroll (for culling). */
        val contentArc: Float,
    ) {
        fun placement(key: String) = placements.firstOrNull { it.key == key }
    }

    /** Smooth (C1) falloff, 1 at the center and 0 at [halfWidth]. */
    fun kernel(distance: Float, halfWidth: Float): Float {
        if (halfWidth <= 0f) return if (distance == 0f) 1f else 0f
        val x = (abs(distance) / halfWidth).coerceIn(0f, 1f)
        val t = 1f - x
        return t * t * (3f - 2f * t)
    }

    fun maxScroll(nodeCount: Int, metrics: Metrics): Float =
        (nodeCount - 1).coerceAtLeast(0) * metrics.scrollStride

    /**
     * @param scroll arc position currently under the focus line (px).
     */
    fun compute(
        nodes: List<Node>,
        scroll: Float,
        metrics: Metrics,
        bloomHalfWidth: Float = 0.65f,
    ): Result {
        val n = nodes.size
        if (n == 0) return Result(emptyList(), 0f, 0, null, false, 0f)

        val scrollStride = metrics.scrollStride
        if (scrollStride <= 0f) return Result(emptyList(), 0f, 0, null, false, 0f)
        val focusPos = (scroll / metrics.scrollStride).coerceIn(0f, (n - 1).toFloat())

        // dynamic gaps: distance from the focus measured in index space
        val gaps = FloatArray((n - 1).coerceAtLeast(0))
        for (i in gaps.indices) {
            val d = abs((i + 0.5f) - focusPos)
            gaps[i] = metrics.baseGap + metrics.focusGapExtra * kernel(d, metrics.focusHalfWidth)
        }

        val raw = FloatArray(n)
        var y = 0f
        for (i in 0 until n) {
            raw[i] = y
            if (i < n - 1) y += metrics.nodeSize + gaps[i]
        }

        // Express positions relative to the focus, then re-base onto the scroll
        // axis so that `scroll == index * scrollStride` puts droplet `index`
        // exactly on the focus line for ANY focus position. This removes the
        // circular dependency between spacing and scroll.
        val lo = floor(focusPos).toInt().coerceIn(0, n - 1)
        val hi = (lo + 1).coerceAtMost(n - 1)
        val frac = focusPos - lo
        val rawFocus = raw[lo] + frac * (raw[hi] - raw[lo])
        val arcs = FloatArray(n) { i -> raw[i] - rawFocus + focusPos * metrics.scrollStride }

        val focusIndex = focusPos.roundToInt().coerceIn(0, n - 1)
        val placements = ArrayList<Placement>(n)
        for (i in 0 until n) {
            val bloom = kernel(abs(i - focusPos), bloomHalfWidth)
            placements += Placement(
                key = nodes[i].key,
                index = i,
                arc = arcs[i],
                size = metrics.nodeSize,
                bloom = bloom,
                merged = nodes[i].merged,
            )
        }

        return Result(
            placements = placements,
            focusPos = focusPos,
            focusIndex = focusIndex,
            focusedKey = nodes[focusIndex].key,
            hasBelow = focusIndex < n - 1,
            contentArc = arcs[n - 1],
        )
    }

    /** Scroll offset that puts droplet [index] exactly on the focus line. */
    fun scrollForIndex(index: Int, metrics: Metrics): Float =
        index.coerceAtLeast(0) * metrics.scrollStride

    /**
     * Where the scroll should go so the focused droplet is revealed, without
     * teleporting. Null when the target is already the current position.
     */
    fun detent(index: Int, currentScroll: Float, metrics: Metrics): Float? {
        val target = scrollForIndex(index, metrics)
        return if (abs(target - currentScroll) < 0.5f) null else target
    }
}
