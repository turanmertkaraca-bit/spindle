package dev.spindle.core.agent

import dev.spindle.core.model.Usage

/** Per-session token/cost budget. null fields = unlimited. */
data class ContextBudget(
    val maxInputTokens: Int? = null,
    val maxCostUsd: Double? = null,
    /** Fraction of [maxCostUsd] at which a one-shot warning is emitted. */
    val warnAtFraction: Double = 0.8,
    /**
     * Tokens of the most recent history [Compaction] keeps verbatim when it
     * folds the head into a summary. A fixed message count is too coarse when
     * turns mix one-line prompts with huge tool output; opencode v2 retains
     * roughly this many recent tokens. [Compaction.MIN_KEEP_RECENT] messages
     * are always kept regardless. Defaulted so existing callers compile
     * unchanged.
     */
    val compactKeepTokens: Int = DEFAULT_COMPACT_KEEP_TOKENS,
) {
    /**
     * User-tunable fraction of the model's context window at which automatic
     * compaction runs; null keeps [Overflow.COMPACT_AT]. Deliberately a body
     * property (not part of the primary constructor) so the data-class shape
     * stays stable for callers.
     */
    var compactAtFraction: Double? = null

    companion object {
        /** opencode v2 keeps ~15k recent tokens verbatim after a compaction. */
        const val DEFAULT_COMPACT_KEEP_TOKENS = 15_000
    }
}

enum class OverflowAction { NONE, TRIM, COMPACT }

data class OverflowDecision(
    val action: OverflowAction,
    val estimatedTokens: Int,
    val reason: String,
    /**
     * The usable input budget this decision was made against, after substituting
     * a conservative window for an unknown model and holding back room for the
     * response. Defaulted so existing constructions and tests compile unchanged.
     */
    val effectiveWindow: Int = 0,
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

    /**
     * Same arithmetic as [estimate] but from pre-computed character counts, so
     * callers never allocate throwaway strings just to measure them.
     */
    fun estimateFromCharCounts(systemChars: Int, messageChars: List<Int>, toolSchemaChars: Int): Int {
        var chars = systemChars
        for (m in messageChars) chars += m + 8
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

    /**
     * Window assumed when the model does not advertise one.
     *
     * A model the live catalogue does not recognise is built with
     * `contextWindow = 0` (see `OpenAiProvider`'s `enrichModel` fallback), and
     * `chat/completions` endpoints routinely omit `context_length`. Before this
     * constant, a 0 window was divided by `coerceAtLeast(1)`, so the estimate
     * looked infinitely over the window and the loop decided COMPACT on **every
     * step** — a summarization provider call per step, roughly doubling cost and
     * destroying history continuously. 128k is the smallest window among the
     * models this app ships, so assuming it is conservative without being absurd.
     */
    const val UNKNOWN_WINDOW = 128_000

    /** Room held back for the response we are about to ask for. */
    const val MAX_RESERVED_OUTPUT = 20_000

    /** Smallest retained-tail budget worth honouring, in tokens. */
    const val MIN_KEEP_TOKENS = 1_000

    /**
     * Usable input budget for a model's [contextWindow], never below 1.
     *
     * Substitutes [UNKNOWN_WINDOW] for a missing (<= 0) window and
     * [MAX_RESERVED_OUTPUT] for a missing output reservation, so callers get a
     * real number without each of them repeating the guard.
     */
    fun effectiveWindow(contextWindow: Int, maxOutputTokens: Int): Int {
        val window = if (contextWindow > 0) contextWindow else UNKNOWN_WINDOW
        // Never reserve more than half the window: a model that declares a huge
        // max output against a small window would otherwise reserve everything and
        // leave an effective budget of 1 token, which no history can fit.
        val reserve = minOf(MAX_RESERVED_OUTPUT, if (maxOutputTokens > 0) maxOutputTokens else 0, window / 2)
        return (window - reserve).coerceAtLeast(1)
    }

    /**
     * Recent tokens [Compaction] may keep verbatim, clamped to half of
     * [effectiveWindow].
     *
     * The unclamped default is 15k, which is *larger* than the usable window of a
     * 16k model. Compaction then finds nothing foldable (`messages.size <=
     * keepCount`), the fallback trim cannot help because it never touches the
     * retained tail, and the request stays over the window until the bounded
     * overflow retries run out and the turn is written as an error. Clamping here
     * is what makes shrinking possible at all on a small-window model.
     *
     * A window too small to hold both a tail and a head (say a 2k window whose
     * reserve leaves 1k) collapses to the smallest useful tail rather than 0: a
     * zero tail would let compaction fold the current turn's own prompt.
     */
    fun keepTokens(requested: Int, effectiveWindow: Int): Int {
        val window = effectiveWindow.coerceAtLeast(1)
        val ceiling = (window / 2).coerceAtLeast(1)
        return requested.coerceIn(minOf(MIN_KEEP_TOKENS, ceiling), ceiling)
    }

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
        // A missing window means "we do not know", not "the window is one token".
        // Dividing by `coerceAtLeast(1)` made every non-trivial history look
        // infinitely over budget, so the loop compacted on every single step —
        // a summarization provider call per step for every model the live
        // catalogue does not describe.
        val window = if (contextWindow > 0) contextWindow else UNKNOWN_WINDOW
        val ratio = estimatedTokens.toDouble() / window
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

    /**
     * What to do when a trim/compact pass reported that it changed nothing.
     *
     * Belief that history shrank was implicit before: the loop recomputed the
     * same decision on the next step, got the same answer, and tried the same
     * fold — forever. Every refusal inside `Compaction` is legitimate (`size <=
     * keepCount`, `headEnd <= headStart`, a head holding a user message, an
     * already-summarized head), but the loop could not distinguish "declined
     * because there is nothing new to fold" from "declined because it is
     * broken", so a genuinely unrecoverable over-window request was retried
     * until the bounded overflow retries died and the turn was written as an
     * error with no explanation.
     *
     * [stalled] counts *consecutive* passes that changed nothing. One is normal
     * (a just-compacted head refuses by design); a second on a fresh over-window
     * estimate means the request cannot be made to fit, so the caller should stop
     * pretending it can and fail the step with a reason the user can read.
     */
    fun nextAction(requested: OverflowAction, changed: Boolean, stalled: Int): StallDecision = when {
        requested == OverflowAction.NONE -> StallDecision(OverflowAction.NONE, stalled)
        changed -> StallDecision(OverflowAction.NONE, 0)
        stalled + 1 >= MAX_STALLED_PASSES -> StallDecision(OverflowAction.NONE, stalled + 1)
        else -> StallDecision(requested, stalled + 1)
    }

    /** Consecutive no-op fold passes tolerated before a step is failed. */
    const val MAX_STALLED_PASSES = 2

    /**
     * A fold pass outcome. [action] is the overflow action still worth trying
     * (`NONE` means stop folding); [stalled] is the new consecutive no-op count.
     * The caller compares [stalled] against [MAX_STALLED_PASSES] to decide
     * whether the request is unrecoverable.
     */
    data class StallDecision(val action: OverflowAction, val stalled: Int) {
        /** True when folding has been given up on for this step. */
        val exhausted: Boolean get() = stalled >= MAX_STALLED_PASSES
    }
}
