package dev.lumen.app.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The motion-safety contract.
 *
 * These are plain JVM tests on purpose: `:app` Robolectric cannot execute in this
 * project's dev environment, and a safety rule that is only verified by CI the
 * day it is written will rot. `MotionPolicy` / [DurationScale] hold no Android
 * types, so the rules are checked here on every build.
 */
class MotionPolicyTest {

    // ---- the 1 Hz floor ----

    @Test
    fun `a requested period faster than one second is raised to the floor`() {
        // 200 ms was the kind of value that reads as a strobe.
        assertEquals(MotionPolicy.MIN_LOOP_PERIOD_MS, MotionPolicy.safeLoopPeriod(200))
        assertEquals(MotionPolicy.MIN_LOOP_PERIOD_MS, MotionPolicy.safeLoopPeriod(16))
        assertEquals(MotionPolicy.MIN_LOOP_PERIOD_MS, MotionPolicy.safeLoopPeriod(999))
    }

    @Test
    fun `a period at or above the floor is left alone`() {
        assertEquals(1_000, MotionPolicy.safeLoopPeriod(1_000))
        assertEquals(2_000, MotionPolicy.safeLoopPeriod(2_000))
        assertEquals(9_000, MotionPolicy.safeLoopPeriod(9_000))
    }

    @Test
    fun `the floor is exactly one cycle per second`() {
        assertEquals(1_000, MotionPolicy.MIN_LOOP_PERIOD_MS)
    }

    @Test
    fun `the default streaming pulse is slower than the floor`() {
        // Slower than 1 Hz: a breath, not a flash. If someone lowers this to make
        // the UI feel "snappier", this test is the tripwire.
        assertTrue(
            MotionPolicy.STREAMING_PULSE_PERIOD_MS > MotionPolicy.MIN_LOOP_PERIOD_MS,
            "the streaming pulse must be slower than the 1 Hz floor",
        )
    }

    // ---- reduce-motion ----

    @Test
    fun `reduce motion stops the loop entirely`() {
        assertNull(MotionPolicy.streamingPulsePeriodMs(reduceMotion = true))
        assertTrue(!MotionPolicy.loopsAllowed(reduceMotion = true))
    }

    @Test
    fun `reduce motion collapses the pulse to a constant`() {
        // A constant, so nothing changes between frames. Returning a range that
        // still varies would animate even though "animations are off".
        assertEquals(1f to 1f, MotionPolicy.pulseAlphaRange(reduceMotion = true))
    }

    @Test
    fun `ambient off stops the loop without reduce motion`() {
        // Idle, backgrounded and test environments pass ambient=false.
        assertNull(MotionPolicy.streamingPulsePeriodMs(reduceMotion = false, ambient = false))
        assertEquals(1f to 1f, MotionPolicy.pulseAlphaRange(reduceMotion = false, ambient = false))
    }

    @Test
    fun `motion on yields a slow low amplitude breath`() {
        val period = MotionPolicy.streamingPulsePeriodMs(reduceMotion = false)
        assertNotNull(period)
        assertTrue(period >= MotionPolicy.MIN_LOOP_PERIOD_MS, "period $period is faster than the floor")

        val (from, to) = MotionPolicy.pulseAlphaRange(reduceMotion = false)
        // Low amplitude: the dim point must stay clearly visible rather than
        // fading to black, which is what makes a pulse read as a blink.
        assertTrue(from >= 0.5f, "dim point $from is too close to invisible")
        assertTrue(from < to, "the pulse must actually vary when motion is allowed")
        assertTrue(to <= 1f)
    }

    // ---- the platform scale -> reduce-motion rule ----

    @Test
    fun `a zero animator scale means reduce motion`() {
        assertTrue(DurationScale.reduceMotion(animatorScale = 0f, transitionScale = 1f))
    }

    @Test
    fun `a zero transition scale means reduce motion`() {
        // The two settings are independent; either one being off is the user
        // asking for no motion.
        assertTrue(DurationScale.reduceMotion(animatorScale = 1f, transitionScale = 0f))
    }

    @Test
    fun `both scales off means reduce motion`() {
        assertTrue(DurationScale.reduceMotion(0f, 0f))
    }

    @Test
    fun `normal scales keep motion`() {
        assertTrue(!DurationScale.reduceMotion(1f, 1f))
        // Users who slow animations down (0.5x, 2x) still want them.
        assertTrue(!DurationScale.reduceMotion(0.5f, 2f))
        assertTrue(!DurationScale.reduceMotion(10f, 1f))
    }

    @Test
    fun `float noise around zero still counts as off`() {
        assertTrue(DurationScale.reduceMotion(0.0001f, 1f))
    }

    // ---- a guard against the old behaviour creeping back ----

    @Test
    fun `the pulse period a caller cannot bypass`() {
        // The public entry points are the only way to build a pulse spec, and
        // both clamp. Passing a deliberately hostile value must not produce a
        // sub-second loop.
        listOf(1, 16, 33, 100, 500, 999).forEach { hostile ->
            val period = MotionPolicy.safeLoopPeriod(hostile)
            assertTrue(
                period >= MotionPolicy.MIN_LOOP_PERIOD_MS,
                "safeLoopPeriod($hostile) = $period, which is under the 1 Hz floor",
            )
        }
    }
}
