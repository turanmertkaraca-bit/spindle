package dev.lumen.app.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.IntSize

/**
 * One place for every transition so expansion, fades and the running pulse move
 * with the same feel across the whole app. Expansion is a quick no-bounce spring
 * and the fades are a short ease. Deliberately no `animateContentSize`: growing,
 * unbounded text must never be re-measured every frame.
 */
object LumenMotion {
    /**
     * Width/height spring shared by every expanding card and section. Medium
     * stiffness with no overshoot: it reaches the target in a few frames and
     * settles without the little bounce that read as jitter on expansion.
     */
    val expand: FiniteAnimationSpec<IntSize> =
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)

    /** Opacity companion for [expand]; short, so cards never ghost in slowly. */
    val fade: FiniteAnimationSpec<Float> = tween(durationMillis = 120, easing = FastOutSlowInEasing)

    /** The press-in scale on the primary send button — quick, no bounce. */
    val press: FiniteAnimationSpec<Float> =
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessHigh)

    /**
     * The slow prism breathe while a run is live.
     *
     * A **function, not a `val`, on purpose.** Every caller must pass the period
     * it wants, and [MotionPolicy.safeLoopPeriod] raises it to the 1 Hz floor —
     * so no call site can reintroduce a fast strobe by choosing a small number.
     * The old `val` was a bare 1400 ms spec that looped for the entire duration
     * of a run, which is exactly what the owner reported as seizure-adjacent.
     *
     * At 2 s per cycle and a 0.62→1.0 opacity swing this reads as a calm
     * "working" breath rather than a flash.
     */
    fun pulse(periodMs: Int = MotionPolicy.STREAMING_PULSE_PERIOD_MS): InfiniteRepeatableSpec<Float> =
        infiniteRepeatable(
            tween(MotionPolicy.safeLoopPeriod(periodMs), easing = FastOutSlowInEasing),
            RepeatMode.Reverse,
        )
}
