package dev.spindle.core.ui

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The scroll -> focus rule, as a PURE state machine.
 *
 * The timeline never expands while it is being flung: a droplet only blooms when
 * the rope is nearly at rest, and then it "clicks into place" (the caller eases
 * the scroll so the chosen droplet lands on the focus line). This models that
 * with two velocity thresholds and HYSTERESIS, so the mode cannot flicker when
 * the thumb hovers right on a threshold.
 *
 * No Android types, no timers -> unit-testable on a plain JVM, like
 * [TimelineLayout] and [RopeLayout].
 */
object FocusPolicy {

    enum class Mode {
        /** thumb / fling is fast: nothing blooms, droplets just travel. */
        FREE,
        /** slowing down: a target is chosen but the bloom is not latched yet. */
        SETTLING,
        /** at rest: the target droplet is bloomed and pulled onto the focus line. */
        FOCUSED,
    }

    /**
     * @param speedFree    at/above this speed (px/s) the rope is FREE.
     * @param speedRest    at/below this speed the rope may latch FOCUSED.
     * @param releaseRatio leaving FOCUSED needs speed > speedRest * releaseRatio
     *                     (hysteresis).
     */
    data class Config(
        val speedFree: Float = 2200f,
        val speedRest: Float = 240f,
        val releaseRatio: Float = 1.6f,
    )

    data class Result(
        val mode: Mode,
        /** index of the droplet the focus should land on. */
        val targetIndex: Int,
        /** true when the droplet at the focus may be bloomed. */
        val bloom: Boolean,
    ) {
        val settled get() = mode == Mode.FOCUSED
    }

    /**
     * @param prevMode the previous frame's mode; drives the hysteresis.
     * @param velocity current scroll velocity in px/s (sign ignored).
     * @param focusPos fractional droplet index currently under the focus line.
     */
    fun decide(
        prevMode: Mode,
        velocity: Float,
        focusPos: Float,
        count: Int,
        config: Config = Config(),
    ): Result {
        if (count <= 0) return Result(Mode.FREE, 0, bloom = false)

        val target = focusPos.roundToInt().coerceIn(0, count - 1)
        val speed = abs(velocity)

        // Leaving FOCUSED is harder than entering it (hysteresis).
        val restThreshold =
            if (prevMode == Mode.FOCUSED) config.speedRest * config.releaseRatio else config.speedRest

        val mode = when {
            speed >= config.speedFree -> Mode.FREE
            speed > restThreshold -> Mode.SETTLING
            else -> Mode.FOCUSED
        }
        return Result(mode, target, bloom = mode == Mode.FOCUSED)
    }

    /** Scroll offset that puts droplet [index] on the focus line. */
    fun targetScroll(index: Int, stride: Float): Float = index.coerceAtLeast(0) * stride
}
