package dev.spindle.core.event

import dev.spindle.core.model.FileEdit
import dev.spindle.core.model.Part
import dev.spindle.core.model.PartId
import dev.spindle.core.model.SessionId
import dev.spindle.core.model.SessionState
import dev.spindle.core.model.ToolResult
import dev.spindle.core.model.Usage
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

enum class DeltaKind { TEXT, REASONING }

/** Everything the UI needs, pushed as it happens. The store remains the truth. */
sealed interface AgentEvent {
    val sessionId: SessionId

    data class StateChanged(override val sessionId: SessionId, val state: SessionState) : AgentEvent
    data class MessageCreated(override val sessionId: SessionId, val messageId: String, val role: String) : AgentEvent

    data class PartDelta(
        override val sessionId: SessionId,
        val messageId: String,
        val partId: PartId,
        val kind: DeltaKind,
        val delta: String,
    ) : AgentEvent

    data class PartUpdated(override val sessionId: SessionId, val messageId: String, val part: Part) : AgentEvent

    data class ToolFinished(
        override val sessionId: SessionId,
        val messageId: String,
        val partId: PartId,
        val result: ToolResult,
    ) : AgentEvent

    /**
     * Emitted the moment the model starts emitting a tool call, before it is
     * persisted, so the UI can show a live node.
     */
    data class ToolCallStarted(
        override val sessionId: SessionId,
        val messageId: String,
        val index: Int,
        val name: String,
    ) : AgentEvent

    data class PermissionRequested(
        override val sessionId: SessionId,
        val requestId: String,
        val tool: String,
        val detail: String,
        val pattern: String?,
    ) : AgentEvent

    data class QuestionAsked(
        override val sessionId: SessionId,
        val requestId: String,
        val question: String,
        val options: List<String>,
        val multiple: Boolean = false,
    ) : AgentEvent

    data class Error(override val sessionId: SessionId, val message: String) : AgentEvent

    /** Out-of-band tool progress (long-running bash, fetch, subagent, …). */
    data class Progress(
        override val sessionId: SessionId,
        val message: String,
        val fraction: Double? = null,
    ) : AgentEvent

    /** Running token/cost totals for the session, rolled up from each message. */
    data class UsageUpdated(override val sessionId: SessionId, val usage: Usage) : AgentEvent

    /** The session crossed its cost-warning threshold (or hit the ceiling). */
    data class BudgetWarning(
        override val sessionId: SessionId,
        val spentUsd: Double,
        val maxUsd: Double,
        val fraction: Double,
    ) : AgentEvent

    /** A session's title was set or auto-generated. */
    data class TitleUpdated(override val sessionId: SessionId, val title: String) : AgentEvent

    /** A mutating tool changed a file; carries the structured edit for the Changes view. */
    data class FileEdited(override val sessionId: SessionId, val edit: FileEdit) : AgentEvent

    /** A pre-edit snapshot was recorded and can be used to revert [path]. */
    data class SnapshotCreated(
        override val sessionId: SessionId,
        val snapshotId: String,
        val path: String,
    ) : AgentEvent

    /** A child (subagent) session changed run state; children are filtered from StateChanged. */
    data class SubagentStateChanged(
        override val sessionId: SessionId,
        val childSessionId: SessionId,
        val state: SessionState,
    ) : AgentEvent
}

/** Fan-out bus. Slow subscribers drop oldest rather than blocking the loop. */
class EventBus {
    private val flow = MutableSharedFlow<AgentEvent>(
        replay = 0,
        extraBufferCapacity = 1024,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<AgentEvent> get() = flow
    fun emit(event: AgentEvent) { flow.tryEmit(event) }
}
