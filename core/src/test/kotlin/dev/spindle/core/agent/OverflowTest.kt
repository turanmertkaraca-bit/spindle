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

/**
 * Wave-1 bug hunt: an unknown or tiny model window.
 *
 * `OpenAiProvider` builds a catalogue entry with `contextWindow = 0` for any
 * model the endpoint does not describe, and `chat/completions` endpoints
 * routinely omit `context_length`. `decide` divides by
 * `contextWindow.coerceAtLeast(1)`, so a 0 window made every estimate look
 * infinitely over budget and the loop compacted on EVERY step — a summarization
 * provider call per step. These tests pin the substitution.
 */
class UnknownWindowTest {

    @Test
    fun `a zero window does not make every estimate overflow`() {
        val decision = Overflow.decide(
            estimatedTokens = 5_000,
            contextWindow = 0,
            hasOpenToolCall = false,
        )
        assertEquals(
            OverflowAction.NONE,
            decision.action,
            "a 5k-token history against an unknown window must not compact; " +
                "before the fix this divided by 1 and compacted on every step",
        )
    }

    @Test
    fun `a zero window still compacts when genuinely huge`() {
        val decision = Overflow.decide(
            estimatedTokens = Overflow.UNKNOWN_WINDOW * 2,
            contextWindow = 0,
            hasOpenToolCall = false,
        )
        assertEquals(OverflowAction.COMPACT, decision.action)
    }

    @Test
    fun `effectiveWindow substitutes a conservative window for an unknown model`() {
        assertEquals(Overflow.UNKNOWN_WINDOW, Overflow.effectiveWindow(0, 0))
        assertEquals(Overflow.UNKNOWN_WINDOW, Overflow.effectiveWindow(-1, 0))
    }

    @Test
    fun `effectiveWindow holds back room for the response`() {
        // A known window reserves the declared output budget, capped by
        // MAX_RESERVED_OUTPUT and by half the window.
        assertEquals(128_000 - 8_000, Overflow.effectiveWindow(128_000, 8_000))
        // A huge declared output is capped at MAX_RESERVED_OUTPUT.
        assertEquals(128_000 - Overflow.MAX_RESERVED_OUTPUT, Overflow.effectiveWindow(128_000, 100_000))
        // A small declared max output reserves only that much.
        assertEquals(128_000 - 2_000, Overflow.effectiveWindow(128_000, 2_000))
    }

    @Test
    fun `effectiveWindow never reserves more than half a small window`() {
        // A model that declares a huge max output against a tiny window must not
        // reserve the whole window and leave an unusable one-token budget.
        val effective = Overflow.effectiveWindow(2_000, 100_000)
        assertTrue(effective >= 1_000, "expected at least half the window, got $effective")
    }

    @Test
    fun `effectiveWindow is never below one`() {
        assertTrue(Overflow.effectiveWindow(1, 1) >= 1)
        assertTrue(Overflow.effectiveWindow(1, 100_000) >= 1)
    }
}

/**
 * Wave-1 bug hunt: the retained tail was never clamped to the window.
 *
 * `Compaction.compact` keeps `compactKeepTokens` (15k by default) verbatim and
 * refuses to fold anything when `messages.size <= keepCount`. On a 16k-window
 * model the usable input budget is smaller than that tail, so compaction found
 * nothing foldable forever, and `trim` could not help because it never touches
 * the retained tail. The request stayed over the window until the bounded
 * overflow retries ran out and the turn was written as an error.
 */
class KeepTokensClampTest {

    @Test
    fun `the retained tail never exceeds half of a small window`() {
        val window = Overflow.effectiveWindow(16_000, 4_000)
        val keep = Overflow.keepTokens(ContextBudget.DEFAULT_COMPACT_KEEP_TOKENS, window)
        assertTrue(
            keep <= window / 2,
            "a 15k tail in a $window-token window can never be folded away; got $keep",
        )
    }

    @Test
    fun `a generous window keeps the requested tail`() {
        val window = Overflow.effectiveWindow(1_000_000, 8_000)
        assertEquals(
            ContextBudget.DEFAULT_COMPACT_KEEP_TOKENS,
            Overflow.keepTokens(ContextBudget.DEFAULT_COMPACT_KEEP_TOKENS, window),
        )
    }

    @Test
    fun `the tail always leaves a foldable head`() {
        // Across every plausible window, the clamp must leave room to fold, or
        // compaction refuses forever and the run dies over the window.
        for (window in listOf(2_000, 4_000, 8_000, 16_000, 32_000, 128_000, 1_000_000)) {
            val effective = Overflow.effectiveWindow(window, 4_000)
            val keep = Overflow.keepTokens(ContextBudget.DEFAULT_COMPACT_KEEP_TOKENS, effective)
            assertTrue(keep < effective, "window=$window effective=$effective keep=$keep")
            assertTrue(keep >= 1, "window=$window keep=$keep must stay positive")
        }
    }
}

/**
 * Wave-1 bug hunt: a fold decision that changes nothing was retried forever.
 *
 * Every refusal inside `Compaction` is legitimate, but the loop could not tell
 * "declined, nothing new to fold" from "cannot shrink at all", so it recomputed
 * the same decision on the next step and tried the same fold. `nextAction` makes
 * the give-up point explicit and testable instead of implicit in retry counts.
 */
class FoldStallTest {

    @Test
    fun `a fold that shrank history resets the stall count`() {
        val next = Overflow.nextAction(OverflowAction.COMPACT, changed = true, stalled = 1)
        assertEquals(0, next.stalled)
        assertEquals(OverflowAction.NONE, next.action)
        assertTrue(!next.exhausted)
    }

    @Test
    fun `one no-op fold is tolerated because a fresh summary refuses by design`() {
        val next = Overflow.nextAction(OverflowAction.COMPACT, changed = false, stalled = 0)
        assertEquals(1, next.stalled)
        assertTrue(!next.exhausted)
        // Still worth trying the next fold: the tail may have grown since.
        assertEquals(OverflowAction.COMPACT, next.action)
    }

    @Test
    fun `two consecutive no-op folds are reported as exhausted`() {
        val next = Overflow.nextAction(OverflowAction.TRIM, changed = false, stalled = 1)
        assertTrue(next.exhausted, "the loop must stop retrying a fold that cannot shrink anything")
        assertEquals(OverflowAction.NONE, next.action)
    }

    @Test
    fun `no decision means no folding and no stall`() {
        val next = Overflow.nextAction(OverflowAction.NONE, changed = false, stalled = 5)
        assertEquals(OverflowAction.NONE, next.action)
        assertEquals(5, next.stalled)
    }
}
