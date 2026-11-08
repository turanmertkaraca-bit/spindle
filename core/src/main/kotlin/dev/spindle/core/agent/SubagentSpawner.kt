package dev.spindle.core.agent

import dev.spindle.core.event.AgentEvent
import dev.spindle.core.event.EventBus
import dev.spindle.core.model.Ids
import dev.spindle.core.model.Message
import dev.spindle.core.model.Part
import dev.spindle.core.model.Role
import dev.spindle.core.model.Session
import dev.spindle.core.model.SessionId
import dev.spindle.core.model.Usage
import dev.spindle.core.store.SessionStore
import dev.spindle.core.tool.SubagentResult
import dev.spindle.core.tool.SubagentSpec
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
            // A child that THREW still did work. Returning a bare "failed" threw
            // all of it away, so the parent had to redo the whole investigation.
            // Report what the child wrote before it died, plus the cause.
            store.session(childId)?.let { store.updateSession(it.copy(updatedAt = System.currentTimeMillis())) }
            val salvaged = runCatching {
                store.messages(childId)
                    .asReversed()
                    .firstOrNull { it.role == Role.ASSISTANT }
                    ?.parts
                    ?.filterIsInstance<Part.Text>()
                    ?.joinToString("") { it.text }
                    .orEmpty()
            }.getOrDefault("")
            val cause = e.message?.takeIf { it.isNotBlank() } ?: e::class.simpleName ?: "unknown error"
            val body = if (salvaged.isNotBlank()) {
                "subagent stopped with an error ($cause). Its findings up to that point:\n\n$salvaged"
            } else {
                "subagent failed: $cause"
            }
            return SubagentResult(childId, body, false)
        }

        val text = subagentReport(store, childId, finalMessage)
        // Return the full report; the loop's tool-output clip bounds it. An
        // artificial low cap here silently dropped findings the parent needed.
        val childUsage: Usage = store.messages(childId).fold(Usage()) { acc, m -> acc + m.usage }
        store.session(childId)?.let { store.updateSession(it.copy(updatedAt = System.currentTimeMillis())) }

        return SubagentResult(
            sessionId = childId,
            text = text,
            ok = finalMessage.error == null,
            childTokens = childUsage.totalTokens,
            costUsd = childUsage.costUsd,
        )
    }

    /**
     * The child's report, resilient to *where* the child stopped.
     *
     * The obvious implementation — read [finalMessage]'s text parts — loses the
     * entire subagent when the child's last message is a **tool call** rather
     * than prose. That is the normal shape of a run that hits its step budget
     * (the model calls a tool, the budget trips, the run ends), and it used to
     * return `"(subagent produced no text)"` for a child that had burned a
     * hundred thousand tokens of real research. The parent then had to redo all
     * of it, which is the worst possible failure mode for delegation.
     *
     * Order of preference:
     *  1. the final message's text, when it has any;
     *  2. the most recent earlier assistant message that has text (the child's
     *     last written findings), newest first;
     *  3. an explicit note that the child stopped without writing anything,
     *     including the reason so the parent can decide whether to retry.
     */
    private suspend fun subagentReport(
        store: SessionStore,
        childId: SessionId,
        finalMessage: Message,
    ): String {
        val own = finalMessage.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }
        if (own.length >= SUBSTANTIAL_REPORT_CHARS) return own

        // Gather every assistant text the child produced this run, newest first,
        // including its final message (which may hold a short narration).
        //
        // Newest-first alone is not enough. A child that runs out of steps
        // mid-investigation typically ends on one line like "Let me check the rest
        // of KeyStoreTest", and handing that back as "the report" is
        // indistinguishable from the child having failed — the owner's exact
        // complaint. So among the candidates we prefer the most SUBSTANTIAL text:
        // a real report is long, a progress line is not. If nothing is long
        // enough we still return the longest, clearly labelled as incomplete,
        // because a partial answer beats an empty one.
        val candidates = buildList {
            if (own.isNotBlank()) add(own)
            addAll(
                store.messages(childId)
                    .asReversed()
                    .filter { it.role == Role.ASSISTANT && it.id != finalMessage.id }
                    .mapNotNull { m ->
                        m.parts.filterIsInstance<Part.Text>()
                            .joinToString("") { it.text }
                            .takeIf { it.isNotBlank() }
                    },
            )
        }

        val best = candidates.firstOrNull { it.length >= SUBSTANTIAL_REPORT_CHARS }
            ?: candidates.maxByOrNull { it.length }
        if (!best.isNullOrBlank()) {
            return if (best.length >= SUBSTANTIAL_REPORT_CHARS) {
                "(the subagent stopped before writing a final answer — " +
                    "this is its most substantial output from the run)\n\n$best"
            } else {
                "(the subagent stopped without a written report — this is all it produced)\n\n$best"
            }
        }

        // Nothing substantial was ever written. Say why, so the parent can act on
        // it instead of guessing at an unexplained empty result.
        val why = finalMessage.error
            ?: finalMessage.parts.filterIsInstance<Part.Tool>().lastOrNull()?.result?.output
            ?: "it produced no text before stopping"
        return "(subagent produced no text: $why)"
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
        /**
         * How long a child's text must be to count as a real report rather than a
         * progress narration ("Let me check X"). 200 chars is roughly one
         * sentence plus a file path — below that it is almost always narration.
         */
        internal const val SUBSTANTIAL_REPORT_CHARS = 200

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
