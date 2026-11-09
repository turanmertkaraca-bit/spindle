package dev.spindle.core.agent

import dev.spindle.core.model.Message
import dev.spindle.core.model.Part
import dev.spindle.core.model.Role

/**
 * Builds a subagent's report from the transcript, and — when there is no prose —
 * from the *work it actually did*.
 *
 * ## Why this is a separate, pure object
 *
 * The owner reported subagents failing three times. Each fix addressed the
 * symptom that was visible at the time; the failures below were all still there,
 * and all three are ordinary logic bugs that a test would have caught:
 *
 * 1. **A reasoning model produces no `Part.Text` at all.** `deepseek-r1`, `o1` and
 *    similar stream `ReasoningDelta` instead of `TextDelta`. A child that thought
 *    for 250k tokens and then called a tool has *zero* text parts, so the old
 *    code reported "no text" about a run that had done real work.
 * 2. **Compaction erases the evidence before it is read.** A long child crosses
 *    the compaction threshold, and `Compaction.compact` replaces the head's parts
 *    with an empty list. Any salvage that walks the store afterwards sees nothing
 *    — so the *longer* a child worked, the *less* it reported. That is backwards.
 * 3. **A child that wrote files but no prose reported nothing.** Several
 *    real failures looked exactly like this: the work landed on disk and the
 *    parent was told "produced no text".
 *
 * The rule this encodes: **a report always says what happened.** Prose if the
 * child wrote prose, reasoning if that is all it produced, and otherwise an
 * explicit list of the changes it made. "No text" is never an acceptable answer
 * to a parent that has to decide what to do next.
 *
 * Pure and dependency-free so it is verified by fast JVM tests; `:app` behaviour
 * is CI-only in this project, and this logic sits in `:core` precisely so it can
 * be trusted locally.
 */
object SubagentReport {

    /**
     * How long a child's text must be to count as a real report rather than a
     * progress narration ("Let me check X"). ~200 chars is one sentence plus a
     * path.
     */
    const val SUBSTANTIAL_CHARS = 200

    /** How much reasoning to consider, so a reasoning-only child is still useful. */
    const val REASONING_PREVIEW_CHARS = 2_000

    /** How many file changes to list before summarising the rest. */
    const val MAX_LISTED_EDITS = 20

    /**
     * One tool call the child made, already flattened from the transcript.
     *
     * Deliberately not a `Part.Tool`: the point of this object is that it can be
     * fed from anything (store, event log, test literal).
     */
    data class ToolUse(val name: String, val ok: Boolean, val detail: String?)

    /**
     * A file the child changed. [path] is relative to the session cwd.
     */
    data class FileChange(val path: String, val created: Boolean)

    /** Everything the report needs, captured at the end of a child run. */
    data class Evidence(
        /** The child's final message parts. */
        val finalParts: List<Part> = emptyList(),
        /** All earlier assistant messages, oldest first, as produced by the store. */
        val earlierAssistantParts: List<List<Part>> = emptyList(),
        /** Tool calls the child made, in order. */
        val toolUses: List<ToolUse> = emptyList(),
        /** Files the child changed, in order. */
        val fileChanges: List<FileChange> = emptyList(),
        /** A terminal error, when the run failed. */
        val error: String? = null,
        /** Why the run stopped, when the loop knows (e.g. "the step budget"). */
        val stopReason: String? = null,
    )

    /**
     * The report text handed to the parent.
     *
     * Order of preference, and the reasoning for each step:
     *  1. **Prose from the final message**, when substantial. This is the clean
     *     case: the child wrote its answer.
     *  2. **The most substantial prose from anywhere in the run.** A child that
     *     ran out of steps mid-investigation still wrote findings earlier.
     *  3. **Its reasoning**, trimmed. A reasoning-only model did the thinking; the
     *     parent should see the conclusion instead of being told nothing happened.
     *  4. **A work summary**: the files it changed and the tools it ran. This is
     *     the honest answer for "created the files but wrote no prose", which is
     *     the shape several real failures took.
     *  5. **The stop reason plus any error**, so the parent can decide to retry.
     */
    fun build(evidence: Evidence): String {
        prose(evidence)?.let { return it }
        // Any text at all beats a derived summary. The model chose those words; a
        // file list is something we inferred. Falling straight from "no substantial
        // prose" to the tool list would bury a short but real finding — e.g.
        // "the desync is in make_move" — under six lines of tool names.
        anyText(evidence)?.let { return it }
        reasoning(evidence)?.let { return it }
        workSummary(evidence)?.let { return it }
        return stopOnly(evidence)
    }

    /**
     * The best text available when nothing clears [SUBSTANTIAL_CHARS].
     *
     * Prefers the final message (the child's last word), then the newest earlier
     * text. Labelled so the parent knows it is not a finished report.
     */
    private fun anyText(e: Evidence): String? {
        val own = textOf(e.finalParts)
        // Prefer the LONGEST available text, not the newest. A child that ran out of
        // steps typically ends on a one-line narration ("Let me check the rest of
        // X") after writing its actual findings earlier; returning that narration
        // because it happens to be last is the "report of nothing" complaint. The
        // longest text is a crude but reliable proxy for "the substantive one".
        val earlier = e.earlierAssistantParts.asReversed()
            .map { textOf(it) }
            .filter { it.isNotBlank() }
        val best = (earlier + listOfNotNull(own.takeIf { it.isNotBlank() }))
            .maxByOrNull { it.length }
            ?: return null
        val where = if (best == own) "all it wrote at the end" else "its most substantial written output"
        return "(the subagent stopped before writing a full report — this is $where)\n\n$best"
    }

    /** Steps 1–2: real prose, labelled when it is not the child's final answer. */
    private fun prose(e: Evidence): String? {
        val own = textOf(e.finalParts)
        if (own.length >= SUBSTANTIAL_CHARS) return own

        val candidates = buildList {
            if (own.isNotBlank()) add(own)
            // Newest first: the latest written findings are the most relevant.
            for (parts in e.earlierAssistantParts.asReversed()) {
                val t = textOf(parts)
                if (t.isNotBlank()) add(t)
            }
        }
        val best = candidates.firstOrNull { it.length >= SUBSTANTIAL_CHARS } ?: return null
        return "(the subagent stopped before writing a final answer — this is its " +
            "most substantial output from the run)\n\n$best"
    }

    /**
     * Step 3: reasoning, when the model produced no prose at all.
     *
     * Labelled explicitly: reasoning is not an answer, and the parent must not
     * mistake it for one. But it is far better than silence — it usually contains
     * the actual findings, because the model reasoned about them.
     */
    private fun reasoning(e: Evidence): String? {
        val all = buildList {
            addAll(reasoningOf(e.finalParts))
            for (parts in e.earlierAssistantParts.asReversed()) addAll(reasoningOf(parts))
        }
        val joined = all.joinToString("").trim()
        if (joined.isBlank()) return null
        val excerpt = if (joined.length > REASONING_PREVIEW_CHARS) {
            joined.take(REASONING_PREVIEW_CHARS) + "\n…[reasoning trimmed]"
        } else {
            joined
        }
        return "(this subagent produced no written answer — this is its reasoning, " +
            "which usually contains the findings)\n\n$excerpt"
    }

    /**
     * Step 4: what the child actually DID.
     *
     * This is the case that produced "no text and no files" reports about a child
     * that had in fact written the files. Listing the changes makes the outcome
     * legible without relying on the model having narrated it.
     */
    private fun workSummary(e: Evidence): String? {
        if (e.fileChanges.isEmpty() && e.toolUses.isEmpty()) return null
        return buildString {
            append("(this subagent produced no written report, but it did work)\n")
            if (e.fileChanges.isNotEmpty()) {
                append("\nFiles changed (${e.fileChanges.size}):\n")
                for (change in e.fileChanges.take(MAX_LISTED_EDITS)) {
                    append("  ").append(if (change.created) "+ " else "~ ").append(change.path).append('\n')
                }
                if (e.fileChanges.size > MAX_LISTED_EDITS) {
                    append("  …and ${e.fileChanges.size - MAX_LISTED_EDITS} more\n")
                }
            }
            if (e.toolUses.isNotEmpty()) {
                append("\nTools run (${e.toolUses.size}):\n")
                for (use in e.toolUses.take(MAX_LISTED_EDITS)) {
                    append("  ").append(if (use.ok) "ok  " else "fail").append(' ').append(use.name)
                    use.detail?.takeIf { it.isNotBlank() }?.let { append(" — ").append(it.take(80)) }
                    append('\n')
                }
                if (e.toolUses.size > MAX_LISTED_EDITS) {
                    append("  …and ${e.toolUses.size - MAX_LISTED_EDITS} more\n")
                }
            }
            e.stopReason?.let { append("\nIt stopped because it reached $it.") }
            e.error?.let { append("\nError: ").append(it) }
        }.trimEnd()
    }

    /** Step 5: nothing was done and nothing was written. Say exactly that, and why. */
    private fun stopOnly(e: Evidence): String = buildString {
        append("(the subagent stopped without producing a report")
        e.stopReason?.let { append(": it reached ").append(it) }
        append(')')
        e.error?.let { append("\nError: ").append(it) }
    }

    private fun textOf(parts: List<Part>): String =
        parts.filterIsInstance<Part.Text>().joinToString("") { it.text }.trim()

    private fun reasoningOf(parts: List<Part>): List<String> =
        parts.filterIsInstance<Part.Reasoning>().map { it.text }

    /**
     * Flatten a stored transcript into [Evidence].
     *
     * Walks newest-first for the assistant parts because the report prefers the
     * latest findings, and collects tool calls and file edits in document order so
     * the work summary reads chronologically.
     *
     * **[assistantParts] must be captured from the store BEFORE compaction can
     * rewrite it**, which is why the spawner collects it as the run proceeds
     * rather than only at the end. Passing an already-compacted transcript is the
     * bug this whole object exists to avoid.
     */
    fun evidenceFrom(
        messages: List<Message>,
        error: String? = null,
        stopReason: String? = null,
    ): Evidence {
        val assistant = messages.filter { it.role == Role.ASSISTANT }
        val final = assistant.lastOrNull()
        val earlier = assistant.dropLast(1).map { it.parts }
        val toolUses = ArrayList<ToolUse>()
        val changes = ArrayList<FileChange>()
        for (m in messages) {
            for (part in m.parts) {
                when (part) {
                    is Part.Tool -> toolUses += ToolUse(
                        name = part.call.name,
                        ok = part.result?.isError == false,
                        detail = part.result?.output?.lineSequence()?.firstOrNull()?.trim(),
                    )
                    // No Part.File in this model; file changes arrive as
                    // `AgentEvent.FileEdited` and are collected live by the
                    // spawner (see `SubagentSpawner.spawn`).
                    else -> Unit
                }
            }
        }
        return Evidence(
            finalParts = final?.parts.orEmpty(),
            earlierAssistantParts = earlier,
            toolUses = toolUses,
            fileChanges = changes,
            error = error ?: final?.error,
            stopReason = stopReason,
        )
    }
}
