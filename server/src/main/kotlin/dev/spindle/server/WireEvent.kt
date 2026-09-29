package dev.spindle.server

import dev.spindle.core.event.AgentEvent
import dev.spindle.core.event.DeltaKind
import kotlinx.serialization.Serializable

/**
 * The wire form of an [AgentEvent], so the browser can render exactly what the
 * app renders. Kept here (not in :core) because it is a presentation concern:
 * the UI-facing shape may evolve without touching the agent.
 */
@Serializable
sealed interface WireEvent {
    val sessionId: String

    @Serializable
    data class State(override val sessionId: String, val state: String) : WireEvent

    @Serializable
    data class MessageCreated(override val sessionId: String, val messageId: String, val role: String) : WireEvent

    @Serializable
    data class Delta(
        override val sessionId: String,
        val messageId: String,
        val partId: String,
        val kind: String,
        val text: String,
    ) : WireEvent

    @Serializable
    data class PartUpdated(override val sessionId: String, val messageId: String, val part: PartView) : WireEvent

    @Serializable
    data class ToolFinished(
        override val sessionId: String,
        val messageId: String,
        val partId: String,
        val output: String,
        val isError: Boolean,
    ) : WireEvent

    @Serializable
    data class ToolCallStarted(
        override val sessionId: String,
        val messageId: String,
        val index: Int,
        val name: String,
    ) : WireEvent

    @Serializable
    data class Progress(override val sessionId: String, val message: String, val fraction: Double? = null) : WireEvent

    @Serializable
    data class Error(override val sessionId: String, val message: String) : WireEvent

    @Serializable
    data class Question(override val sessionId: String, val question: String, val options: List<String>, val multiple: Boolean) : WireEvent

    @Serializable
    data class Permission(override val sessionId: String, val tool: String, val detail: String) : WireEvent

    companion object {
        fun of(e: AgentEvent): WireEvent = when (e) {
            is AgentEvent.StateChanged -> State(e.sessionId.value, e.state.name)
            is AgentEvent.MessageCreated -> MessageCreated(e.sessionId.value, e.messageId, e.role)
            is AgentEvent.PartDelta -> Delta(
                e.sessionId.value, e.messageId, e.partId.value,
                if (e.kind == DeltaKind.REASONING) "reasoning" else "text", e.delta,
            )
            is AgentEvent.PartUpdated -> PartUpdated(e.sessionId.value, e.messageId, PartView.of(e.part))
            is AgentEvent.ToolFinished -> ToolFinished(
                e.sessionId.value, e.messageId, e.partId.value, e.result.output, e.result.isError,
            )
            is AgentEvent.ToolCallStarted -> ToolCallStarted(e.sessionId.value, e.messageId, e.index, e.name)
            is AgentEvent.Progress -> Progress(e.sessionId.value, e.message, e.fraction)
            is AgentEvent.Error -> Error(e.sessionId.value, e.message)
            is AgentEvent.QuestionAsked -> Question(e.sessionId.value, e.question, e.options, e.multiple)
            is AgentEvent.PermissionRequested -> Permission(e.sessionId.value, e.tool, e.detail)
        }
    }
}
