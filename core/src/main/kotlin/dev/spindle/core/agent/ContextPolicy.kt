package dev.spindle.core.agent

import dev.spindle.core.model.Usage

/** Per-session token/cost budget. null fields = unlimited. */
data class ContextBudget(
    val maxInputTokens: Int? = null,
    val maxCostUsd: Double? = null,
    /** Fraction of [maxCostUsd] at which a one-shot warning is emitted. */
    val warnAtFraction: Double = 0.8,
) {
    /**
     * User-tunable fraction of the model's context window at which automatic
     * compaction runs; null keeps [Overflow.COMPACT_AT]. Deliberately a body
     * property (not part of the primary constructor) so the data-class shape
     * stays stable for callers.
     */
    var compactAtFraction: Double? = null
}

enum class OverflowAction { NONE, TRIM, COMPACT }

data class OverflowDecision(
    val action: OverflowAction,
    val estimatedTokens: Int,
    val reason: String,
)

/**
 * Very cheap token estimator: ~4 chars/token over wire text plus a fixed
 * per-message overhead. Deliberately conservative — we would rather compact a
 * turn early than blow the provider's window.
 */
object TokenEstimator {
    fun estimate(system: String, messages: List<String>, toolSchemaChars: Int): Int {
        var chars = system.length
        for (m in messages) chars += m.length + 8
        chars += toolSchemaChars
        return chars / 4
    }

    fun total(usages: List<Usage>): Usage =
        usages.fold(Usage()) { acc, u -> acc + u }
}

object Overflow {
    /** Fraction of the window at which we act. */
    const val TRIM_AT = 0.80
    const val COMPACT_AT = 0.92

    fun decide(
        estimatedTokens: Int,
        contextWindow: Int,
        hasOpenToolCall: Boolean,
        compactAt: Double = COMPACT_AT,
        trimAt: Double = TRIM_AT,
    ): OverflowDecision {
        if (hasOpenToolCall) {
            return OverflowDecision(OverflowAction.NONE, estimatedTokens, "open tool call — not touching history")
        }
        val ratio = estimatedTokens.toDouble() / contextWindow.coerceAtLeast(1)
        return when {
            // Re-evaluated every step: a session that has already compacted once
            // must keep compacting/trimming as new turns arrive, or a long run
            // grows past the window again and can never recover.
            ratio >= compactAt ->
                OverflowDecision(OverflowAction.COMPACT, estimatedTokens, "%.0f%% of window".format(ratio * 100))
            ratio >= trimAt ->
                OverflowDecision(OverflowAction.TRIM, estimatedTokens, "%.0f%% of window".format(ratio * 100))
            else -> OverflowDecision(OverflowAction.NONE, estimatedTokens, "ok")
        }
    }
}
