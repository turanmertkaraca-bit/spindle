package dev.lumen.app.ui.model

/** How a step is drawn on the beam. */
enum class StepKind { YOU, THINKING, TOOL, SUBAGENT, QUESTION, ASSISTANT }

/**
 * Everything the timeline needs to draw one row, and nothing else. Deliberately
 * a flat view model so tests can construct rows without any backend.
 *
 * @param summary the one-line form shown while collapsed
 * @param body the full text shown while open
 * @param rows sub-steps (tool sub-calls or subagent children) — only shown when open
 * @param running a tool/subagent currently executing (spinner node)
 * @param failed the step ended in an error (red node)
 * @param merged how many steps this row represents; consecutive tool/subagent
 *        calls merge into a single merged droplet (>1 shows the extra dots)
 */
data class UiStep(
    val id: String,
    val kind: StepKind,
    val label: String,
    val tag: String,
    val summary: String,
    val body: String,
    val rows: List<Pair<String, String>> = emptyList(),
    val running: Boolean = false,
    val failed: Boolean = false,
    val merged: Int = 1,
    /** Child session id for a subagent step, so the UI can show what it did. */
    val childId: String? = null,
)
