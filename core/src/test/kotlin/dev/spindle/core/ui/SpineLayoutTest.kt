package dev.spindle.core.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class SpineLayoutTest {

    @Test
    fun `an empty list focuses index zero`() {
        assertEquals(0, SpineLayout.nearestCenter(emptyList(), 100f))
    }

    @Test
    fun `the node nearest the focus line wins`() {
        val centres = listOf(0 to 20f, 1 to 90f, 2 to 160f)
        assertEquals(1, SpineLayout.nearestCenter(centres, 100f))
        assertEquals(0, SpineLayout.nearestCenter(centres, 0f))
        assertEquals(2, SpineLayout.nearestCenter(centres, 400f))
    }

    @Test
    fun `centerDelta is signed toward the focus line`() {
        assertEquals(30f, SpineLayout.centerDelta(130f, 100f))
        assertEquals(-30f, SpineLayout.centerDelta(70f, 100f))
        assertEquals(0f, SpineLayout.centerDelta(100f, 100f))
    }

    @Test
    fun `growing a node nudges by half its growth`() {
        assertEquals(12f, SpineLayout.growthRecenter(24f))
        assertEquals(-5f, SpineLayout.growthRecenter(-10f))
        assertEquals(0f, SpineLayout.growthRecenter(0f))
    }
}
