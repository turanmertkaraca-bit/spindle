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
 * with the same feel across the whole app. Expansion is a medium-low spring and
 * the fades are a short ease. Deliberately no `animateContentSize`: growing,
 * unbounded text must never be re-measured every frame.
 */
object LumenMotion {
    /** Width/height spring shared by every expanding card and section. */
    val expand: FiniteAnimationSpec<IntSize> = spring(stiffness = Spring.StiffnessMediumLow)

    /** Opacity companion for [expand]; short, so cards never ghost in slowly. */
    val fade: FiniteAnimationSpec<Float> = tween(durationMillis = 150, easing = FastOutSlowInEasing)

    /** The press-in scale on the primary send button. */
    val press: FiniteAnimationSpec<Float> =
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessHigh)

    /** The slow prism breathe while a run is live. */
    val pulse: InfiniteRepeatableSpec<Float> =
        infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse)
}
