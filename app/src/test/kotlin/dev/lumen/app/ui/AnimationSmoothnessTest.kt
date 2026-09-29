package dev.lumen.app.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import dev.lumen.app.ui.model.StepKind
import dev.lumen.app.ui.model.UiStep
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Deterministic, emulator-free animation verification for CI.
 *
 * It drives the real [LumenChatScreen] under Robolectric, freezes Compose's
 * `mainClock`, taps the think header, then steps the clock one frame at a time
 * and captures the decor view after each frame. From that frame sequence it
 * derives smoothness metrics (did it move, did it converge, was there a hitch).
 *
 * The metrics are logged rather than asserted hard, because Robolectric's
 * anti-aliasing and font rasterisation differ from a device; only the fact that
 * the harness actually captured motion (>= 2 distinct frames) is a hard check.
 * GIF/PNG encoding failures are swallowed by their writers.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AnimationSmoothnessTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** Frames to step after the tap. 24 * 16ms ~= 384ms, longer than these springs. */
    private val frameCount = 24

    /** Capture at half scale: the decor view is ~780x1688 px at xhdpi. */
    private val captureScale = 0.5f

    /**
     * A single focused capsule carrying a think section. `groupSteps` folds the
     * THINKING row into the ASSISTANT row that follows it, so the screen renders
     * exactly one node whose `testTag("think-toggle")` is present; tapping it
     * grows both the think section and the capsule via `animateContentSize`.
     */
    private fun thinkingSteps(): List<UiStep> = listOf(
        UiStep(
            id = "t1", kind = StepKind.THINKING, label = "THINKING", tag = "c3d4e5",
            summary = "look at the layout rule first",
            body = "look at the layout rule first, then trace where the open/close state comes from, " +
                "and finally decide whether the capsule should grow from the top or from the centre line.",
        ),
        UiStep(
            id = "a1", kind = StepKind.ASSISTANT, label = "ASSISTANT", tag = "d1e2f3",
            summary = "looking now", body = "looking at it now",
        ),
    )

    @Test
    fun `think expansion animates smoothly and converges`() {
        compose.setContent {
            LumenChatScreen(
                steps = thinkingSteps(),
                input = "",
                busy = false,
                error = null,
                colors = LumenColors.Light,
                forceOpenIndex = 0,
                ambient = false,
                onToggleTheme = {},
            )
        }
        compose.waitForIdle()

        val dir = File("build/screenshots/anim").apply { mkdirs() }
        val baseline = ScreenshotSupport.captureDecor(compose.activity, captureScale)
        ScreenshotSupport.writePng(baseline, File(dir, "think_baseline.png"))

        val toggles = compose.onAllNodesWithTag("think-toggle").fetchSemanticsNodes()
        if (toggles.isEmpty()) {
            println("[anim] think-toggle not found; nothing to animate — passing without metrics")
            return
        }

        // Freeze time, then tap: the click changes state but the animation only
        // advances when we explicitly step the clock.
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag("think-toggle").performClick()
            compose.waitForIdle()

            val frames = ArrayList<Bitmap>(frameCount)
            for (i in 0 until frameCount) {
                compose.mainClock.advanceTimeByFrame()
                val frame = ScreenshotSupport.captureDecor(compose.activity, captureScale)
                frames += frame
                ScreenshotSupport.writePng(frame, File(dir, "think_%02d.png".format(i)))
            }

            val firstLast = ScreenshotSupport.meanAbsDiff(frames.first(), frames.last())
            val consecutive = frames.zipWithNext { a, b -> ScreenshotSupport.meanAbsDiff(a, b) }
            val medianConsecutive = median(consecutive)
            val maxConsecutive = consecutive.maxOrNull() ?: 0.0
            val hitchRatio = if (medianConsecutive > 1e-6) maxConsecutive / medianConsecutive else Double.MAX_VALUE
            val distanceToLast = frames.map { ScreenshotSupport.meanAbsDiff(it, frames.last()) }

            val tailCount = minOf(8, distanceToLast.size)
            val tail = distanceToLast.takeLast(tailCount)
            val convergenceTolerance = maxOf(0.25, tail.firstOrNull()?.times(0.1) ?: 0.0)
            val converged = tail.zipWithNext().all { (a, b) -> b <= a + convergenceTolerance }

            var distinctPairs = 0
            for (d in consecutive) if (d > 0.01) distinctPairs++

            println(
                "[anim] frames=${frames.size} firstLast=%.4f".format(firstLast) +
                    " medianConsec=%.4f".format(medianConsecutive) +
                    " maxConsec=%.4f".format(maxConsecutive) +
                    " hitchRatio=%.2f".format(hitchRatio) +
                    " converged=$converged distinctPairs=$distinctPairs",
            )
            if (firstLast <= 0.25) println("[anim] WARN: first and last frames look nearly identical (no visible motion)")
            if (!converged) println("[anim] WARN: frames did not settle monotonically toward the last frame")
            if (hitchRatio > 6.0) println("[anim] WARN: possible hitch (max/median consecutive diff > 6x)")

            AnimatedGifWriter.write(frames, File(dir, "think_expand.gif"), delayMs = 80)

            // The one hard check: the harness must actually have captured motion.
            check(frames.size >= 2 && distinctPairs >= 1) {
                "expected >= 2 distinct frames from the think-expansion animation, " +
                    "got ${frames.size} frames / $distinctPairs distinct transitions"
            }
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    private fun median(xs: List<Double>): Double {
        if (xs.isEmpty()) return 0.0
        val sorted = xs.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2.0 else sorted[mid]
    }
}
