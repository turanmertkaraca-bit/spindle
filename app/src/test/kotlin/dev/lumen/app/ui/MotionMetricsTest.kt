package dev.lumen.app.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Plain-JVM tests for the frame metrics.
 *
 * `MotionMetrics.from(frameCount, diff)` takes the difference function as a
 * parameter precisely so these can be written without a `Bitmap`, a graphics
 * stack or Robolectric. The `:app` animation harness itself needs native
 * rasterisation that this project's dev sandbox cannot provide, so anything that
 * can be checked here *should* be.
 */
class MotionMetricsTest {

    /** A frame sequence whose distance from frame 0 is the given list of values. */
    private fun fromOffsets(offsets: List<Double>): MotionMetrics =
        MotionMetrics.from(offsets.size) { a, b -> kotlin.math.abs(offsets[a] - offsets[b]) }

    @Test
    fun `a sequence that settles reports converged and a finite ratio`() {
        // 0, 10, 18, 24, 28, 30, 31, 31.5 ... approaching the final value.
        val offsets = listOf(0.0, 10.0, 18.0, 24.0, 28.0, 30.0, 31.0, 31.5, 31.8, 31.9, 32.0, 32.0)
        val m = fromOffsets(offsets)
        assertTrue(m.converged, "a settling sequence must report converged: ${m.report()}")
        assertNotNull(m.hitchRatio)
        assertEquals(12, m.frames)
        assertTrue(m.distinctPairs > 0)
    }

    /**
     * The CI regression this type exists for.
     *
     * After a spring settles every consecutive frame is identical, so the median
     * consecutive difference is 0. The old inline code fell back to
     * `Double.MAX_VALUE` for the ratio, printed it with `%.2f` (a 309-character
     * number) and then tripped its own "possible hitch" warning. The ratio must
     * be absent, not infinite.
     */
    @Test
    fun `a fully settled sequence reports no ratio instead of an infinite one`() {
        val offsets = List(24) { 5.0 }
        val m = fromOffsets(offsets)
        assertNull(m.hitchRatio, "a motionless tail has no meaningful hitch ratio")
        assertEquals(0.0, m.medianConsecutive)
        assertEquals(0, m.distinctPairs)
        assertTrue(m.converged, "a motionless sequence is trivially settled")
        // The report must stay short and readable, not a wall of digits.
        assertTrue(m.report().length < 200, "report is ${m.report().length} chars:\n${m.report()}")
        assertTrue(m.report().contains("n/a"), m.report())
    }

    @Test
    fun `a hitch is detected when one frame jumps much more than the rest`() {
        // Uniform 1.0 steps, except one 20.0 jump.
        val offsets = listOf(0.0, 1.0, 2.0, 3.0, 4.0, 24.0, 25.0, 26.0, 27.0, 28.0)
        val m = fromOffsets(offsets)
        val ratio = assertNotNull(m.hitchRatio)
        assertTrue(ratio > 6.0, "a 20x single-frame jump should read as a hitch, got $ratio")
    }

    @Test
    fun `a dense monotonic run does not look like a hitch`() {
        val offsets = (0..23).map { it.toDouble() }
        val m = fromOffsets(offsets)
        val ratio = assertNotNull(m.hitchRatio)
        assertTrue(ratio < 2.0, "an even run should have a ratio near 1, got $ratio")
    }

    @Test
    fun `a sequence that never settles is not converged`() {
        // A spring that keeps oscillating with constant amplitude. Distances to
        // the final frame do NOT shrink towards it, so this must not be reported
        // as settled. (A quadratic ramp would not work here: its distances still
        // decrease relatively, so relative convergence genuinely holds.)
        val offsets = (0 until 24).map { if (it % 2 == 0) 0.0 else 100.0 }
        val m = fromOffsets(offsets)
        assertTrue(!m.converged, "a constant-amplitude oscillation must not report converged: ${m.report()}")
    }

    @Test
    fun `a single frame is handled without dividing by zero`() {
        val m = fromOffsets(listOf(1.0))
        assertEquals(1, m.frames)
        assertNull(m.hitchRatio)
        assertEquals(0, m.distinctPairs)
        assertTrue(m.converged)
    }

    @Test
    fun `an empty sequence is handled`() {
        val m = fromOffsets(emptyList())
        assertEquals(0, m.frames)
        assertNull(m.hitchRatio)
        assertEquals(0, m.distinctPairs)
    }

    @Test
    fun `the summary line stays a single readable line`() {
        val m = fromOffsets(listOf(0.0, 5.0, 9.0, 12.0, 14.0, 15.0, 15.5, 15.7, 15.8, 15.9, 16.0, 16.0))
        val line = m.summary()
        assertTrue(!line.contains("\n"), "summary must be one line")
        assertTrue(line.length < 160, "summary is ${line.length} chars: $line")
        assertTrue(line.startsWith("frames="), line)
    }
}
