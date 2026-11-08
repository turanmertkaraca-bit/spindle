package dev.lumen.app.ui

import java.util.Locale

/**
 * The text of the composer's context gauge.
 *
 * Pure on purpose: the composer is a Compose surface verified only by CI, so any
 * formatting rule kept inside it is effectively untested. The rules here are worth
 * pinning because the numbers people act on are easy to get subtly wrong — a
 * negative countdown, an "1k tokens left" on a 1M window, or a percentage that
 * rounds a nearly-full context up to a comfortable-looking number.
 */
object ContextLabel {

    /** Below this many remaining tokens the gauge reads as critical. */
    const val CRITICAL_TOKENS = 8_000

    /** Below this fraction remaining the gauge warns. */
    const val WARN_FRACTION = 0.20

    /**
     * The right-hand label for the composer gauge, e.g. `"12.4k / 200k"`.
     *
     * Shows **used / window** rather than "remaining": with a fixed window, the
     * total is the one number that does not change during a session, so it reads
     * as the limit. The countdown lives in [remaining] for the accessibility
     * description, where it is more useful than a fraction.
     */
    fun describe(usedTokens: Int, window: Int): String {
        if (window <= 0) return "unknown limit"
        val used = usedTokens.coerceIn(0, window)
        return "${tokens(used)} / ${tokens(window)}"
    }

    /** The spoken description, e.g. `"context 94% full, 12.4k of 200k tokens"`. */
    fun accessibility(usedTokens: Int, window: Int): String {
        if (window <= 0) return "context limit unknown"
        val used = usedTokens.coerceIn(0, window)
        val pct = ((used.toDouble() / window.toDouble()) * 100.0).toInt().coerceIn(0, 100)
        return "context $pct% full, ${tokens(used)} of ${tokens(window)} tokens"
    }

    /** The countdown people actually reason with, e.g. `"188k tokens left"`. */
    fun remaining(usedTokens: Int, window: Int): String {
        if (window <= 0) return "unknown"
        val left = (window - usedTokens.coerceIn(0, window)).coerceAtLeast(0)
        return "${tokens(left)} tokens left"
    }

    /** True when the gauge should read as warning/critical. */
    fun isTight(usedTokens: Int, window: Int): Boolean {
        if (window <= 0) return false
        val left = (window - usedTokens.coerceIn(0, window)).coerceAtLeast(0)
        return left <= CRITICAL_TOKENS || left.toDouble() / window.toDouble() <= WARN_FRACTION
    }

    /**
     * Compact token counts: `840`, `12.4k`, `1.05M`.
     *
     * One decimal below 100k and two above, because the useful precision differs
     * by an order of magnitude — `12.4k` is informative, `1.05M` is, and
     * `124,381` is noise in a 9.5sp label.
     */
    fun tokens(count: Int): String {
        val n = count.coerceAtLeast(0)
        return when {
            n < 1_000 -> n.toString()
            // "1k", not "1.0k": a trailing ".0" is just noise in a 9.5sp label.
            n < 100_000 -> String.format(Locale.US, "%.1fk", n / 1_000.0)
                .let { if (it.endsWith(".0k")) it.dropLast(3) + "k" else it }
            n < 1_000_000 -> "${n / 1_000}k"
            // Above 10M a two-decimal "M" is longer than the label can hold
            // (Int.MAX_VALUE would render "2147.48M"), so switch to whole M.
            n < 10_000_000 -> String.format(Locale.US, "%.2fM", n / 1_000_000.0)
            else -> "${n / 1_000_000}M"
        }
    }
}
