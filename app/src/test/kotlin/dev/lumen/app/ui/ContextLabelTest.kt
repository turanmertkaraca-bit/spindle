package dev.lumen.app.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The composer's context-gauge text.
 *
 * Plain JVM: `ContextLabel` holds no Android types, so these run locally as well
 * as in CI. That matters because the composer itself is CI-only, and the numbers
 * a user acts on are easy to get subtly wrong.
 */
class ContextLabelTest {

    @Test
    fun `describe shows used over window`() {
        assertEquals("12.4k / 200k", ContextLabel.describe(12_400, 200_000))
        assertEquals("0 / 1.00M", ContextLabel.describe(0, 1_000_000))
    }

    @Test
    fun `describe never reports more used than the window`() {
        // A provider can report usage above the window after compaction; the
        // gauge must not claim 130% of a hard limit.
        assertEquals("200k / 200k", ContextLabel.describe(260_000, 200_000))
    }

    @Test
    fun `describe handles an unknown window`() {
        assertEquals("unknown limit", ContextLabel.describe(5_000, 0))
    }

    @Test
    fun `accessibility reads as a percentage and a fraction`() {
        val text = ContextLabel.accessibility(94_000, 100_000)
        assertEquals("context 94% full, 94k of 100k tokens", text)
    }

    @Test
    fun `accessibility clamps rather than lying`() {
        assertEquals("context 100% full, 200k of 200k tokens", ContextLabel.accessibility(500_000, 200_000))
        assertEquals("context 0% full, 0 of 200k tokens", ContextLabel.accessibility(-5, 200_000))
    }

    @Test
    fun `remaining counts down and never goes negative`() {
        // 200_000 - 12_400 = 187_600 -> "187k" (whole-k above 100k).
        assertEquals("187k tokens left", ContextLabel.remaining(12_400, 200_000))
        assertEquals("0 tokens left", ContextLabel.remaining(250_000, 200_000))
        assertEquals("unknown", ContextLabel.remaining(1_000, 0))
    }

    @Test
    fun `tight triggers on a small absolute remainder`() {
        // 90% of a 100k window: 10k left is above the 20% fraction but within the
        // absolute guard, because 10k is only a couple of turns.
        assertTrue(ContextLabel.isTight(90_000, 100_000))
    }

    @Test
    fun `tight triggers on a small remaining fraction`() {
        // A 1M window with 150k left is 15% — many turns, but under the fraction.
        assertTrue(ContextLabel.isTight(850_000, 1_000_000))
    }

    @Test
    fun `a comfortable context is not tight`() {
        assertFalse(ContextLabel.isTight(12_400, 200_000))
        assertFalse(ContextLabel.isTight(0, 200_000))
    }

    @Test
    fun `an unknown window is never tight`() {
        // Without a window we cannot know, and a false alarm trains people to
        // ignore the gauge.
        assertFalse(ContextLabel.isTight(999_999, 0))
    }

    @Test
    fun `token formatting is compact at every scale`() {
        assertEquals("0", ContextLabel.tokens(0))
        assertEquals("840", ContextLabel.tokens(840))
        assertEquals("1k", ContextLabel.tokens(1_000))
        assertEquals("12.4k", ContextLabel.tokens(12_400))
        assertEquals("200k", ContextLabel.tokens(200_000))
        assertEquals("1.00M", ContextLabel.tokens(1_000_000))
        assertEquals("1.05M", ContextLabel.tokens(1_050_000))
    }

    @Test
    fun `negative and absurd values do not produce garbage`() {
        assertEquals("0", ContextLabel.tokens(-1))
        // Must stay short enough for a 9.5sp label.
        assertTrue(ContextLabel.tokens(Int.MAX_VALUE).length <= 8, ContextLabel.tokens(Int.MAX_VALUE))
    }
}
