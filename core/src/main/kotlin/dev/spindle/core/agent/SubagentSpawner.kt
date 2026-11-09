package dev.spindle.core.agent

import dev.spindle.core.event.AgentEvent
import dev.spindle.core.event.DeltaKind
import dev.spindle.core.event.EventBus
import dev.spindle.core.model.Ids
import dev.spindle.core.model.Message
import dev.spindle.core.model.Part
import dev.spindle.core.model.PartId
import dev.spindle.core.model.Role
import dev.spindle.core.model.Session
import dev.spindle.core.model.SessionId
import dev.spindle.core.model.Usage
import dev.spindle.core.store.SessionStore
import dev.spindle.core.tool.SubagentResult
import dev.spindle.core.tool.SubagentSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import java.util.concurrent.atomic.AtomicInteger

/**
 * Resolves what a child session should inherit from its parent. The parent
 * [Session]'s provider/model are chosen by the app and can be stale (the session
 * is created before a key/model is picked), so the most recent assistant
 * [Message] — which records the provider/model actually used — wins when present.
 */
internal object SubagentInheritance {
    fun resolve(parent: Session, messages: List<Message>): Pair<String, String>? {
        val recent = messages.asReversed().firstOrNull {
            it.role == Role.ASSISTANT && it.model != null && it.providerId != null
        }
        val provider = recent?.providerId ?: parent.providerId
        val model = recent?.model ?: parent.model
        return if (provider != null && model != null) provider to model else null
    }
}

/**
 * Spawns child sessions and runs them with the same loop. Child sessions are the
 * mechanism the `task` tool uses; they are normal sessions with a `parentId`, so
 * the UI can show them as nested runs and the store handles retention uniformly.
 *
 * **Concurrency is capped, and the cap is global.** A parent model can emit
 * several `task` calls in one turn; without a gate that runs every child at
 * once, each with its own provider stream, its own tool calls and its own
 * memory. The owner asked for 7. The [gate] is deliberately `companion`-scoped
 * rather than an instance field: a nested child carries `allowSubagents = false`
 * today, but making the permit a process-wide resource keeps the guarantee true
 * even if recursion is ever re-enabled, and it also covers the case where two
 * different `AgentLoop` instances (app + a future server) each spawn.
 */
class SubagentSpawner(
    private val loop: AgentLoop,
    private val store: SessionStore,
    private val bus: EventBus,
    /**
     * Tool-work steps a child may take.
     *
     * 12 was too few in practice: a child asked to map a subsystem spends its
     * budget on reads/greps and then ends mid-investigation with no written
     * report, which reads to the parent as "the subagent failed". 24 is enough
     * for a focused exploration, and the wind-down turn (see `AgentLoop`) means
     * the run still ends with prose when it does trip.
     */
    private val maxChildSteps: Int = DEFAULT_MAX_CHILD_STEPS,
    /** Ceiling on concurrently RUNNING child runs. 0 or less disables the gate. */
    private val maxConcurrent: Int = DEFAULT_MAX_CONCURRENT,
) {
    /**
     * The gate this instance actually uses. The **default** cap shares the
     * process-wide [sharedGate] so two loops (app + server, or a parent and a
     * nested child) cannot double the real limit. A caller that asks for a
     * non-default cap gets its own gate, which is what a test needs to assert
     * the cap in isolation without disturbing the shared counter.
     */
    private val gate: Gate =
        if (maxConcurrent == DEFAULT_MAX_CONCURRENT) sharedGate else Gate(maxConcurrent)

    suspend fun spawn(parentId: SessionId, spec: SubagentSpec, parentAgent: AgentConfig): SubagentResult {
        val parent = store.session(parentId) ?: return SubagentResult(parentId, "no parent session", false)
        val childId = SessionId(Ids.new("ses"))
        val now = System.currentTimeMillis()
        val inherited = SubagentInheritance.resolve(parent, store.messages(parentId))
        val child = Session(
            id = childId,
            title = spec.description.ifBlank { "subagent" },
            cwd = parent.cwd,
            createdAt = now,
            updatedAt = now,
            model = spec.model ?: inherited?.second ?: parent.model,
            providerId = inherited?.first ?: parent.providerId,
            agent = spec.agent ?: "general",
            parentId = parentId,
        )
        store.createSession(child)

        val modelRef = spec.model
            ?: inherited?.let { (provider, model) -> "$provider/$model" }
            ?: throw IllegalStateException("subagent has no model: parent has none and none was given")

        val agent = when (spec.agent?.lowercase()) {
            "explore" -> AgentConfig.explore()
            "general", null -> AgentConfig.general()
            else -> AgentConfig.general().copy(name = spec.agent)
        }.copy(
            allowSubagents = false, // no recursive fan-out
            maxSteps = maxChildSteps,
        )

        // Collect the child's evidence AS IT RUNS.
        //
        // This is the fix for the worst subagent bug: a long child crosses the
        // compaction threshold, and `Compaction.compact` replaces the head
        // messages' parts with an empty list. Any report built by walking the
        // store afterwards therefore sees only the retained tail — so the LONGER
        // a child worked, the LESS it reported. Watching the event stream captures
        // the findings before they can be folded away. It is also the only place
        // file changes exist at all (they arrive as `FileEdited`, never as a
        // transcript part), and the only record of a reasoning model's work.
        val evidence = EvidenceCollector(bus, childId)

        // The child loop owns the StateChanged(RUNNING) and terminal events; the
        // spawner only re-reads and touches updatedAt so it never clobbers the
        // state the loop persisted.
        //
        // The permit is taken around the RUN only — `createSession` above has
        // already happened, and holding a permit across it would not help. Taking
        // it inside the caller's coroutine means a user Stop cancels queued
        // children instead of leaving them wedged waiting for a permit.
        val finalMessage = try {
            if (maxConcurrent <= 0) {
                loop.prompt(childId, spec.prompt, modelRef, agent, budget = ContextBudget(), maintenance = false)
            } else {
                // Announce BEFORE waiting, so a queued child is visible. Without
                // this a child blocked on the cap looks exactly like a frozen run.
                if (gate.activeRuns >= gate.capacity) {
                    bus.emit(
                        AgentEvent.Progress(
                            sessionId = parentId,
                            message = "queued subagent '${child.title}' — " +
                                "${gate.activeRuns}/${gate.capacity} running",
                        ),
                    )
                }
                gate.withPermit {
                    loop.prompt(childId, spec.prompt, modelRef, agent, budget = ContextBudget(), maintenance = false)
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            // A child that THREW still did work. Report what it did, plus the
            // cause — never a bare "failed" that discards the whole run.
            store.session(childId)?.let { store.updateSession(it.copy(updatedAt = System.currentTimeMillis())) }
            val cause = e.message?.takeIf { it.isNotBlank() } ?: e::class.simpleName ?: "unknown error"
            val usage = childUsage(childId)
            val text = try {
                evidence.report(error = cause)
            } finally {
                evidence.stop()
            }
            return SubagentResult(childId, text, false, usage.totalTokens, usage.costUsd)
        }

        val usage = childUsage(childId)
        store.session(childId)?.let { store.updateSession(it.copy(updatedAt = System.currentTimeMillis())) }
        val text = try {
            evidence.report(store, finalMessage)
        } finally {
            evidence.stop()
        }

        return SubagentResult(
            sessionId = childId,
            text = text,
            ok = finalMessage.error == null,
            childTokens = usage.totalTokens,
            costUsd = usage.costUsd,
        )
    }

    /** Rolled-up child usage; never throws (a store hiccup must not lose a report). */
    private suspend fun childUsage(childId: SessionId): Usage =
        runCatching { store.messages(childId).fold(Usage()) { acc, m -> acc + m.usage } }
            .getOrDefault(Usage())

    /**
     * Subscribes to the child's event stream and keeps everything the report may
     * need, **before** compaction can rewrite the store.
     *
     * Three bugs this exists to fix, all of which independently produced "250k
     * tokens and no report":
     *
     * 1. **Compaction erases the evidence.** `Compaction.compact` replaces head
     *    messages' parts with an empty list, so a post-hoc store walk sees only the
     *    retained tail. The longer a child worked, the less it reported — exactly
     *    backwards.
     * 2. **File changes are not in the transcript.** They arrive as
     *    `AgentEvent.FileEdited`. Without collecting them, a child that wrote the
     *    files and said nothing looked like it had done nothing.
     * 3. **Reasoning models never emit text.** `deepseek-r1`/`o1` stream
     *    `ReasoningDelta`, so the reasoning *is* the record of the work.
     *
     * Bounded: at most [MAX_TEXT_PARTS] streamed blobs per channel, so a
     * pathological child cannot grow the parent's memory without limit.
     */
    private class EvidenceCollector(bus: EventBus, private val childId: SessionId) {
        private val texts = ArrayDeque<String>()
        private val reasonings = ArrayDeque<String>()
        private val toolUses = ArrayList<SubagentReport.ToolUse>()
        private val files = LinkedHashMap<String, SubagentReport.FileChange>()
        private var lastError: String? = null
        private var job: Job? = null

        init {
            job = CoroutineScope(Dispatchers.Unconfined).launch {
                bus.events.collect { event ->
                    if (event.sessionId != childId) return@collect
                    when (event) {
                        is AgentEvent.PartDelta -> when (event.kind) {
                            DeltaKind.TEXT -> append(texts, event.delta)
                            DeltaKind.REASONING -> append(reasonings, event.delta)
                        }
                        is AgentEvent.PartUpdated -> when (val part = event.part) {
                            is Part.Text -> appendWhole(texts, part.text)
                            is Part.Reasoning -> appendWhole(reasonings, part.text)
                            is Part.Tool -> toolUses += SubagentReport.ToolUse(
                                name = part.call.name,
                                ok = part.result?.isError == false,
                                detail = part.result?.output?.lineSequence()?.firstOrNull()?.trim()?.take(120),
                            )
                            else -> Unit
                        }
                        is AgentEvent.FileEdited -> {
                            val path = event.edit.path
                            // Keep the FIRST record of a path: "created" comes from
                            // the first edit (removed == 0) and a later overwrite
                            // must not clear it.
                            files.putIfAbsent(
                                path,
                                SubagentReport.FileChange(path = path, created = event.edit.removed == 0),
                            )
                        }
                        is AgentEvent.Error -> lastError = event.message
                        else -> Unit
                    }
                }
            }
        }

        fun stop() {
            job?.cancel()
            job = null
        }

        /**
         * The report, preferring the live capture and falling back to the store.
         *
         * The store fallback matters for a child that failed before emitting
         * anything (a provider error on turn one): the transcript may still hold a
         * usable message even though no events were seen.
         */
        suspend fun report(
            fallbackStore: SessionStore? = null,
            finalMessage: Message? = null,
            error: String? = null,
        ): String {
            val fromStore = if (fallbackStore != null) {
                runCatching {
                    SubagentReport.evidenceFrom(
                        fallbackStore.messages(childId),
                        error = error ?: lastError,
                    )
                }.getOrNull()
            } else {
                null
            }
            // The LIVE CAPTURE IS AUTHORITATIVE for prose, and the store is only a
            // fallback. The store may already have been compacted (which erases the
            // child's findings), so preferring it would resurrect exactly the bug this
            // class exists to fix. The store is still useful when the child failed
            // before emitting anything.
            val capturedFinal = buildList {
                texts.lastOrNull()?.let { add(Part.Text(PartId("captured-text"), it)) }
                reasonings.lastOrNull()?.let { add(Part.Reasoning(PartId("captured-reasoning"), it)) }
            }
            val finalParts = capturedFinal.ifEmpty { fromStore?.finalParts.orEmpty() }
                .ifEmpty { finalMessage?.parts.orEmpty() }

            // Earlier text is listed NEWEST FIRST, matching how the report picks the
            // most recent substantial output. Everything captured during the run goes
            // in before anything read from the store, so live evidence always wins.
            val capturedEarlier = buildList {
                texts.dropLast(1).asReversed().forEach { add(listOf(Part.Text(PartId("t"), it))) }
                reasonings.dropLast(1).asReversed().forEach { add(listOf(Part.Reasoning(PartId("r"), it))) }
            }

            val merged = SubagentReport.Evidence(
                finalParts = finalParts,
                earlierAssistantParts = capturedEarlier + fromStore?.earlierAssistantParts.orEmpty(),
                toolUses = toolUses.ifEmpty { fromStore?.toolUses.orEmpty() },
                fileChanges = files.values.toList(),
                error = error ?: lastError ?: fromStore?.error,
            )
            return SubagentReport.build(merged)
        }

        private fun append(target: ArrayDeque<String>, delta: String) {
            if (delta.isEmpty()) return
            if (target.isNotEmpty() && target.size >= MAX_TEXT_PARTS) {
                // Merge into the newest entry so a streamed answer stays one blob
                // instead of growing the deque without bound.
                target[target.size - 1] = target.last() + delta
            } else {
                target.addLast(delta)
            }
        }

        private fun appendWhole(target: ArrayDeque<String>, text: String) {
            if (text.isBlank()) return
            // A PartUpdated carries the FULL part text, and the delta stream has
            // already accumulated it. Only record it when nothing streamed (some
            // providers send a completed part with no deltas at all).
            if (target.isEmpty()) target.addLast(text)
        }

        private companion object {
            /** Bound on retained streamed blobs per channel. */
            const val MAX_TEXT_PARTS = 24
        }
    }

    /**
     * Process-wide cap on concurrently running child runs.
     *
     * A plain `Semaphore` would do, but the UI wants to say "3/7 running" and a
     * test wants a high-water mark, so this wraps the counter explicitly.
     *
     * `withPermit`'s release is in a `finally`, so cancellation (a user Stop)
     * cannot leak a permit and wedge every later run.
     */
    internal class Gate(val capacity: Int) {
        private val semaphore = Semaphore(capacity)
        private val active = AtomicInteger(0)

        /** Runs currently holding a permit. */
        val activeRuns: Int get() = active.get()

        /** Highest [activeRuns] ever observed; the assertion a test wants. */
        val highWaterMark = AtomicInteger(0)

        suspend fun <T> withPermit(block: suspend () -> T): T {
            semaphore.acquire()
            val now = active.incrementAndGet()
            highWaterMark.accumulateAndGet(now) { a, b -> maxOf(a, b) }
            try {
                return block()
            } finally {
                active.decrementAndGet()
                semaphore.release()
            }
        }
    }

    companion object {
        /** Default tool-work steps for a child run; see [maxChildSteps]. */
        const val DEFAULT_MAX_CHILD_STEPS = 24

        /**
         * Owner's requested ceiling. Seven concurrent children is enough to fan
         * a review across a repo while staying inside provider rate limits and
         * the device's memory budget; see `docs/analysis/arch/SUBAGENTS.md`.
         */
        const val DEFAULT_MAX_CONCURRENT = 7

        /**
         * Shared, NOT per-instance: a per-instance semaphore gives N×(cap) as
         * soon as two spawners exist, and would not bound recursion.
         */
        internal val sharedGate = Gate(DEFAULT_MAX_CONCURRENT)
    }
}
