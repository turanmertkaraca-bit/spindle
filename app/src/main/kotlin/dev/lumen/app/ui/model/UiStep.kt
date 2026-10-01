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
 * @param think reasoning folded into this block (see StepMapper.groupSteps);
 *        shown expanded while it is alone and collapsed once [body] exists
 * @param thinkTag the stable tag of the reasoning row, kept for tests/traceability
 * @param childSteps full child transcript of a subagent, loaded on demand when
 *        the user taps the subagent bubble (lazy, so a rebuild never reads every
 *        child session)
 * @param childLoading true while the child transcript is being read
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
    /** Reasoning grouped with this step; null when the step has none. */
    val think: String? = null,
    /** Tag of the folded reasoning row. */
    val thinkTag: String? = null,
    /** Full child transcript of a subagent, loaded on tap. */
    val childSteps: List<UiStep> = emptyList(),
    /** True while the child transcript is loading. */
    val childLoading: Boolean = false,
    /** Tool names of the calls folded into this run, for the compact summary. */
    val toolNames: List<String> = emptyList(),
    /** The store message this row came from; used by rewind. Null for synthetic rows. */
    val messageId: String? = null,
)
