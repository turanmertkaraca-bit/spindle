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
import dev.spindle.core.model.TodoItem
import dev.spindle.core.model.ToolResult
import dev.spindle.core.model.ToolState
import dev.spindle.core.model.Usage
import dev.spindle.core.provider.ProviderEvent
import dev.spindle.core.provider.ProviderRegistry
import dev.spindle.core.store.SessionStore
import dev.spindle.core.store.SnapshotStore
import dev.spindle.core.tool.AllowAllPolicy
import dev.spindle.core.tool.ApprovalDecision
import dev.spindle.core.tool.ApprovalPolicy
import dev.spindle.core.tool.ApprovalRequest
import dev.spindle.core.tool.SubagentResult
import dev.spindle.core.tool.SubagentRunner
import dev.spindle.core.tool.SubagentSpec
import dev.spindle.core.tool.ToolContext
import dev.spindle.core.tool.ToolEdit
import dev.spindle.core.tool.ToolOutcome
import dev.spindle.core.tool.ToolProgress
import dev.spindle.core.tool.ToolRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The agent loop. Assemble the conversation, stream the model, run the tools it
 * asks for, feed results back, repeat until the model stops requesting tools.
 *
 * Hardened with: step budget, transient-failure retries, cooperative cancellation,
 * context trimming/compaction, and subagent delegation via the `task` tool.
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
    /** Subagent override — when null, the loop spawns child AgentLoops on demand. */
    private val subagents: SubagentRunner? = null,
    /** Per-call allow/ask/deny decision layer; [permissions] is the ASK transport. */
    private val approval: ApprovalPolicy = AllowAllPolicy,
    /** Pre-edit snapshots; null disables the record-and-revert capability. */
    private val snapshots: SnapshotStore? = null,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun toolContext(sessionId: SessionId, gate: PermissionGate = permissions): ToolContext {
        val session = store.session(sessionId) ?: error("no session")
        return LoopToolContext(
            sessionId = sessionId,
            cwd = Path.of(session.cwd),
            session = session,
            snapshots = snapshots,
            gate = gate,
            questionGate = questions,
            bus = bus,
            store = store,
            approval = approval,
            policyApproved = false,
            subagentRunner = { spec -> runSubagent(sessionId, spec, AgentConfig()) },
        )
    }

    /** Run a prompt with a primary agent selected by name (build/plan/explore/general). */
    suspend fun prompt(
        sessionId: SessionId,
        userText: String,
        modelRef: String,
        agentName: String,
        onPermission: PermissionGate = permissions,
        budget: ContextBudget = ContextBudget(),
        rules: String? = null,
    ): Message = prompt(sessionId, userText, modelRef, AgentConfig.byName(agentName), onPermission, budget, rules)

    suspend fun prompt(
        sessionId: SessionId,
        userText: String,
        modelRef: String,
        agent: AgentConfig = AgentConfig(),
        onPermission: PermissionGate = permissions,
        budget: ContextBudget = ContextBudget(),
        /** Extra host-supplied rules appended after any AGENTS.md content. */
        rules: String? = null,
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

            // Subagents that must not recurse can't even be offered the `task` tool.
            val denied = if (agent.allowSubagents) agent.denyTools else agent.denyTools + "task"
            val active = tools.without(denied)
            val system = systemPrompt(agent.systemPrompt, Path.of(session.cwd), rules)
            var step = 0
            var compacted = false
            while (true) {
                currentCoroutineContext().ensureActive()
                step++

                // ---- context policy: trim/compact before we build the request ----
                val decision = evaluateOverflow(sessionId, model.contextWindow, active, system)
                if (decision.action != OverflowAction.NONE) {
                    bus.emit(AgentEvent.StateChanged(sessionId, SessionState.RUNNING))
                }
                when (decision.action) {
                    OverflowAction.TRIM -> Compaction.trim(store, sessionId)
                    OverflowAction.COMPACT -> Compaction.compact(store, sessionId, modelRef, providers, bus)
                    OverflowAction.NONE -> Unit
                }
                budgetCheck(budget, store, sessionId)

                val history = store.messages(sessionId)
                val wire = Wire.toWire(history)
                val specs = if (model.supportsTools) active.specs else emptyList()

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

                val request = Wire.request(
                    model = model.id,
                    system = system,
                    messages = wire,
                    tools = specs,
                    agent = agent,
                    sessionHint = sessionId.value,
                )

                // ---- stream with retry on transient failures ----
                try {
                    Retry.withRetry(
                        maxRetries = agent.maxRetries,
                        onRetry = { attempt, err ->
                            bus.emit(
                                AgentEvent.Error(
                                    sessionId,
                                    "retrying (${attempt}/${agent.maxRetries}) after: ${err.message}",
                                ),
                            )
                        },
                    ) {
                        text.setLength(0); reasoning.setLength(0); calls.clear()
                        failure = null
                        provider.stream(request).collect { ev ->
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
                                    bus.emit(AgentEvent.ToolCallStarted(sessionId, assistant.id.value, ev.index, ev.name))
                                }
                                is ProviderEvent.ToolCallArgsDelta ->
                                    calls.getOrPut(ev.index) { MutableToolCall("call_${ev.index}", "unknown") }.args.append(ev.argsDelta)
                                is ProviderEvent.ToolCallEnd -> Unit
                                is ProviderEvent.UsageEvent -> usage = usage + ev.usage
                                is ProviderEvent.Finished -> finish = ev.reason
                                is ProviderEvent.Failure -> failure = ev.message
                            }
                        }
                        if (failure != null) throw ProviderFailure(failure!!)
                    }
                } catch (e: ProviderFailure) {
                    val errored = assistant.copy(
                        error = e.message,
                        finish = FinishReason.ERROR,
                        usage = usage,
                        parts = text.takeIf { it.isNotEmpty() }?.let { listOf(Part.Text(textPartId, it.toString())) } ?: emptyList(),
                    )
                    store.updateMessage(errored)
                    bus.emit(AgentEvent.Error(sessionId, e.message ?: "provider error"))
                    bus.emit(AgentEvent.UsageUpdated(sessionId, store.messages(sessionId).fold(Usage()) { acc, m -> acc + m.usage }))
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
                if (sessionId.let { store.session(it) } != null) store.updateSession(session.copy(updatedAt = clock()))
                val sessionUsage = store.messages(sessionId).fold(Usage()) { acc, m -> acc + m.usage }
                bus.emit(AgentEvent.UsageUpdated(sessionId, sessionUsage))

                if (toolCalls.isEmpty()) break
                if (agent.maxSteps != null && step >= agent.maxSteps) break

                for (part in finalized.parts.filterIsInstance<Part.Tool>()) {
                    currentCoroutineContext().ensureActive()
                    executeTool(sessionId, finalized.id, part, session.cwd, onPermission, agent)
                }
            }

            bus.emit(AgentEvent.StateChanged(sessionId, SessionState.IDLE))
            return store.latestMessage(sessionId)!!
        } catch (e: CancellationException) {
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

    private suspend fun evaluateOverflow(
        sessionId: SessionId,
        contextWindow: Int,
        active: ToolRegistry,
        system: String,
    ): OverflowDecision {
        val history = store.messages(sessionId)
        val open = history.lastOrNull()?.parts?.filterIsInstance<Part.Tool>()
            ?.any { it.state == ToolState.PENDING || it.state == ToolState.RUNNING } ?: false
        val sysChars = system.length
        val msgChars = history.sumOf { m ->
            m.parts.sumOf { p ->
                when (p) {
                    is Part.Text -> p.text.length
                    is Part.Reasoning -> p.text.length
                    is Part.Tool -> p.call.argumentsJson.length + (p.result?.output?.length ?: 0)
                    else -> 16
                }
            }
        }
        val schemaChars = active.specs.sumOf { it.parametersJson.length + it.description.length }
        val est = TokenEstimator.estimate("x".repeat(sysChars), listOf("x".repeat(msgChars)), schemaChars)
        val alreadyCompacted = history.any { it.parts.filterIsInstance<Part.Text>().any { p -> p.text.startsWith(COMPACT_MARKER) } }
        return Overflow.decide(est, contextWindow, open, alreadyCompacted)
    }

    private suspend fun budgetCheck(budget: ContextBudget, store: SessionStore, sessionId: SessionId) {
        val max = budget.maxCostUsd ?: return
        val spent = store.messages(sessionId).sumOf { it.usage.costUsd }
        if (spent >= max) throw IllegalStateException("session cost budget exceeded: $spent >= $max")
    }

    private suspend fun executeTool(
        sessionId: SessionId,
        messageId: MessageId,
        part: Part.Tool,
        cwd: String,
        gate: PermissionGate,
        agent: AgentConfig,
    ) {
        val tool = tools.get(part.call.name)
        updatePart(sessionId, messageId, part.copy(state = ToolState.RUNNING))

        if (tool == null) {
            finishTool(
                sessionId, messageId,
                part.copy(state = ToolState.ERROR, result = ToolResult(part.call.id, "Unknown tool: ${part.call.name}", isError = true)),
            )
            return
        }

        if (part.call.name in agent.denyTools || (!agent.allowSubagents && part.call.name == "task")) {
            finishTool(
                sessionId, messageId,
                part.copy(
                    state = ToolState.ERROR,
                    result = ToolResult(
                        part.call.id,
                        "Tool not permitted for agent ${agent.name}: ${part.call.name}",
                        isError = true,
                    ),
                ),
            )
            return
        }

        val input: JsonObject = runCatching { json.parseToJsonElement(part.call.argumentsJson) as JsonObject }
            .getOrElse { JsonObject(emptyMap()) }

        val request = ApprovalRequest(
            sessionId = sessionId.value,
            tool = part.call.name,
            detail = approvalDetail(input),
            pattern = approvalPattern(input),
        )
        var policyApproved = false
        when (approval.decide(request)) {
            ApprovalDecision.DENY -> {
                finishTool(sessionId, messageId, deniedPart(part, "denied by approval policy"))
                return
            }
            ApprovalDecision.ASK -> {
                val ok = gate.request(request.tool, request.detail, request.pattern)
                approval.remember(request, if (ok) ApprovalDecision.ALLOW else ApprovalDecision.DENY)
                if (!ok) {
                    finishTool(sessionId, messageId, deniedPart(part, "denied by user"))
                    return
                }
                policyApproved = true
            }
            ApprovalDecision.ALLOW -> policyApproved = true
        }

        val outcome: ToolOutcome = try {
            val ctx = LoopToolContext(
                sessionId = sessionId,
                cwd = Path.of(cwd),
                session = store.session(sessionId) ?: error("no session"),
                snapshots = snapshots,
                gate = gate,
                questionGate = questions,
                bus = bus,
                store = store,
                approval = approval,
                policyApproved = policyApproved,
                subagentRunner = { spec -> runSubagent(sessionId, spec, agent) },
            )
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

        val reports = if (outcome.edits.isNotEmpty()) {
            outcome.edits
        } else {
            listOfNotNull(outcome.edit?.let { ToolEdit(it, outcome.snapshotId) })
        }
        for (report in reports) {
            val edit = report.edit.copy(messageId = messageId)
            bus.emit(AgentEvent.FileEdited(sessionId, edit))
            report.snapshotId?.let {
                bus.emit(AgentEvent.SnapshotCreated(sessionId, it, report.snapshotPath ?: edit.path))
            }
        }
    }

    private fun deniedPart(part: Part.Tool, message: String): Part.Tool = part.copy(
        state = ToolState.ERROR,
        result = ToolResult(part.call.id, message, isError = true),
    )

    private fun approvalDetail(input: JsonObject): String {
        for (key in listOf("command", "path", "url", "patchText")) {
            val value = (input[key] as? JsonPrimitive)?.contentOrNull
            if (!value.isNullOrBlank()) return if (value.length > 280) value.take(280) else value
        }
        return input.toString().take(280)
    }

    private fun approvalPattern(input: JsonObject): String? {
        (input["command"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { command ->
            return command.trim().split(Regex("\\s+")).firstOrNull()
        }
        (input["path"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { return it }
        return null
    }

    /**
     * The system prompt actually sent: the agent's base prompt plus project rules
     * read from `<cwd>/AGENTS.md` and any host-supplied [extraRules].
     */
    private fun systemPrompt(base: String, cwd: Path, extraRules: String?): String {
        val builder = StringBuilder(base)
        val projectRules = readProjectRules(cwd)
        if (projectRules.isNotBlank()) {
            builder.append("\n\n## Project rules\n\n").append(projectRules)
        }
        if (!extraRules.isNullOrBlank()) {
            builder.append("\n\n## Additional rules\n\n").append(extraRules)
        }
        return builder.toString()
    }

    private fun readProjectRules(cwd: Path): String = try {
        val base = cwd.toAbsolutePath().normalize()
        val file = base.resolve(AGENTS_FILE).normalize()
        if (!file.startsWith(base) || !Files.isRegularFile(file)) {
            ""
        } else {
            val text = Files.readString(file, StandardCharsets.UTF_8)
            if (text.length > MAX_RULES_CHARS) text.take(MAX_RULES_CHARS) + "\n…[truncated]" else text
        }
    } catch (_: Exception) {
        ""
    }

    /** Spawn (or delegate) a subagent and return its final text. */
    private suspend fun runSubagent(parentId: SessionId, spec: SubagentSpec, parentAgent: AgentConfig): SubagentResult {
        subagents?.let { return it.run(toolContext(parentId), spec) }
        return SubagentSpawner(this, store, bus).spawn(parentId, spec, parentAgent)
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

    private class ProviderFailure(override val message: String) : RuntimeException(message)

    private class LoopToolContext(
        override val sessionId: SessionId,
        override val cwd: Path,
        override val session: dev.spindle.core.model.Session,
        override val snapshots: SnapshotStore?,
        private val gate: PermissionGate,
        private val questionGate: QuestionGate,
        private val bus: EventBus,
        private val store: SessionStore,
        private val approval: ApprovalPolicy,
        private val policyApproved: Boolean,
        private val subagentRunner: suspend (SubagentSpec) -> SubagentResult,
    ) : ToolContext {
        private val aborted = AtomicBoolean(false)

        override suspend fun requestPermission(tool: String, detail: String, pattern: String?): Boolean {
            if (policyApproved) return true
            val request = ApprovalRequest(sessionId.value, tool, detail, pattern)
            return when (approval.decide(request)) {
                ApprovalDecision.ALLOW -> true
                ApprovalDecision.DENY -> {
                    bus.emit(AgentEvent.Error(sessionId, "denied: $tool"))
                    false
                }
                ApprovalDecision.ASK -> {
                    val approved = gate.request(tool, detail, pattern)
                    approval.remember(request, if (approved) ApprovalDecision.ALLOW else ApprovalDecision.DENY)
                    if (!approved) bus.emit(AgentEvent.Error(sessionId, "denied: $tool"))
                    approved
                }
            }
        }

        override suspend fun ask(question: String, options: List<String>, multiple: Boolean): List<String> {
            bus.emit(AgentEvent.QuestionAsked(sessionId, Ids.new("q"), question, options, multiple))
            return questionGate.ask(sessionId.value, question, options, multiple)
        }

        override fun emit(event: ToolProgress) {
            bus.emit(AgentEvent.Progress(sessionId, event.message, event.fraction))
        }

        override suspend fun checkAborted() {
            if (aborted.get()) throw CancellationException("tool aborted")
            currentCoroutineContext().ensureActive()
        }
        override suspend fun setTodos(todos: List<TodoItem>) = store.setTodos(sessionId, todos)
        override suspend fun todos(): List<TodoItem> = store.todos(sessionId)
        override suspend fun subagent(spec: SubagentSpec): SubagentResult = subagentRunner(spec)
    }

    internal companion object {
        const val COMPACT_MARKER = "[compacted]"
        const val AGENTS_FILE = "AGENTS.md"
        const val MAX_RULES_CHARS = 8_192
    }
}
