package dev.lumen.app.ui.model

import dev.spindle.core.event.AgentEvent
import dev.spindle.core.model.Message
import dev.spindle.core.model.Part
import dev.spindle.core.model.Role
import dev.spindle.core.model.ToolState

/**
 * Turns the backend's stored messages + live events into the flat row list the
 * timeline draws. Pure and side-effect free, so it is unit-testable without any
 * Android or network — the same discipline as [dev.spindle.core.ui.TimelineLayout].
 */
object StepMapper {

    /**
     * The stable row identity for a part, derived from the id alone, so the same
     * row shows the same tag whether it was streamed live or rebuilt from the store.
     */
    private fun tag(id: String) =
        Integer.toHexString(0x100000 + (id.hashCode() and 0xFFFFF)).takeLast(6)

    fun fromMessages(messages: List<Message>): List<UiStep> {
        val out = ArrayList<UiStep>()
        for (m in messages) {
            when (m.role) {
                Role.USER -> {
                    val text = m.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }.trim()
                    val images = m.parts.filterIsInstance<Part.File>().map { f ->
                        UiImage(name = f.path, mime = f.mime ?: "image/*", base64 = f.dataBase64.orEmpty())
                    }
                    if (text.isNotBlank() || images.isNotEmpty()) {
                        out += UiStep(
                            id = m.id.value, kind = StepKind.YOU, label = "YOU", tag = tag(m.id.value),
                            summary = if (text.isNotBlank()) oneLine(text) else oneLine(images.first().name),
                            body = text, images = images, messageId = m.id.value,
                        )
                    }
                }
                Role.ASSISTANT -> {
                    for (p in m.parts) {
                        when (p) {
                            is Part.Reasoning -> {
                                if (p.text.isBlank()) continue
                                out += UiStep(
                                    id = p.id.value, kind = StepKind.THINKING, label = "THINKING", tag = tag(p.id.value),
                                    summary = oneLine(p.text), body = p.text.trim(), messageId = m.id.value,
                                )
                            }
                            is Part.Text -> {
                                if (p.text.isBlank()) continue
                                out += UiStep(
                                    id = p.id.value, kind = StepKind.ASSISTANT, label = "ASSISTANT", tag = tag(p.id.value),
                                    summary = oneLine(p.text), body = p.text.trim(), messageId = m.id.value,
                                )
                            }
                            is Part.Tool -> {
                                val call = p.call
                                val rows = parseToolRows(p)
                                val isSub = call.name == "task"
                                out += UiStep(
                                    id = p.id.value,
                                    kind = if (isSub) StepKind.SUBAGENT else StepKind.TOOL,
                                    label = call.name.uppercase(),
                                    tag = tag(p.id.value),
                                    summary = oneLine(p.result?.output ?: call.argumentsJson),
                                    body = toolBody(p),
                                    rows = rows,
                                    toolNames = listOf(call.name.lowercase()),
                                    running = p.state == ToolState.RUNNING || p.state == ToolState.PENDING,
                                    failed = p.state == ToolState.ERROR,
                                    childId = if (isSub) p.result?.metadata?.get("sessionId") else null,
                                    toolMetadata = p.result?.metadata.orEmpty(),
                                    messageId = m.id.value,
                                )
                            }
                            else -> Unit
                        }
                    }
                }
                else -> Unit
            }
        }
        return mergeAdjacent(out)
    }

    /**
     * Consecutive tool / subagent calls collapse into one droplet (the rope gets
     * fewer, fatter beads instead of a long chain of identical tool dots). Any
     * other step kind breaks the run. Pure, so the merge is unit-tested.
     */
    fun mergeAdjacent(steps: List<UiStep>): List<UiStep> {
        val res = ArrayList<UiStep>(steps.size)
        for (s in steps) {
            val last = res.lastOrNull()
            if (last != null && isMergeable(last.kind) && isMergeable(s.kind)) {
                res[res.size - 1] = merge(last, s)
            } else {
                res += s
            }
        }
        return res
    }

    private fun isMergeable(kind: StepKind): Boolean =
        kind == StepKind.TOOL || kind == StepKind.SUBAGENT

    /**
     * Fold a reasoning row into the step that follows it in the same assistant
     * turn — the answer text, or the tool/subagent call that comes next. The
     * thinking rides on the host row as [UiStep.think]; the timeline shows it
     * expanded while it is alone and collapses it to a header once the host has
     * a body. Pure, so the fold is unit-tested and can run on every frame of a
     * stream (the raw rows stay untouched, so deltas still find their part id).
     */
    fun groupSteps(steps: List<UiStep>): List<UiStep> {
        val out = ArrayList<UiStep>(steps.size)
        var pending: UiStep? = null
        for (s in steps) {
            if (s.kind == StepKind.THINKING) {
                val prev = pending
                pending = if (prev == null) s else prev.copy(
                    body = prev.body + "\n\n" + s.body,
                    summary = oneLine(prev.body + " " + s.body),
                )
                continue
            }
            val host = if (s.kind == StepKind.ASSISTANT || isMergeable(s.kind)) pending else null
            if (host != null && s.body.isNotBlank()) {
                // Keep the THINKING row's identity so the LazyColumn key is stable
                // across the think→answer transition: the item grows in place and
                // the list never removes+re-inserts (which caused a scroll jump).
                out += s.copy(
                    id = host.id,
                    think = host.body,
                    thinkTag = host.tag,
                )
                pending = null
            } else {
                if (pending != null) {
                    out += pending
                    pending = null
                }
                out += s
            }
        }
        if (pending != null) out += pending
        return out
    }

    private fun merge(a: UiStep, b: UiStep): UiStep {
        val merged = a.merged + b.merged
        val promoted = a.kind == StepKind.SUBAGENT || b.kind == StepKind.SUBAGENT
        return a.copy(
            kind = if (promoted) StepKind.SUBAGENT else StepKind.TOOL,
            label = if (promoted) "SUBAGENT" else a.label,
            merged = merged,
            summary = "$merged calls · ${b.summary}",
            body = a.body + "\n\n" + b.body,
            rows = a.rows + b.rows,
            toolNames = a.toolNames + b.toolNames,
            toolMetadata = a.toolMetadata + b.toolMetadata,
            running = a.running || b.running,
            failed = a.failed || b.failed,
        )
    }

    private fun toolBody(p: Part.Tool): String {
        val result = p.result
        return when {
            result == null -> p.call.argumentsJson
            result.isError -> "failed: ${result.output}"
            else -> result.output
        }
    }

    /** Pull a short (label, value) list out of a tool result for the sub-rows. */
    private fun parseToolRows(p: Part.Tool): List<Pair<String, String>> {
        val out = p.result?.output ?: return emptyList()
        val lines = out.lines().filter { it.isNotBlank() }
        if (lines.size <= 1) return emptyList()
        return lines.take(6).map { l ->
            val t = l.trim()
            val words = t.split(Regex("\\s+"), limit = 2)
            if (words.size == 2) words[0] to words[1] else t to ""
        }
    }

    private fun oneLine(s: String): String =
        s.replace(Regex("\\s+"), " ").trim().let { if (it.length > 140) it.take(140) + "…" else it }

    /**
     * Apply a live event to the row list. Returns the new list. Kept here (not in
     * the composable) so the streaming behaviour is testable.
     */
    fun applyEvent(current: List<UiStep>, event: AgentEvent): List<UiStep> = when (event) {
        is AgentEvent.PartDelta -> current // deltas are cheap; see applyDelta
        is AgentEvent.ToolCallStarted -> {
            val id = "call:${event.messageId}:${event.index}"
            if (current.any { it.id == id }) {
                current
            } else {
                current + UiStep(
                    id = id, kind = StepKind.TOOL, label = event.name.uppercase(), tag = "",
                    summary = "calling…", body = "", running = true,
                    toolNames = listOf(event.name.lowercase()),
                )
            }
        }
        is AgentEvent.Progress -> current.map {
            if (it.running) it.copy(summary = oneLine(event.message)) else it
        }
        else -> current
    }

    /**
     * The row shown the instant send is tapped, before the store has the
     * message. Its [PENDING_USER_ID] id makes it replaceable by the next
     * store-backed rebuild without duplicating the user's turn.
     */
    fun optimisticUser(text: String): UiStep = UiStep(
        id = PENDING_USER_ID, kind = StepKind.YOU, label = "YOU", tag = "",
        summary = oneLine(text), body = text.trim(),
    )

    /** Append-or-grow a streaming text row by partId. */
    fun applyDelta(current: List<UiStep>, partId: String, delta: String, kind: StepKind, label: String): List<UiStep> {
        val i = current.indexOfFirst { it.id == partId }
        return if (i >= 0) {
            val old = current[i]
            val body = old.body + delta
            current.toMutableList().also {
                it[i] = old.copy(body = body, summary = oneLine(body))
            }
        } else {
            current + UiStep(
                id = partId, kind = kind, label = label, tag = Integer.toHexString(0x100000 + (partId.hashCode() and 0xFFFFF)).takeLast(6),
                summary = oneLine(delta), body = delta,
            )
        }
    }

    /** Id of the optimistic user row; a rebuild replaces it with the store row. */
    const val PENDING_USER_ID = "\u0000you"

    /**
     * Last-wins de-duplication by id. A stopped run can leave an optimistic row
     * in the list, and handing a LazyColumn two identical keys throws and
     * crashes the app, so the render path always cleans ids first. Pure/testable.
     */
    fun dedupeById(steps: List<UiStep>): List<UiStep> {
        if (steps.size < 2) return steps
        val seen = HashSet<String>(steps.size)
        val out = ArrayList<UiStep>(steps.size)
        for (i in steps.indices.reversed()) {
            if (seen.add(steps[i].id)) out.add(steps[i])
        }
        out.reverse()
        return out
    }
}
