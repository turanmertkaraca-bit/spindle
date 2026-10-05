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

    /** The slow prism breathe while a run is live. */
    val pulse: InfiniteRepeatableSpec<Float> =
        infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse)
}
