package dev.spindle.core.agent

import dev.spindle.core.model.Usage

/** Per-session token/cost budget. null fields = unlimited. */
data class ContextBudget(
    val maxInputTokens: Int? = null,
    val maxCostUsd: Double? = null,
)

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
        alreadyCompacted: Boolean,
    ): OverflowDecision {
        if (hasOpenToolCall) {
            return OverflowDecision(OverflowAction.NONE, estimatedTokens, "open tool call — not touching history")
        }
        val ratio = estimatedTokens.toDouble() / contextWindow.coerceAtLeast(1)
        return when {
            ratio >= COMPACT_AT && !alreadyCompacted ->
                OverflowDecision(OverflowAction.COMPACT, estimatedTokens, "%.0f%% of window".format(ratio * 100))
            ratio >= TRIM_AT && !alreadyCompacted ->
                OverflowDecision(OverflowAction.TRIM, estimatedTokens, "%.0f%% of window".format(ratio * 100))
            else -> OverflowDecision(OverflowAction.NONE, estimatedTokens, "ok")
        }
    }
}
