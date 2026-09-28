package dev.spindle.core.ui

import kotlin.math.abs
import kotlin.math.atan

/**
 * The rope's gentle bow around the focus, as pure math.
 *
 * The rope is a straight channel everywhere except a soft bump centred on the
 * focus line, where it leans toward the text. Droplets sit *on* the rope, so
 * they inherit this offset and lean with its slope — that is what stops them
 * looking like rigid discs whenever the rope curves.
 *
 * `dy` is the distance from the focus line in pixels (positive downward).
 */
object RopeCurve {

    /** Horizontal offset of the rope at [dy] from the focus. */
    fun offset(dy: Float, bow: Float, halfWidth: Float): Float {
        if (halfWidth <= 0f) return 0f
        val x = (dy / halfWidth).coerceIn(-1f, 1f)
        val k = 1f - x * x
        return bow * k * k
    }

    /** dx/dy of the rope at [dy]: the lean the droplet should adopt. */
    fun slope(dy: Float, bow: Float, halfWidth: Float): Float {
        if (halfWidth <= 0f) return 0f
        val x = dy / halfWidth
        if (abs(x) >= 1f) return 0f
        val k = 1f - x * x
        return bow * (-4f * x * k) / halfWidth
    }

    /** Lean angle in radians (the actual tangent angle), clamped so it stays sane. */
    fun lean(dy: Float, bow: Float, halfWidth: Float, maxRadians: Float = 0.45f): Float =
        atan(slope(dy, bow, halfWidth)).coerceIn(-maxRadians, maxRadians)
}
