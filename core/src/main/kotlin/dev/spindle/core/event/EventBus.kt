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
import java.util.concurrent.atomic.AtomicLong

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

    /**
     * A part's live text/reasoning was discarded because the attempt that
     * streamed it failed and is being retried. The UI must drop anything it
     * accumulated for [partId]; the next attempt recreates the row from its own
     * deltas. Emitted only on a retry, before that attempt's first delta.
     */
    data class PartReset(
        override val sessionId: SessionId,
        val messageId: String,
        val partId: PartId,
    ) : AgentEvent

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
    /** One bus emission, tagged with its monotonic sequence number. */
    data class Entry(val seq: Long, val event: AgentEvent)

    private val flow = MutableSharedFlow<AgentEvent>(
        replay = 0,
        extraBufferCapacity = 1024,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<AgentEvent> get() = flow

    /**
     * The same stream as [events], but each emission carries the monotonic
     * [Entry.seq] a client uses to detect gaps and to catch up after a
     * reconnect (`Last-Event-ID`). Kept additive so existing collectors of
     * [events] compile and behave unchanged.
     */
    private val sequences = MutableSharedFlow<Entry>(
        replay = 0,
        extraBufferCapacity = 1024,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val sequenced: SharedFlow<Entry> get() = sequences

    private val counter = AtomicLong(0)

    /** How many short waits [publish] will attempt before giving up. */
    private val PUBLISH_RETRIES = 8

    /** Wait per retry; 8 * 5ms bounds the stall at ~40ms. */
    private val PUBLISH_RETRY_MS = 5L

    /** Bounded replay ring of the most recent entries, oldest first. */
    private val ring = ArrayDeque<Entry>()
    private val ringLock = Any()

    fun emit(event: AgentEvent) {
        // Assign the seq, append to the ring, and publish under one lock so the
        // sequence order, the ring order, and the live emission order all agree.
        // Otherwise two concurrent emitters could hand out 1 and 2 but publish
        // 2 first, breaking replay and gap detection.
        synchronized(ringLock) {
            val entry = Entry(counter.incrementAndGet(), event)
            ring.addLast(entry)
            while (ring.size > REPLAY_CAPACITY) ring.removeFirst()
            publish(sequences, entry)
            publish(flow, event)
        }
    }

    /**
     * Publish without ever dropping an event the UI depends on.
     *
     * `tryEmit` returns false when the buffer is full AND the overflow policy
     * refuses the new element. With `DROP_OLDEST` that cannot normally happen,
     * but it can for a terminal `StateChanged` racing a burst of deltas on a slow
     * collector, and losing it is catastrophic in a specific way: the screen
     * stays `busy = true` forever, or a run ends and nothing tells the UI. The
     * user sees a chat that "just stopped".
     *
     * So: try the non-blocking path first (the common case, and it preserves
     * ordering), and if it refuses, retry briefly. A dropped progress event
     * degrades to a missed spinner tick; a dropped terminal event is a silent
     * failure, and those are not equivalent.
     */
    private fun <T> publish(target: MutableSharedFlow<T>, value: T) {
        if (target.tryEmit(value)) return
        // The buffer is momentarily saturated. Spin a few short intervals rather
        // than blocking the agent loop: bounded, so a genuinely dead collector
        // still cannot wedge the run (the replay ring is the safety net).
        repeat(PUBLISH_RETRIES) {
            Thread.sleep(PUBLISH_RETRY_MS)
            if (target.tryEmit(value)) return
        }
    }

    /** Ring entries with `seq > afterSeq`, in order; empty if none survive. */
    fun replayAfter(afterSeq: Long): List<Entry> =
        synchronized(ringLock) { ring.filter { it.seq > afterSeq } }

    /** The highest sequence emitted so far, or 0 before the first event. */
    fun lastSeq(): Long = counter.get()

    companion object {
        /** How many recent entries are retained for reconnect catch-up. */
        const val REPLAY_CAPACITY = 4096
    }
}
