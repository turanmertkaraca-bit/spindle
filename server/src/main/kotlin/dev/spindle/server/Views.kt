package dev.spindle.server

import dev.spindle.core.model.Message
import dev.spindle.core.model.Part
import dev.spindle.core.model.Role
import kotlinx.serialization.Serializable

/** A serializable snapshot of a stored [Message], for the browser's rebuild path. */
@Serializable
data class MessageView(
    val id: String,
    val role: String,
    val parts: List<PartView>,
    val createdAt: Long,
    val model: String? = null,
    val finish: String? = null,
    val error: String? = null,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val costUsd: Double = 0.0,
) {
    companion object {
        fun of(m: Message) = MessageView(
            id = m.id.value,
            role = m.role.name.lowercase(),
            parts = m.parts.map { PartView.of(it) },
            createdAt = m.createdAt,
            model = m.model,
            finish = m.finish?.name,
            error = m.error,
            inputTokens = m.usage.inputTokens,
            outputTokens = m.usage.outputTokens,
            costUsd = m.usage.costUsd,
        )
    }
}

/**
 * A serializable view of a [Part]. Flat (no sealed hierarchy) so the JSON is
 * trivial for any UI to consume. `type` picks the renderer.
 */
@Serializable
data class PartView(
    val type: String,
    val id: String,
    val text: String? = null,
    val reasoning: String? = null,
    val tool: ToolView? = null,
) {
    companion object {
        fun of(p: Part): PartView = when (p) {
            is Part.Text -> PartView(type = "text", id = p.id.value, text = p.text)
            is Part.Reasoning -> PartView(type = "reasoning", id = p.id.value, reasoning = p.text)
            is Part.Tool -> PartView(type = "tool", id = p.id.value, tool = ToolView.of(p))
            is Part.File -> PartView(type = "file", id = p.id.value, text = p.path)
            is Part.Step -> PartView(type = "step", id = p.id.value, text = p.index.toString())
        }
    }
}

@Serializable
data class ToolView(
    val name: String,
    val argumentsJson: String,
    val state: String,
    val output: String? = null,
    val isError: Boolean = false,
    val diff: String? = null,
) {
    companion object {
        fun of(p: Part.Tool) = ToolView(
            name = p.call.name,
            argumentsJson = p.call.argumentsJson,
            state = p.state.name,
            output = p.result?.output,
            isError = p.result?.isError ?: false,
            diff = p.result?.diff,
        )
    }
}

/** A session summary for the sidebar. */
@Serializable
data class SessionView(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val preview: String = "",
)

fun previewOf(messages: List<Message>): String {
    val last = messages.lastOrNull { it.role != Role.USER } ?: messages.lastOrNull() ?: return ""
    val text = last.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }
        .ifBlank {
            last.parts.filterIsInstance<Part.Tool>().joinToString(" ") { it.call.name }
        }
    return oneLine(text, 80)
}

private fun oneLine(s: String, max: Int): String =
    s.replace(Regex("\\s+"), " ").trim().let { if (it.length > max) it.take(max) + "…" else it }
