package dev.spindle.core.agent

import dev.spindle.core.model.Ids
import dev.spindle.core.model.Message
import dev.spindle.core.model.Part
import dev.spindle.core.model.PartId
import dev.spindle.core.model.Role
import dev.spindle.core.model.SessionId
import dev.spindle.core.provider.ProviderRegistry
import dev.spindle.core.store.SessionStore
import dev.spindle.core.event.AgentEvent
import dev.spindle.core.event.EventBus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect

/**
 * Context management. [trim] shrinks the *content* of old turns without changing
 * the message count (safe: the model still sees the shape of the conversation).
 * [compact] folds everything before the recent tail into one summary message.
 *
 * Both are conservative: the most recent turns and any open tool call are never
 * touched, because removing them breaks the assistant/tool pairing the providers
 * require.
 */
object Compaction {
    /**
     * Legacy fixed recent-message window, kept for callers that still reference
     * it. The retained tail is now sized by the token budget
     * ([ContextBudget.compactKeepTokens]) because a fixed count treats a turn of
     * one-line prompts and a turn of huge tool output identically.
     */
    const val KEEP_RECENT = 6

    /** Never fold the conversation below this many trailing messages. */
    const val MIN_KEEP_RECENT = 2

    private const val TRIM_TOOL_OUTPUT = 2_000
    private const val TRIM_TEXT = 4_000
    private const val TRIM_TOOL_DIFF = 4_000

    /** Inline base64 larger than this is dropped from an old message's File part. */
    private const val TRIM_FILE_BASE64 = 4_000

    suspend fun trim(
        store: SessionStore,
        sessionId: SessionId,
        bus: EventBus? = null,
        keepTokens: Int = ContextBudget.DEFAULT_COMPACT_KEEP_TOKENS,
    ): Int {
        val messages = store.messages(sessionId)
        val keepFrom = (messages.size - retainedCount(messages, keepTokens)).coerceAtLeast(0)
        var changed = 0
        for (i in 0 until keepFrom) {
            val m = messages[i]
            val trimmed = m.copy(parts = m.parts.map { clip(it) })
            if (trimmed != m) {
                store.updateMessage(trimmed)
                // Surface the shrink to the UI instead of leaving it showing the
                // untrimmed text until the next full rebuild.
                bus?.let { b ->
                    trimmed.parts.forEachIndexed { idx, p ->
                        if (p != m.parts.getOrNull(idx)) {
                            b.emit(AgentEvent.PartUpdated(sessionId, trimmed.id.value, p))
                        }
                    }
                }
                changed++
            }
        }
        return changed
    }

    private fun clip(part: Part): Part = when (part) {
        is Part.Text -> if (part.text.length > TRIM_TEXT) {
            Part.Text(part.id, part.text.take(TRIM_TEXT) + "\n…[trimmed]")
        } else part
        is Part.Reasoning -> Part.Reasoning(part.id, part.text.take(500))
        is Part.Tool -> {
            val r = part.result ?: return part
            val output = if (r.output.length > TRIM_TOOL_OUTPUT) {
                r.output.take(TRIM_TOOL_OUTPUT) + "\n…[trimmed]"
            } else {
                r.output
            }
            val diff = r.diff?.let {
                if (it.length > TRIM_TOOL_DIFF) it.take(TRIM_TOOL_DIFF) + "\n…[trimmed]" else it
            }
            if (output != r.output || diff != r.diff) {
                part.copy(result = r.copy(output = output, diff = diff))
            } else part
        }
        // Old inline images are dropped from history so a long session cannot
        // re-send every attachment ever queued on every subsequent turn.
        is Part.File -> {
            val base64 = part.dataBase64
            if (base64 != null && base64.length > TRIM_FILE_BASE64) part.copy(dataBase64 = null) else part
        }
        else -> part
    }

    /**
     * How many trailing messages fit in [keepTokens], measured with the shared
     * [TokenEstimator] (wire characters plus a fixed per-message overhead).
     * Always at least [MIN_KEEP_RECENT]: a turn whose own content exceeds the
     * budget is still kept verbatim rather than folded down to a lone summary.
     */
    fun retainedCount(messages: List<Message>, keepTokens: Int): Int {
        val recent = ArrayList<Int>()
        for (i in messages.indices.reversed()) {
            recent.add(0, messageChars(messages[i]))
            if (recent.size > MIN_KEEP_RECENT &&
                TokenEstimator.estimateFromCharCounts(0, recent, 0) > keepTokens
            ) {
                recent.removeAt(0)
                break
            }
        }
        return recent.size
    }

    /**
     * Characters actually sent to the provider for [m]; reasoning is persisted
     * for the UI but never sent, so it is not counted.
     */
    private fun messageChars(m: Message): Int = m.parts.sumOf { p ->
        when (p) {
            is Part.Text -> p.text.length
            is Part.Reasoning -> 0
            is Part.Tool -> p.call.argumentsJson.length + (p.result?.output?.length ?: 0)
            else -> 16
        }
    }

    /**
     * Keep a tool reply with the turn that carried its tool call. The token
     * window can land the retained boundary exactly on a bare tool result;
     * folding the assistant call while keeping the result would orphan it, so
     * the boundary walks back to include the call.
     */
    private fun alignKeepFrom(messages: List<Message>, keepFrom: Int): Int {
        var start = keepFrom
        while (start in 1 until messages.size && messages[start].role == Role.TOOL) start--
        return start
    }

    /**
     * Fold the head of the session into a single summary at the head of the
     * conversation, leaving a token-budgeted recent tail verbatim. Returns true
     * when a compaction actually happened. The tail is sized by [keepTokens]
     * with the shared [TokenEstimator] (never fewer than [MIN_KEEP_RECENT]
     * messages). A single summarization request is made against the caller's
     * model — for the agent loop that is the session's own model; failures fall
     * back to a deterministic extract without calling the provider.
     */
    suspend fun compact(
        store: SessionStore,
        sessionId: SessionId,
        modelRef: String,
        providers: ProviderRegistry,
        bus: EventBus,
        keepTokens: Int = ContextBudget.DEFAULT_COMPACT_KEEP_TOKENS,
    ): Boolean {
        val messages = store.messages(sessionId)
        val keepCount = retainedCount(messages, keepTokens)
        // Nothing beyond the retained tail means there is no head to fold.
        if (messages.size <= keepCount) return false

        // Compaction may only consume messages strictly before the current
        // turn's first user message; the current user prompt (and any user
        // message that belongs to it, e.g. a separate attachments message) and
        // everything after it are never folded. Without this boundary a long
        // single-prompt run folds its own instruction once enough turns
        // accumulate after it — which is reachable now that overflow is
        // ratio-driven and re-compacts on later turns.
        val lastUser = messages.indexOfLast { it.role == Role.USER }
        var turnStart = lastUser
        while (turnStart > 0 && messages[turnStart - 1].role == Role.USER) turnStart--
        // The token budget can land the retained boundary inside a tool reply;
        // align it so a retained result is never severed from its call.
        val keepFrom = alignKeepFrom(messages, (messages.size - keepCount).coerceAtLeast(0))
        // Never fold the current turn's user message(s). When the only user
        // message sits at index 0 there is no earlier turn boundary, so fold the
        // old assistant/tool turns from index 1 and keep the instruction at index
        // 0 verbatim. The fallback refuses if it would fold any user message.
        val startedAtZero = turnStart <= 0
        val headStart = if (startedAtZero) 1 else 0
        val headEnd = if (startedAtZero) keepFrom else minOf(turnStart, keepFrom)
        if (headEnd <= headStart) return false

        val head = messages.subList(headStart, headEnd)
        if (head.isEmpty()) return false
        if (startedAtZero && head.any { it.role == Role.USER }) return false
        // Nothing new since the last compaction: the only head content is the
        // existing summary. Skip the provider call so a long session cannot
        // re-summarize its own summary every step.
        val alreadySummarized = head.first().parts.any {
            it is Part.Text && it.text.startsWith(AgentLoop.COMPACT_MARKER)
        }
        if (alreadySummarized && head.drop(1).all { it.parts.isEmpty() }) return false
        val headText = head.joinToString("\n\n") { m ->
            m.role.name.lowercase() + ": " + m.parts.joinToString(" ") { p ->
                when (p) {
                    is Part.Text -> p.text
                    is Part.Tool -> "[tool ${p.call.name}] ${p.result?.output?.take(300) ?: "(pending)"}"
                    is Part.Reasoning -> ""
                    else -> ""
                }
            }.take(2_000)
        }

        val summary = summarize(modelRef, providers, headText)
        val summaryPart = Part.Text(
            PartId(Ids.new("prt")),
            AgentLoop.COMPACT_MARKER + " " + summary,
        )

        // The store SPI has no insert-at-position, so the summary takes over the
        // first head message and the rest of the head is emptied. The tail is
        // never touched, which keeps the current user message and provider
        // assistant/tool pairing intact.
        val first = head.first()
        store.updateMessage(first.copy(parts = listOf(summaryPart)))
        bus.emit(AgentEvent.PartUpdated(sessionId, first.id.value, summaryPart))
        for (m in head.drop(1)) {
            if (m.parts.isNotEmpty()) store.updateMessage(m.copy(parts = emptyList()))
        }
        return true
    }

    private suspend fun summarize(modelRef: String, providers: ProviderRegistry, text: String): String {
        if (text.isBlank()) return "(nothing to summarize)"
        return try {
            val (provider, model) = providers.resolve(modelRef) ?: return extract(text)
            val req = dev.spindle.core.provider.ChatRequest(
                model = model.id,
                system = "Summarize the following coding session into a compact brief. Keep: the goal, decisions made, files touched, current state, and open questions. Be terse. No preamble.",
                messages = listOf(dev.spindle.core.provider.WireMessage(role = "user", text = text.take(24_000))),
                tools = emptyList(),
            )
            val sb = StringBuilder()
            provider.stream(req).collect { ev ->
                if (ev is dev.spindle.core.provider.ProviderEvent.TextDelta) sb.append(ev.text)
                if (ev is dev.spindle.core.provider.ProviderEvent.Failure) throw RuntimeException(ev.message)
            }
            sb.toString().ifBlank { extract(text) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            extract(text)
        }
    }

    private fun extract(text: String): String =
        "Earlier turns (summarized offline):\n" + text.take(2_000)
}
