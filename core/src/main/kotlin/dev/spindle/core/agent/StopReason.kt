package dev.spindle.core.agent

/**
 * Why a run stopped, as a value.
 *
 * ## Why this exists
 *
 * The loop used to signal "the budget is spent" by **throwing**
 * `IllegalStateException` from `budgetCheck`. That throw happened *outside* the
 * provider-failure `try`/`catch`, so it fell through to the generic
 * `catch (Throwable)` handler, which:
 *
 * - rewrote the session state to `ERROR`,
 * - emitted `AgentEvent.Error` with the raw exception text, and
 * - **re-threw**, so the caller saw an exception rather than a finished run.
 *
 * The result was that a run which simply reached its cost or input-token budget
 * looked like a **crash** — the opposite of the "never end without telling the
 * user" rule. It also skipped the wind-down turn, so the user got no report.
 *
 * A budget stop is a normal, expected end. It is modelled as a value here so it
 * cannot accidentally become an exception again, and so every consumer
 * (`RunEndPolicy`, tests, the UI) classifies it explicitly.
 */
enum class StopReason(
    /** Human-readable cause, used in messages and the report. */
    val label: String,
) {
    /** The run ended because the model produced a turn with no tool calls. */
    FINISHED("the model finished its answer"),

    /** The same tool call repeated beyond `maxRepeatedCalls`. */
    DOOM_LOOP("a repeated identical tool call"),

    /**
     * A hard budget stopped normal work, so one wind-down turn was taken with
     * tools disabled. The run still ends with a written report.
     */
    STEP_BUDGET("the step budget"),
    TOOL_CALL_BUDGET("the tool-call budget"),
    WALL_CLOCK_BUDGET("the wall-clock budget"),

    /**
     * The provider's context window, the cost ceiling or the input-token ceiling
     * was reached. These are *budget* stops, not failures.
     */
    COST_BUDGET("the cost budget"),
    INPUT_TOKEN_BUDGET("the input-token budget"),

    /** The provider reported a terminal error. */
    PROVIDER_ERROR("a provider error"),
}
