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
 */
class SubagentSpawner(
    private val loop: AgentLoop,
    private val store: SessionStore,
    private val bus: EventBus,
    private val maxChildSteps: Int = 12,
    private val maxChildOutputChars: Int = 4_000,
) {
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

        bus.emit(AgentEvent.StateChanged(childId, dev.spindle.core.model.SessionState.RUNNING))
        val finalMessage = try {
            loop.prompt(childId, spec.prompt, modelRef, agent, budget = ContextBudget())
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            store.updateSession(child.copy(updatedAt = System.currentTimeMillis()))
            return SubagentResult(childId, "subagent failed: ${e.message}", false)
        }

        val text = finalMessage.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }
            .ifBlank { "(subagent produced no text)" }
        val clipped = if (text.length > maxChildOutputChars) text.take(maxChildOutputChars) + "…" else text
        val childUsage: Usage = store.messages(childId).fold(Usage()) { acc, m -> acc + m.usage }
        store.updateSession(child.copy(updatedAt = System.currentTimeMillis()))
        bus.emit(AgentEvent.StateChanged(childId, dev.spindle.core.model.SessionState.IDLE))

        return SubagentResult(
            sessionId = childId,
            text = clipped,
            ok = finalMessage.error == null,
            childTokens = childUsage.totalTokens,
            costUsd = childUsage.costUsd,
        )
    }
}
