package dev.spindle.core.ui

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RopeWaveTest {

    @Test
    fun `the wave fades to nothing over time`() {
        val atAppend = RopeWave.displacement(30f, 0f, 20f, 120f, 400f, 300f)
        val later = RopeWave.displacement(30f, 2f, 20f, 120f, 400f, 300f)
        assertTrue(abs(atAppend) > abs(later))
        assertTrue(RopeWave.displacement(30f, 100f, 20f, 120f, 400f, 300f).isFinite())
    }

    @Test
    fun `zero amplitude or wavelength is inert`() {
        assertEquals(0f, RopeWave.displacement(10f, 0f, 0f, 120f, 400f, 300f))
        assertEquals(0f, RopeWave.displacement(10f, 0f, 20f, 0f, 400f, 300f))
        assertEquals(0f, RopeWave.displacement(10f, 0f, 20f, 120f, 400f, 0f))
    }

    @Test
    fun `it is finite and periodic in dy at t=0`() {
        val wavelength = 120f
        val here = RopeWave.displacement(5f, 0f, 20f, wavelength, 400f, 1e9f)
        val next = RopeWave.displacement(5f + wavelength, 0f, 20f, wavelength, 400f, 1e9f)
        assertTrue(here.isFinite() && next.isFinite())
        assertEquals(here, next, 0.001f)
    }

    @Test
    fun `envelope is 1 at the append and decays`() {
        assertEquals(1f, RopeWave.envelope(0f))
        assertEquals(1f, RopeWave.envelope(-1f))
        assertTrue(RopeWave.envelope(1f) < 1f)
        assertTrue(RopeWave.envelope(2f) < RopeWave.envelope(1f))
        assertTrue(RopeWave.envelope(1f) > 0f)
    }
}
