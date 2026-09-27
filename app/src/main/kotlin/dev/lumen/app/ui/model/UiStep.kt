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
 */
data class UiStep(
    val id: String,
    val kind: StepKind,
    val label: String,
    val tag: String,
    val summary: String,
    val body: String,
    val rows: List<Pair<String, String>> = emptyList(),
)
