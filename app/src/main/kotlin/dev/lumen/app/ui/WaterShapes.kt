package dev.lumen.app.ui

import androidx.compose.foundation.shape.GenericShape
import androidx.compose.ui.graphics.Shape
import dev.spindle.core.ui.DropletShape

/**
 * The water shapes. Everything in Lumen is a drop of water, so the node marks,
 * the capsule and the brand seed all share one silhouette language instead of
 * borrowing generic circles and rounded rectangles.
 *
 * All of it is pure geometry driven by [DropletShape] (a JVM object with its own
 * tests), so the outline math is verifiable without a device.
 */
object WaterShapes {

    /**
     * A teardrop: a round body with a tapered tail pointing down. [tail] is the
     * tail length as a fraction of the radius, so 0 is a plain circle.
     */
    fun droplet(tail: Float = 0.45f): Shape = GenericShape { size, _ ->
        val cx = size.width / 2f
        val cy = size.height / 2f
        val radius = minOf(size.width, size.height) / 2f
        val points = DropletShape.outline(
            cx = cx,
            cy = cy,
            p = DropletShape.Params(
                radius = radius,
                tail = radius * tail,
                segments = 48,
            ),
        )
        moveTo(points[0].x, points[0].y)
        for (i in 1 until points.size) lineTo(points[i].x, points[i].y)
        close()
    }

    /**
     * The focused "water body": a rounded capsule that is wider at the top and
     * tapers to a softer point at the bottom, like a hanging drop. Built from an
     * oval top and a smooth quadratic bottom so it always stays inside bounds.
     */
    fun drop(taper: Float = 0.18f): Shape = GenericShape { size, _ ->
        val w = size.width
        val h = size.height
        val r = minOf(w, h) * 0.5f
        val dip = (h * taper.coerceIn(0f, 0.35f)).coerceAtMost(r * 0.8f)
        val waist = h - dip
        moveTo(0f, r)
        quadraticBezierTo(0f, 0f, r, 0f)
        lineTo(w - r, 0f)
        quadraticBezierTo(w, 0f, w, r)
        lineTo(w, waist - r)
        quadraticBezierTo(w, waist, w - r, waist)
        // Bottom edge bows downward to a soft centre point.
        quadraticBezierTo(w / 2f, h + dip, r, waist)
        quadraticBezierTo(0f, waist, 0f, waist - r)
        close()
    }
}
