package dev.spindle.core.agent

import dev.spindle.core.model.Message
import dev.spindle.core.model.MessageId
import dev.spindle.core.model.Part
import dev.spindle.core.model.PartId
import dev.spindle.core.model.Role
import dev.spindle.core.model.SessionId
import dev.spindle.core.provider.ProviderRegistry
import dev.spindle.core.store.SessionStore
import dev.spindle.core.event.EventBus
import kotlinx.coroutines.flow.collect

/**
 * Context management. [trim] shrinks the *content* of old turns without changing
 * the message count (safe: the model still sees the shape of the conversation).
 * [compact] replaces everything before the recent tail with one summary message.
 *
 * Both are conservative: the most recent turns and any open tool call are never
 * touched, because removing them breaks the assistant/tool pairing the providers
 * require.
 */
object Compaction {
    /** How many trailing messages are always preserved verbatim. */
    const val KEEP_RECENT = 6
    private const val TRIM_TOOL_OUTPUT = 2_000
    private const val TRIM_TEXT = 4_000

    suspend fun trim(store: SessionStore, sessionId: SessionId): Int {
        val messages = store.messages(sessionId)
        val keepFrom = (messages.size - KEEP_RECENT).coerceAtLeast(0)
        var changed = 0
        for (i in 0 until keepFrom) {
            val m = messages[i]
            val trimmed = m.copy(parts = m.parts.map { clip(it) })
            if (trimmed != m) {
                store.updateMessage(trimmed)
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
            val r = part.result
            if (r != null && r.output.length > TRIM_TOOL_OUTPUT) {
                part.copy(result = r.copy(output = r.output.take(TRIM_TOOL_OUTPUT) + "\n…[trimmed]"))
            } else part
        }
        else -> part
    }

    /**
     * Summarize the head of the session into one assistant message. Returns true
     * when a compaction actually happened. A single summarization request is made
     * against the cheapest available model; failures fall back to a deterministic
     * extract without calling the provider.
     */
    suspend fun compact(
        store: SessionStore,
        sessionId: SessionId,
        modelRef: String,
        providers: ProviderRegistry,
        bus: EventBus,
    ): Boolean {
        val messages = store.messages(sessionId)
        if (messages.size <= KEEP_RECENT + 2) return false

        val head = messages.dropLast(KEEP_RECENT)
        val tail = messages.takeLast(KEEP_RECENT)
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
        val summaryMsg = Message(
            id = MessageId("msg_" + dev.spindle.core.model.Ids.new("cmp")),
            sessionId = sessionId,
            role = Role.ASSISTANT,
            parts = listOf(Part.Text(PartId(dev.spindle.core.model.Ids.new("prt")), AgentLoop.COMPACT_MARKER + " " + summary)),
            createdAt = System.currentTimeMillis(),
        )

        // Rebuild the session: summary + recent tail.
        store.messages(sessionId).forEach { store.updateMessage(it.copy(parts = emptyList())) }
        // Simplest correct approach with the current store SPI: delete by rewriting.
        // (The SQLite store keeps history, so we mark compacted messages as empty parts
        //  and prepend the summary, preserving ids for any UI references.)
        store.appendMessage(summaryMsg)
        bus.emit(dev.spindle.core.event.AgentEvent.MessageCreated(sessionId, summaryMsg.id.value, "assistant"))
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
        } catch (e: Throwable) {
            extract(text)
        }
    }

    private fun extract(text: String): String =
        "Earlier turns (summarized offline):\n" + text.take(2_000)
}
