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
    internal fun tag(id: String) =
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
    fun parseToolRows(p: Part.Tool): List<Pair<String, String>> {
        val out = p.result?.output ?: return emptyList()
        val lines = out.lines().filter { it.isNotBlank() }
        if (lines.size <= 1) return emptyList()
        return lines.take(6).map { l ->
            val t = l.trim()
            val words = t.split(Regex("\\s+"), limit = 2)
            if (words.size == 2) words[0] to words[1] else t to ""
        }
    }

    /**
     * The lines of a tool's raw output that [parseToolRows] did NOT turn into
     * structured rows, so the expanded card can show the full output instead of
     * silently dropping everything past the first few rows. Blank lines are
     * skipped (matching [parseToolRows]); when there is no structured output at
     * all, the whole body is returned. Pure/JVM-testable.
     */
    fun toolExtraLines(output: String, structured: Int): List<String> {
        val lines = output.lines().filter { it.isNotBlank() }
        // parseToolRows collapses a single-line output to no rows; the raw body
        // is still drawn separately in that case, so nothing is "extra".
        if (lines.size <= 1) return emptyList()
        return lines.drop(structured.coerceAtLeast(0))
    }

    /**
     * Collapse whitespace to single spaces, trim, and cap at 140 chars. Written
     * against a [CharSequence] so the streaming buffer can summarize a growing
     * [StringBuilder] in O(140) instead of copying/regexing the whole body on
     * every frame. Whitespace is matched exactly as the old `\s` did.
     */
    internal fun oneLine(s: CharSequence): String {
        // The old implementation collapsed ASCII whitespace runs with `\s+` and
        // then called `String.trim()`, which is Unicode-aware. Trimming the
        // edges first is equivalent (edges are removed either way) and lets the
        // scan stop after the first 141 collapsed characters even though the
        // source can be arbitrarily long.
        var start = 0
        var end = s.length
        while (start < end && isSummaryEdgeSpace(s[start])) start++
        while (end > start && isSummaryEdgeSpace(s[end - 1])) end--
        val out = StringBuilder(144)
        var pendingSpace = false
        var i = start
        while (i < end) {
            val c = s[i]
            if (isSummarySpace(c)) {
                if (out.isNotEmpty()) pendingSpace = true
            } else {
                if (pendingSpace) {
                    out.append(' ')
                    pendingSpace = false
                }
                out.append(c)
                if (out.length > 140) return out.take(140).toString() + "…"
            }
            i++
        }
        return out.toString()
    }

    /** Whitespace the old regex `\s+` collapsed (ASCII only, like Java's). */
    private fun isSummarySpace(c: Char): Boolean =
        c == ' ' || c == '\t' || c == '\n' || c == '\u000B' || c == '\u000C' || c == '\r'

    /**
     * Whitespace the old `String.trim()` stripped at the edges: Java/Kotlin
     * `Char.isWhitespace()` is Unicode-aware, so NBSP and other space chars
     * count here even though the collapse regex did not touch them.
     */
    private fun isSummaryEdgeSpace(c: Char): Boolean = isSummarySpace(c) || c.isWhitespace()

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
        // Drop the failed attempt's live rows (text/reasoning part and any tool
        // call rows it emitted) so the next attempt starts clean.
        is AgentEvent.PartReset -> current.filterNot {
            it.id == event.partId.value || it.id.startsWith("call:${event.messageId}:")
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

    /**
     * Append-or-grow a streaming text row by partId. Single-shot convenience
     * over [DeltaBuffer] for callers that hold an immutable list; the streaming
     * path uses one long-lived [DeltaBuffer] so it never re-concatenates.
     */
    fun applyDelta(current: List<UiStep>, partId: String, delta: String, kind: StepKind, label: String): List<UiStep> {
        val buffer = DeltaBuffer()
        buffer.reset(current)
        buffer.append(partId, delta, kind, label)
        return buffer.snapshot()
    }

    /**
     * A running accumulator for streamed rows. Text lives in a per-part
     * [StringBuilder] rather than being re-concatenated into an immutable
     * [UiStep.body] on every token, so an append is amortized O(delta) and the
     * whole list is only materialized when [snapshot] is called (the view model
     * throttles that to a frame-sized cadence). Structural changes go through
     * [transform]/[rebase] so a rebuild that raced a newer delta cannot drop it.
     *
     * Guarded by the monitor so an Unconfined test dispatcher (or any future
     * multi-threaded caller) cannot tear the maps; production is single-threaded
     * and pays nothing. The public [applyDelta] stays pure for tests.
     */
    class DeltaBuffer {
        private var working: MutableList<UiStep> = ArrayList()
        private val index = HashMap<String, Int>()
        private val bodies = LinkedHashMap<String, StringBuilder>()
        private val kinds = HashMap<String, StepKind>()
        private val labels = HashMap<String, String>()
        private var pending = false

        /** Deltas are buffered and not yet reflected by [snapshot]. */
        @Synchronized
        fun hasPending(): Boolean = pending

        /** Replace the rows wholesale (session load, full store rebuild). */
        @Synchronized
        fun reset(steps: List<UiStep>) {
            working = ArrayList(steps)
            index.clear()
            bodies.clear()
            kinds.clear()
            labels.clear()
            pending = false
            for (i in steps.indices) index[steps[i].id] = i
        }

        /** Append [delta] to [partId], creating the row on first sight. */
        @Synchronized
        fun append(partId: String, delta: String, kind: StepKind, label: String) {
            val i = index[partId]
            if (i == null) {
                index[partId] = working.size
                working.add(
                    UiStep(
                        id = partId, kind = kind, label = label, tag = StepMapper.tag(partId),
                        summary = "", body = "",
                    ),
                )
                bodies[partId] = StringBuilder(delta)
                kinds[partId] = kind
                labels[partId] = label
            } else {
                val sb = bodies.getOrPut(partId) { StringBuilder(working[i].body) }
                kinds.putIfAbsent(partId, working[i].kind)
                labels.putIfAbsent(partId, working[i].label)
                sb.append(delta)
            }
            pending = true
        }

        /**
         * Drop [partId]'s accumulated text and its row, so a retried attempt
         * rebuilds the row from scratch instead of concatenating onto the failed
         * attempt's text. The next [append] recreates it. No-op when unknown.
         */
        @Synchronized
        fun clearPart(partId: String) {
            bodies.remove(partId)
            kinds.remove(partId)
            labels.remove(partId)
            val at = index[partId]?.takeIf { it in working.indices && working[it].id == partId }
                ?: working.indexOfFirst { it.id == partId }.takeIf { it >= 0 }
            if (at != null) {
                working.removeAt(at)
                index.clear()
                for (i in working.indices) index[working[i].id] = i
            } else {
                index.remove(partId)
            }
            pending = true
        }

        /**
         * The rows with every buffered delta applied. Materializes the streaming
         * bodies once; cheap enough for a per-frame caller, far cheaper than
         * doing it per token.
         */
        @Synchronized
        fun snapshot(): List<UiStep> {
            pending = false
            if (bodies.isEmpty()) return ArrayList(working)
            val out = ArrayList<UiStep>(working.size)
            for (step in working) {
                val sb = bodies[step.id]
                out += if (sb == null) step else step.copy(body = sb.toString(), summary = StepMapper.oneLine(sb))
            }
            return out
        }

        /**
         * Fold pending deltas in, apply a structural change, and adopt the
         * result. Used for events that add/merge rows (tool start, optimistic
         * echo, enrichment) so buffered text is never lost.
         */
        @Synchronized
        fun transform(f: (List<UiStep>) -> List<UiStep>) {
            reset(f(snapshot()))
        }

        /**
         * Adopt a store-fresh [newBase] without dropping deltas that arrived
         * while it was being read. A part the store already covers to at least
         * our accumulated body is trusted; a part the store lags is extended
         * with just the missing tail; a part absent from the store is replayed.
         */
        @Synchronized
        fun rebase(newBase: List<UiStep>) {
            if (bodies.isEmpty()) {
                reset(newBase)
                return
            }
            val tails = ArrayList<PendingTail>(bodies.size)
            for ((id, sb) in bodies) {
                tails += PendingTail(
                    id = id,
                    acc = sb.toString(),
                    kind = kinds[id] ?: StepKind.ASSISTANT,
                    label = labels[id] ?: "",
                )
            }
            reset(newBase)
            for (t in tails) {
                val existing = index[t.id]?.let { working[it] }
                when {
                    existing == null -> append(t.id, t.acc, t.kind, t.label)
                    existing.body == t.acc -> Unit
                    t.acc.startsWith(existing.body) -> append(t.id, t.acc.substring(existing.body.length), t.kind, t.label)
                    else -> Unit
                }
            }
        }

        private data class PendingTail(
            val id: String,
            val acc: String,
            val kind: StepKind,
            val label: String,
        )
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
