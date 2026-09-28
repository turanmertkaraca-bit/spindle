package dev.spindle.core.ui

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Droplet geometry, as pure math.
 *
 * A step is not a rigid dot: it behaves like a droplet on a rope. It stretches
 * along the rope when the rope moves fast (squash & stretch), grows a downward
 * tail, and reaches a spout to the right when it blooms toward its text. This
 * object only produces points; the Android layer turns them into a Path. Keeping
 * it here means the deformation is unit-testable without a device.
 */
object DropletShape {

    data class Vec2(val x: Float, val y: Float)

    data class Params(
        val radius: Float,
        /** downward teardrop tail length (0 = round). */
        val tail: Float = 0f,
        /** rightward spout length toward the text (0 = none). */
        val spout: Float = 0f,
        /** >1 elongates vertically, <1 flattens: velocity squash & stretch. */
        val stretch: Float = 1f,
        /** radians: leans the droplet with the rope tangent instead of rotating rigidly. */
        val lean: Float = 0f,
        val segments: Int = 40,
    )

    /**
     * Closed outline, sampled counter-clockwise in screen coordinates (y down).
     * The first point is at angle 0 (+x), the last is just before a full turn.
     */
    fun outline(cx: Float, cy: Float, p: Params): List<Vec2> {
        val n = p.segments.coerceAtLeast(8)
        val out = ArrayList<Vec2>(n)
        val s = p.stretch.coerceAtLeast(0.2f)
        val lc = cos(p.lean)
        val ls = sin(p.lean)
        for (i in 0 until n) {
            val t = (i.toFloat() / n) * (2.0 * PI)
            val ux = cos(t).toFloat()
            val uy = sin(t).toFloat()
            var r = p.radius
            if (uy > 0f) r += p.tail * uy * uy
            if (ux > 0f) r += p.spout * ux * ux
            val x0 = r * ux / s
            val y0 = r * uy * s
            // lean: the bead stretches along the rope instead of spinning
            val rx = x0 * lc - y0 * ls
            val ry = x0 * ls + y0 * lc
            out += Vec2(cx + rx, cy + ry)
        }
        return out
    }

    /** Velocity (px/s) -> vertical stretch factor, clamped so it never explodes. */
    fun stretchFor(speed: Float, gain: Float = 0.00035f): Float =
        (1f + kotlin.math.abs(speed) * gain).coerceIn(1f, 2.2f)

    fun bounds(points: List<Vec2>): Pair<Vec2, Vec2> {
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (v in points) {
            if (v.x < minX) minX = v.x
            if (v.y < minY) minY = v.y
            if (v.x > maxX) maxX = v.x
            if (v.y > maxY) maxY = v.y
        }
        return Vec2(minX, minY) to Vec2(maxX, maxY)
    }
}
