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

    @Serializable
    data class Usage(
        override val sessionId: String,
        val input: Int,
        val output: Int,
        val costUsd: Double,
    ) : WireEvent

    @Serializable
    data class BudgetWarning(
        override val sessionId: String,
        val spentUsd: Double,
        val maxUsd: Double,
        val fraction: Double,
    ) : WireEvent

    @Serializable
    data class Title(override val sessionId: String, val title: String) : WireEvent

    @Serializable
    data class FileEdited(
        override val sessionId: String,
        val path: String,
        val added: Int,
        val removed: Int,
        val diff: String,
    ) : WireEvent

    @Serializable
    data class Snapshot(override val sessionId: String, val snapshotId: String, val path: String) : WireEvent

    @Serializable
    data class SubagentState(
        override val sessionId: String,
        val childSessionId: String,
        val state: String,
    ) : WireEvent

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
            is AgentEvent.UsageUpdated -> Usage(
                e.sessionId.value, e.usage.inputTokens, e.usage.outputTokens, e.usage.costUsd,
            )
            is AgentEvent.BudgetWarning -> BudgetWarning(
                e.sessionId.value, e.spentUsd, e.maxUsd, e.fraction,
            )
            is AgentEvent.TitleUpdated -> Title(e.sessionId.value, e.title)
            is AgentEvent.FileEdited -> FileEdited(
                e.sessionId.value, e.edit.path, e.edit.added, e.edit.removed, e.edit.unifiedDiff,
            )
            is AgentEvent.SnapshotCreated -> Snapshot(e.sessionId.value, e.snapshotId, e.path)
            is AgentEvent.SubagentStateChanged -> SubagentState(
                e.sessionId.value, e.childSessionId.value, e.state.name,
            )
        }
    }
}
