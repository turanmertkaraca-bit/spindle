package dev.spindle.core.agent

import dev.spindle.core.event.AgentEvent
import dev.spindle.core.event.DeltaKind
import dev.spindle.core.event.EventBus
import dev.spindle.core.model.FinishReason
import dev.spindle.core.model.Ids
import dev.spindle.core.model.Message
import dev.spindle.core.model.MessageId
import dev.spindle.core.model.Part
import dev.spindle.core.model.PartId
import dev.spindle.core.model.Role
import dev.spindle.core.model.SessionId
import dev.spindle.core.model.SessionState
import dev.spindle.core.model.ToolCall
import dev.spindle.core.model.ToolResult
import dev.spindle.core.model.ToolState
import dev.spindle.core.model.Usage
import dev.spindle.core.provider.ProviderEvent
import dev.spindle.core.provider.ProviderRegistry
import dev.spindle.core.store.SessionStore
import dev.spindle.core.tool.ToolContext
import dev.spindle.core.tool.ToolOutcome
import dev.spindle.core.tool.ToolProgress
import dev.spindle.core.tool.ToolRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.nio.file.Path

/**
 * The agent loop. Assemble the conversation, stream the model, run the tools it
 * asks for, feed results back, repeat until the model stops requesting tools.
 */
class AgentLoop(
    private val providers: ProviderRegistry,
    private val tools: ToolRegistry,
    private val store: SessionStore,
    private val bus: EventBus,
    private val permissions: PermissionGate = AllowAll,
    private val questions: QuestionGate = QuestionGate { _, _, _, _ -> emptyList() },
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val maxToolOutputChars: Int = 60_000,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun prompt(
        sessionId: SessionId,
        userText: String,
        modelRef: String,
        agent: AgentConfig = AgentConfig(),
        onPermission: PermissionGate = permissions,
    ): Message {
        val (provider, model) = providers.resolve(modelRef)
            ?: throw IllegalArgumentException("Unknown model: $modelRef")
        val session = store.session(sessionId)
            ?: throw IllegalArgumentException("Unknown session: $sessionId")

        bus.emit(AgentEvent.StateChanged(sessionId, SessionState.RUNNING))
        try {
            store.appendMessage(
                Message(
                    id = MessageId(Ids.new("msg")),
                    sessionId = sessionId,
                    role = Role.USER,
                    parts = listOf(Part.Text(PartId(Ids.new("prt")), userText)),
                    createdAt = clock(),
                ),
            )

            var step = 0
            while (true) {
                step++
                val history = store.messages(sessionId)
                val wire = Wire.toWire(history)
                val specs = if (model.supportsTools) tools.specs else emptyList()

                val assistant = Message(
                    id = MessageId(Ids.new("msg")),
                    sessionId = sessionId,
                    role = Role.ASSISTANT,
                    parts = emptyList(),
                    createdAt = clock(),
                    model = model.id,
                    providerId = provider.id,
                    agent = agent.name,
                )
                store.appendMessage(assistant)
                bus.emit(AgentEvent.MessageCreated(sessionId, assistant.id.value, "assistant"))

                val textPartId = PartId(Ids.new("prt"))
                val reasoningPartId = PartId(Ids.new("prt"))
                val text = StringBuilder()
                val reasoning = StringBuilder()
                val calls = LinkedHashMap<Int, MutableToolCall>()
                var usage = Usage()
                var finish = FinishReason.UNKNOWN
                var failure: String? = null

                provider.stream(
                    Wire.request(
                        model = model.id,
                        system = agent.systemPrompt,
                        messages = wire,
                        tools = specs,
                        agent = agent,
                        sessionHint = sessionId.value,
                    ),
                ).collect { ev ->
                    when (ev) {
                        is ProviderEvent.TextDelta -> {
                            text.append(ev.text)
                            bus.emit(AgentEvent.PartDelta(sessionId, assistant.id.value, textPartId, DeltaKind.TEXT, ev.text))
                        }
                        is ProviderEvent.ReasoningDelta -> {
                            reasoning.append(ev.text)
                            bus.emit(AgentEvent.PartDelta(sessionId, assistant.id.value, reasoningPartId, DeltaKind.REASONING, ev.text))
                        }
                        is ProviderEvent.ToolCallStart -> {
                            calls.getOrPut(ev.index) { MutableToolCall(ev.id, ev.name) }
                        }
                        is ProviderEvent.ToolCallArgsDelta -> {
                            calls.getOrPut(ev.index) { MutableToolCall("call_${ev.index}", "unknown") }
                                .args.append(ev.argsDelta)
                        }
                        is ProviderEvent.ToolCallEnd -> Unit
                        is ProviderEvent.UsageEvent -> usage = usage + ev.usage
                        is ProviderEvent.Finished -> finish = ev.reason
                        is ProviderEvent.Failure -> failure = ev.message
                    }
                }

                if (failure != null) {
                    val errored = assistant.copy(
                        error = failure,
                        finish = FinishReason.ERROR,
                        usage = usage,
                        parts = text.takeIf { it.isNotEmpty() }
                            ?.let { listOf(Part.Text(textPartId, it.toString())) }
                            ?: emptyList(),
                    )
                    store.updateMessage(errored)
                    bus.emit(AgentEvent.Error(sessionId, failure!!))
                    bus.emit(AgentEvent.StateChanged(sessionId, SessionState.ERROR))
                    return errored
                }

                val toolCalls = calls.toSortedMap().values.map { it.toCall() }
                val parts = buildList {
                    if (reasoning.isNotEmpty()) add(Part.Reasoning(reasoningPartId, reasoning.toString()))
                    if (text.isNotEmpty()) add(Part.Text(textPartId, text.toString()))
                    toolCalls.forEach { add(Part.Tool(PartId(Ids.new("prt")), it, ToolState.PENDING)) }
                }
                val cost = Wire.cost(model, usage)
                val finalized = assistant.copy(
                    parts = parts,
                    usage = usage.copy(costUsd = cost),
                    finish = if (toolCalls.isEmpty()) finish else FinishReason.TOOL_CALLS,
                )
                store.updateMessage(finalized)
                parts.forEach { bus.emit(AgentEvent.PartUpdated(sessionId, finalized.id.value, it)) }

                if (toolCalls.isEmpty()) break
                if (agent.maxSteps != null && step >= agent.maxSteps) break

                for (part in finalized.parts.filterIsInstance<Part.Tool>()) {
                    executeTool(sessionId, finalized.id, part, session.cwd, onPermission)
                }
            }

            bus.emit(AgentEvent.StateChanged(sessionId, SessionState.IDLE))
            return store.latestMessage(sessionId)!!
        } catch (e: kotlinx.coroutines.CancellationException) {
            store.latestMessage(sessionId)?.let {
                store.updateMessage(it.copy(finish = FinishReason.ERROR, error = "aborted"))
            }
            bus.emit(AgentEvent.StateChanged(sessionId, SessionState.IDLE))
            throw e
        } catch (e: Throwable) {
            bus.emit(AgentEvent.Error(sessionId, e.message ?: e.toString()))
            bus.emit(AgentEvent.StateChanged(sessionId, SessionState.ERROR))
            throw e
        }
    }

    private suspend fun executeTool(
        sessionId: SessionId,
        messageId: MessageId,
        part: Part.Tool,
        cwd: String,
        gate: PermissionGate,
    ) {
        val tool = tools.get(part.call.name)
        val running = part.copy(state = ToolState.RUNNING)
        updatePart(sessionId, messageId, running)

        if (tool == null) {
            finishTool(
                sessionId, messageId,
                part.copy(state = ToolState.ERROR, result = ToolResult(part.call.id, "Unknown tool: ${part.call.name}", isError = true)),
            )
            return
        }

        val outcome: ToolOutcome = try {
            val ctx = LoopToolContext(sessionId, Path.of(cwd), gate, questions, bus) { message ->
                bus.emit(AgentEvent.StateChanged(sessionId, SessionState.RUNNING))
            }
            val input: JsonObject = runCatching { json.parseToJsonElement(part.call.argumentsJson) as JsonObject }
                .getOrElse { JsonObject(emptyMap()) }
            tool.run(input, ctx)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            ToolOutcome("Tool ${part.call.name} failed: ${e.message}", isError = true)
        }

        val clipped = if (outcome.output.length > maxToolOutputChars) {
            outcome.output.take(maxToolOutputChars) + "\n…[truncated ${outcome.output.length - maxToolOutputChars} chars]"
        } else {
            outcome.output
        }
        finishTool(
            sessionId, messageId,
            part.copy(
                state = if (outcome.isError) ToolState.ERROR else ToolState.DONE,
                result = ToolResult(part.call.id, clipped, outcome.isError, outcome.diff, outcome.metadata),
            ),
        )
    }

    private suspend fun finishTool(sessionId: SessionId, messageId: MessageId, part: Part.Tool) {
        updatePart(sessionId, messageId, part)
        part.result?.let { bus.emit(AgentEvent.ToolFinished(sessionId, messageId.value, part.id, it)) }
    }

    private suspend fun updatePart(sessionId: SessionId, messageId: MessageId, part: Part) {
        val message = store.message(sessionId, messageId) ?: return
        val replaced = message.parts.map { if (it.id == part.id) part else it }
        store.updateMessage(message.copy(parts = replaced))
        bus.emit(AgentEvent.PartUpdated(sessionId, messageId.value, part))
    }

    private class MutableToolCall(val id: String, val name: String) {
        val args = StringBuilder()
        fun toCall() = ToolCall(id, name, args.toString().ifBlank { "{}" })
    }

    private class LoopToolContext(
        override val sessionId: SessionId,
        override val cwd: Path,
        private val gate: PermissionGate,
        private val questionGate: QuestionGate,
        private val bus: EventBus,
        private val onProgress: (String) -> Unit,
    ) : ToolContext {
        override suspend fun requestPermission(tool: String, detail: String, pattern: String?): Boolean {
            val approved = gate.request(tool, detail, pattern)
            if (!approved) bus.emit(AgentEvent.Error(sessionId, "denied: $tool"))
            return approved
        }

        override suspend fun ask(question: String, options: List<String>, multiple: Boolean): List<String> {
            bus.emit(AgentEvent.QuestionAsked(sessionId, Ids.new("q"), question, options, multiple))
            return questionGate.ask(sessionId.value, question, options, multiple)
        }

        override fun emit(event: ToolProgress) = onProgress(event.message)
        override fun checkAborted() = Unit
    }
}
