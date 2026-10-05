package dev.spindle.core.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OverflowTest {

    private fun decide(
        tokens: Int,
        window: Int = 1_000,
        hasOpenToolCall: Boolean = false,
    ) = Overflow.decide(tokens, window, hasOpenToolCall)

    @Test
    fun `below the trim threshold is a no-op`() {
        assertEquals(OverflowAction.NONE, decide(0).action)
        assertEquals(OverflowAction.NONE, decide(700).action)
        assertEquals(OverflowAction.NONE, decide(799).action)
    }

    @Test
    fun `trim band between eighty and ninety-two percent`() {
        assertEquals(OverflowAction.TRIM, decide(800).action)
        assertEquals(OverflowAction.TRIM, decide(850).action)
        assertEquals(OverflowAction.TRIM, decide(919).action)
    }

    @Test
    fun `above the compact threshold compacts`() {
        assertEquals(OverflowAction.COMPACT, decide(920).action)
        assertEquals(OverflowAction.COMPACT, decide(1_000).action)
        assertEquals(OverflowAction.COMPACT, decide(2_500).action)
    }

    @Test
    fun `an open tool call always yields none`() {
        assertEquals(OverflowAction.NONE, decide(700, hasOpenToolCall = true).action)
        assertEquals(OverflowAction.NONE, decide(850, hasOpenToolCall = true).action)
        assertEquals(OverflowAction.NONE, decide(1_000, hasOpenToolCall = true).action)
        assertEquals(OverflowAction.NONE, decide(5_000, hasOpenToolCall = true).action)
    }

    @Test
    fun `compaction stays available after a first compaction`() {
        // Regression: the old gate disabled trim/compact forever once a session
        // held one summary, so a long run could never shrink again and would
        // eventually exceed the window. Overflow is now purely ratio-driven.
        assertEquals(OverflowAction.TRIM, decide(800).action)
        assertEquals(OverflowAction.TRIM, decide(919).action)
        assertEquals(OverflowAction.COMPACT, decide(920).action)
        assertEquals(OverflowAction.COMPACT, decide(5_000).action)
    }

    @Test
    fun `token estimator scales with input size`() {
        assertEquals(0, TokenEstimator.estimate("", emptyList(), 0))

        val systemOnly = TokenEstimator.estimate("a".repeat(400), emptyList(), 0)
        assertEquals(100, systemOnly)

        val largerSystem = TokenEstimator.estimate("a".repeat(4_000), emptyList(), 0)
        assertEquals(1_000, largerSystem)
        assertTrue(largerSystem > systemOnly)

        val oneMessage = TokenEstimator.estimate("", listOf("b".repeat(4_000)), 0)
        // 4000 chars + 8 per-message overhead, then /4
        assertEquals(1_002, oneMessage)
        assertTrue(oneMessage > systemOnly)

        val withSchema = TokenEstimator.estimate("", emptyList(), 4_000)
        assertEquals(1_000, withSchema)
        assertTrue(withSchema > systemOnly)

        val moreMessages = TokenEstimator.estimate("", listOf("b".repeat(400), "b".repeat(400)), 0)
        val fewerMessages = TokenEstimator.estimate("", listOf("b".repeat(400)), 0)
        assertTrue(moreMessages > fewerMessages)
    }
}
