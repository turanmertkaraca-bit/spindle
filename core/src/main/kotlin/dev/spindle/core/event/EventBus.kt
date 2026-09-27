package dev.spindle.core.event

import dev.spindle.core.model.Part
import dev.spindle.core.model.PartId
import dev.spindle.core.model.SessionId
import dev.spindle.core.model.SessionState
import dev.spindle.core.model.ToolResult
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
