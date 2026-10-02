package dev.spindle.core.agent

import dev.spindle.core.event.AgentEvent
import dev.spindle.core.event.EventBus
import dev.spindle.core.model.FinishReason
import dev.spindle.core.model.Message
import dev.spindle.core.model.MessageId
import dev.spindle.core.model.Part
import dev.spindle.core.model.Role
import dev.spindle.core.model.Session
import dev.spindle.core.model.SessionId
import dev.spindle.core.model.SessionState
import dev.spindle.core.model.ToolState
import dev.spindle.core.model.Usage
import dev.spindle.core.provider.ChatRequest
import dev.spindle.core.provider.ModelInfo
import dev.spindle.core.provider.Provider
import dev.spindle.core.provider.ProviderEvent
import dev.spindle.core.provider.SimpleProviderRegistry
import dev.spindle.core.provider.ToolSpec
import dev.spindle.core.store.InMemorySessionStore
import dev.spindle.core.tool.Tool
import dev.spindle.core.tool.ToolContext
import dev.spindle.core.tool.ToolOutcome
import dev.spindle.core.tool.ToolRegistry
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AgentLoopTest {

    private class ScriptedProvider(vararg turns: List<ProviderEvent>) : Provider {
        override val id = "fake"
        private val turns = turns.toMutableList()
        val requests = mutableListOf<ChatRequest>()
        override suspend fun models() = listOf(
            ModelInfo(providerId = id, id = "fake-1", label = "fake", supportsTools = true, supportsReasoning = true),
        )
        override fun stream(request: ChatRequest): Flow<ProviderEvent> {
            requests += request
            return turns.removeAt(0).asFlow()
        }
    }

    private class EchoTool : Tool {
        override val spec = ToolSpec(
            name = "echo",
            description = "echoes its input",
            parametersJson = """{"type":"object","properties":{"text":{"type":"string"}},"required":["text"]}""",
        )
        override suspend fun run(input: JsonObject, ctx: ToolContext): ToolOutcome =
            ToolOutcome("echo: " + (input["text"]?.toString() ?: "(none)"))
    }

    /** A tool that exists only so its spec shows up in the offered request. */
    private class NamedTool(name: String) : Tool {
        override val spec = ToolSpec(
            name = name,
            description = "test tool $name",
            parametersJson = """{"type":"object","properties":{}}""",
        )
        override suspend fun run(input: JsonObject, ctx: ToolContext): ToolOutcome = ToolOutcome("ok")
    }

    /** A tool that advances an injected clock so the loop's timing is observable. */
    private class ClockAdvancingTool(private val onRun: () -> Unit) : Tool {
        override val spec = ToolSpec(
            name = "timed",
            description = "advances the clock",
            parametersJson = """{"type":"object","properties":{}}""",
        )
        override suspend fun run(input: JsonObject, ctx: ToolContext): ToolOutcome {
            onRun()
            return ToolOutcome("done", metadata = mapOf("path" to "src/App.kt"))
        }
    }

    /** A tool that reports its own duration, which the loop must not overwrite. */
    private class SelfTimedTool : Tool {
        override val spec = ToolSpec(
            name = "self",
            description = "reports its own duration",
            parametersJson = """{"type":"object","properties":{}}""",
        )
        override suspend fun run(input: JsonObject, ctx: ToolContext): ToolOutcome =
            ToolOutcome("done", metadata = mapOf("durationMs" to "42"))
    }

    /** Captures the session state the loop has persisted when the tool runs. */
    private class StateCapturingTool(private val onState: (SessionState) -> Unit) : Tool {
        override val spec = ToolSpec(
            name = "state_probe",
            description = "captures the persisted session state",
            parametersJson = """{"type":"object","properties":{}}""",
        )
        override suspend fun run(input: JsonObject, ctx: ToolContext): ToolOutcome {
            onState(ctx.session.state)
            return ToolOutcome("probed")
        }
    }

    private fun store() = InMemorySessionStore()

    /** Seed a prior assistant turn whose usage already counts against the budget. */
    private suspend fun seedCost(store: InMemorySessionStore, sessionId: SessionId, cost: Double) {
        store.appendMessage(
            Message(
                id = MessageId("msg_seed"),
                sessionId = sessionId,
                role = Role.ASSISTANT,
                createdAt = 0,
                usage = Usage(costUsd = cost),
            ),
        )
    }

    private suspend fun newSession(store: InMemorySessionStore, id: String) {
        store.createSession(
            Session(
                id = SessionId(id),
                cwd = System.getProperty("user.dir"),
                createdAt = 0,
                updatedAt = 0,
            ),
        )
    }

    @Test
    fun `runs a tool then finishes, persisting parts and wiring results back`() = runTest {
        val provider = ScriptedProvider(
            listOf(
                ProviderEvent.ReasoningDelta("hmm "),
                ProviderEvent.TextDelta("let me check"),
                ProviderEvent.ToolCallStart(0, "call_1", "echo"),
                ProviderEvent.ToolCallArgsDelta(0, "{\"text\":\"hi"),
                ProviderEvent.ToolCallArgsDelta(0, "\"}"),
                ProviderEvent.ToolCallEnd(0),
                ProviderEvent.UsageEvent(Usage(inputTokens = 100, outputTokens = 20)),
                ProviderEvent.Finished(FinishReason.TOOL_CALLS),
            ),
            listOf(
                ProviderEvent.TextDelta("all done"),
                ProviderEvent.UsageEvent(Usage(inputTokens = 200, outputTokens = 5)),
                ProviderEvent.Finished(FinishReason.STOP),
            ),
        )
        val store = store()
        val bus = EventBus()
        val loop = AgentLoop(
            providers = SimpleProviderRegistry(listOf(provider)),
            tools = ToolRegistry(listOf(EchoTool())),
            store = store,
            bus = bus,
        )
        newSession(store, "ses_1")

        val events = mutableListOf<AgentEvent>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            bus.events.collect { events += it }
        }

        val last = loop.prompt(SessionId("ses_1"), "please echo hi", "fake/fake-1")
        job.cancel()

        assertEquals("all done", last.parts.filterIsInstance<Part.Text>().joinToString("") { it.text })

        val messages = store.messages(SessionId("ses_1"))
        assertEquals(3, messages.size)

        val withTool = messages.first { m -> m.role == Role.ASSISTANT && m.parts.any { it is Part.Tool } }
        val toolPart = withTool.parts.filterIsInstance<Part.Tool>().single()
        assertEquals("echo", toolPart.call.name)
        assertEquals(ToolState.DONE, toolPart.state)
        assertEquals("echo: \"hi\"", toolPart.result?.output)
        assertTrue(withTool.parts.any { it is Part.Reasoning })
        assertTrue(withTool.parts.any { it is Part.Text && it.text == "let me check" })
        assertEquals(FinishReason.TOOL_CALLS, withTool.finish)

        assertEquals(2, provider.requests.size)
        val secondWire = provider.requests[1].messages
        assertTrue(secondWire.any { it.role == "tool" && it.toolCallId == "call_1" && it.text == "echo: \"hi\"" })
        assertTrue(secondWire.any { it.role == "assistant" && it.toolCalls.single().name == "echo" })

        assertEquals(205, messages.last().usage.totalTokens)
        assertEquals(FinishReason.STOP, messages.last().finish)

        assertTrue(events.any { it is AgentEvent.PartDelta })
        assertTrue(events.any { it is AgentEvent.ToolFinished })
        assertTrue(events.any { it is AgentEvent.StateChanged && it.state == SessionState.IDLE })
    }

    @Test
    fun `emits ToolCallStarted when the model starts a tool call`() = runTest {
        val provider = ScriptedProvider(
            listOf(
                ProviderEvent.ToolCallStart(3, "call_9", "echo"),
                ProviderEvent.ToolCallArgsDelta(3, "{\"text\":\"hi\"}"),
                ProviderEvent.ToolCallEnd(3),
                ProviderEvent.Finished(FinishReason.TOOL_CALLS),
            ),
            listOf(
                ProviderEvent.TextDelta("done"),
                ProviderEvent.Finished(FinishReason.STOP),
            ),
        )
        val store = store()
        val bus = EventBus()
        val loop = AgentLoop(
            providers = SimpleProviderRegistry(listOf(provider)),
            tools = ToolRegistry(listOf(EchoTool())),
            store = store,
            bus = bus,
        )
        newSession(store, "ses_tcs")

        val events = mutableListOf<AgentEvent>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            bus.events.collect { events += it }
        }
        loop.prompt(SessionId("ses_tcs"), "go", "fake/fake-1")
        job.cancel()

        val started = events.filterIsInstance<AgentEvent.ToolCallStarted>()
        assertEquals(1, started.size)
        assertEquals(3, started.single().index)
        assertEquals("echo", started.single().name)

        val toolMessage = store.messages(SessionId("ses_tcs"))
            .first { m -> m.parts.any { it is Part.Tool } }
        assertEquals(toolMessage.id.value, started.single().messageId)
    }

    @Test
    fun `allowSubagents controls whether the task tool is offered`() = runTest {
        fun done() = listOf(ProviderEvent.TextDelta("ok"), ProviderEvent.Finished(FinishReason.STOP))
        val provider = ScriptedProvider(done(), done())
        val store = store()
        val loop = AgentLoop(
            providers = SimpleProviderRegistry(listOf(provider)),
            tools = ToolRegistry(listOf(EchoTool(), NamedTool("task"))),
            store = store,
            bus = EventBus(),
        )
        newSession(store, "ses_sub")

        loop.prompt(SessionId("ses_sub"), "hi", "fake/fake-1", agent = AgentConfig(allowSubagents = false))
        loop.prompt(SessionId("ses_sub"), "hi", "fake/fake-1", agent = AgentConfig(allowSubagents = true))

        assertEquals(2, provider.requests.size)
        val denied = provider.requests[0].tools.map { it.name }
        assertTrue(denied.contains("echo"), "echo should still be offered: $denied")
        assertTrue("task" !in denied, "task must not be offered when allowSubagents=false: $denied")
        val allowed = provider.requests[1].tools.map { it.name }
        assertTrue("task" in allowed, "task must be offered when allowSubagents=true: $allowed")
    }

    @Test
    fun `provider failure is persisted as an errored assistant message`() = runTest {
        val provider = ScriptedProvider(
            listOf(ProviderEvent.TextDelta("partial"), ProviderEvent.Failure("boom")),
        )
        val store = store()
        val bus = EventBus()
        val loop = AgentLoop(
            providers = SimpleProviderRegistry(listOf(provider)),
            tools = ToolRegistry(emptyList()),
            store = store,
            bus = bus,
        )
        newSession(store, "ses_2")

        val result = loop.prompt(SessionId("ses_2"), "hi", "fake/fake-1")

        assertEquals(FinishReason.ERROR, result.finish)
        assertEquals("boom", result.error)
        assertEquals("partial", result.parts.filterIsInstance<Part.Text>().joinToString("") { it.text })
    }

    @Test
    fun `a tool call records its wall-clock duration in the result metadata`() = runTest {
        val provider = ScriptedProvider(
            listOf(
                ProviderEvent.ToolCallStart(0, "call_1", "timed"),
                ProviderEvent.ToolCallArgsDelta(0, "{}"),
                ProviderEvent.Finished(FinishReason.TOOL_CALLS),
            ),
            listOf(ProviderEvent.TextDelta("done"), ProviderEvent.Finished(FinishReason.STOP)),
        )
        val store = store()
        var now = 1_000L
        val loop = AgentLoop(
            providers = SimpleProviderRegistry(listOf(provider)),
            tools = ToolRegistry(listOf(ClockAdvancingTool { now += 500 })),
            store = store,
            bus = EventBus(),
            clock = { now },
        )
        newSession(store, "ses_dur")

        loop.prompt(SessionId("ses_dur"), "go", "fake/fake-1")

        val result = store.messages(SessionId("ses_dur"))
            .flatMap { it.parts }.filterIsInstance<Part.Tool>().single().result
        assertEquals("500", result?.metadata?.get("durationMs"))
        assertEquals("src/App.kt", result?.metadata?.get("path"))
    }

    @Test
    fun `a tool that reports its own duration is not overwritten`() = runTest {
        val provider = ScriptedProvider(
            listOf(
                ProviderEvent.ToolCallStart(0, "call_1", "self"),
                ProviderEvent.ToolCallArgsDelta(0, "{}"),
                ProviderEvent.Finished(FinishReason.TOOL_CALLS),
            ),
            listOf(ProviderEvent.TextDelta("done"), ProviderEvent.Finished(FinishReason.STOP)),
        )
        val store = store()
        var now = 0L
        val loop = AgentLoop(
            providers = SimpleProviderRegistry(listOf(provider)),
            tools = ToolRegistry(listOf(SelfTimedTool())),
            store = store,
            bus = EventBus(),
            clock = { now++ },
        )
        newSession(store, "ses_self")

        loop.prompt(SessionId("ses_self"), "go", "fake/fake-1")

        val result = store.messages(SessionId("ses_self"))
            .flatMap { it.parts }.filterIsInstance<Part.Tool>().single().result
        assertEquals("42", result?.metadata?.get("durationMs"))
    }

    @Test
    fun `maxSteps caps the number of model turns`() = runTest {
        fun alwaysTool() = listOf(
            ProviderEvent.ToolCallStart(0, "call_1", "echo"),
            ProviderEvent.ToolCallArgsDelta(0, "{\"text\":\"x\"}"),
            ProviderEvent.Finished(FinishReason.TOOL_CALLS),
        )
        val provider = ScriptedProvider(alwaysTool(), alwaysTool(), alwaysTool(), alwaysTool())
        val store = store()
        val loop = AgentLoop(
            providers = SimpleProviderRegistry(listOf(provider)),
            tools = ToolRegistry(listOf(EchoTool())),
            store = store,
            bus = EventBus(),
        )
        newSession(store, "ses_3")

        loop.prompt(SessionId("ses_3"), "go", "fake/fake-1", agent = AgentConfig(maxSteps = 2))

        assertEquals(2, provider.requests.size)
    }

    @Test
    fun `budget warning fires once at the threshold and does not repeat`() = runTest {
        val provider = ScriptedProvider(
            listOf(
                ProviderEvent.ToolCallStart(0, "call_1", "echo"),
                ProviderEvent.ToolCallArgsDelta(0, "{\"text\":\"x\"}"),
                ProviderEvent.Finished(FinishReason.TOOL_CALLS),
            ),
            listOf(ProviderEvent.TextDelta("done"), ProviderEvent.Finished(FinishReason.STOP)),
        )
        val store = store()
        val bus = EventBus()
        val loop = AgentLoop(
            providers = SimpleProviderRegistry(listOf(provider)),
            tools = ToolRegistry(listOf(EchoTool())),
            store = store,
            bus = bus,
        )
        newSession(store, "ses_budget")
        seedCost(store, SessionId("ses_budget"), 0.85)

        val events = mutableListOf<AgentEvent>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            bus.events.collect { events += it }
        }
        loop.prompt(SessionId("ses_budget"), "go", "fake/fake-1", budget = ContextBudget(maxCostUsd = 1.0))
        job.cancel()

        val warnings = events.filterIsInstance<AgentEvent.BudgetWarning>()
        assertEquals(1, warnings.size)
        assertEquals(1.0, warnings.single().maxUsd)
        assertEquals(0.85, warnings.single().spentUsd)
        assertTrue(warnings.single().fraction >= 0.8)
    }

    @Test
    fun `over-budget emits a warning at or above 1_0 then throws`() = runTest {
        val provider = ScriptedProvider(
            listOf(ProviderEvent.TextDelta("unused"), ProviderEvent.Finished(FinishReason.STOP)),
        )
        val store = store()
        val bus = EventBus()
        val loop = AgentLoop(
            providers = SimpleProviderRegistry(listOf(provider)),
            tools = ToolRegistry(emptyList()),
            store = store,
            bus = bus,
        )
        newSession(store, "ses_over")
        seedCost(store, SessionId("ses_over"), 1.5)

        val events = mutableListOf<AgentEvent>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            bus.events.collect { events += it }
        }
        val error = assertFailsWith<IllegalStateException> {
            loop.prompt(SessionId("ses_over"), "go", "fake/fake-1", budget = ContextBudget(maxCostUsd = 1.0))
        }
        job.cancel()

        assertTrue(error.message!!.contains("session cost budget exceeded"))
        val warnings = events.filterIsInstance<AgentEvent.BudgetWarning>()
        assertEquals(1, warnings.size)
        assertTrue(warnings.single().fraction >= 1.0)
        assertEquals(SessionState.ERROR, store.session(SessionId("ses_over"))!!.state)
    }

    @Test
    fun `session state is RUNNING during a prompt and IDLE after it`() = runTest {
        val provider = ScriptedProvider(
            listOf(
                ProviderEvent.ToolCallStart(0, "call_1", "state_probe"),
                ProviderEvent.ToolCallArgsDelta(0, "{}"),
                ProviderEvent.Finished(FinishReason.TOOL_CALLS),
            ),
            listOf(ProviderEvent.TextDelta("done"), ProviderEvent.Finished(FinishReason.STOP)),
        )
        val store = store()
        var observed: SessionState? = null
        val loop = AgentLoop(
            providers = SimpleProviderRegistry(listOf(provider)),
            tools = ToolRegistry(listOf(StateCapturingTool { observed = it })),
            store = store,
            bus = EventBus(),
        )
        newSession(store, "ses_state_run")

        val result = loop.prompt(SessionId("ses_state_run"), "go", "fake/fake-1")

        assertEquals(FinishReason.STOP, result.finish)
        assertEquals(SessionState.RUNNING, observed)
        assertEquals(SessionState.IDLE, store.session(SessionId("ses_state_run"))!!.state)
    }

    @Test
    fun `session state is persisted ERROR on a provider failure`() = runTest {
        val provider = ScriptedProvider(listOf(ProviderEvent.Failure("boom")))
        val store = store()
        val loop = AgentLoop(
            providers = SimpleProviderRegistry(listOf(provider)),
            tools = ToolRegistry(emptyList()),
            store = store,
            bus = EventBus(),
        )
        newSession(store, "ses_state_err")

        loop.prompt(SessionId("ses_state_err"), "hi", "fake/fake-1")

        assertEquals(SessionState.ERROR, store.session(SessionId("ses_state_err"))!!.state)
    }

    @Test
    fun `maxSteps finalizes the unexecuted tool as an error`() = runTest {
        fun alwaysTool() = listOf(
            ProviderEvent.ToolCallStart(0, "call_1", "echo"),
            ProviderEvent.ToolCallArgsDelta(0, "{\"text\":\"x\"}"),
            ProviderEvent.Finished(FinishReason.TOOL_CALLS),
        )
        val provider = ScriptedProvider(alwaysTool(), alwaysTool())
        val store = store()
        val loop = AgentLoop(
            providers = SimpleProviderRegistry(listOf(provider)),
            tools = ToolRegistry(listOf(EchoTool())),
            store = store,
            bus = EventBus(),
        )
        newSession(store, "ses_max_err")

        loop.prompt(SessionId("ses_max_err"), "go", "fake/fake-1", agent = AgentConfig(maxSteps = 1))

        val tools = store.messages(SessionId("ses_max_err")).flatMap { it.parts }.filterIsInstance<Part.Tool>()
        assertEquals(1, tools.size)
        assertEquals(ToolState.ERROR, tools.single().state)
        assertEquals("step budget exhausted", tools.single().result?.output)
        assertTrue(tools.none { it.state == ToolState.PENDING })
    }

    @Test
    fun `retry discards usage from a failed attempt`() = runTest {
        val provider = ScriptedProvider(
            listOf(
                ProviderEvent.UsageEvent(Usage(inputTokens = 111, outputTokens = 22)),
                ProviderEvent.Failure("503 service unavailable"),
            ),
            listOf(
                ProviderEvent.UsageEvent(Usage(inputTokens = 7, outputTokens = 3)),
                ProviderEvent.TextDelta("ok"),
                ProviderEvent.Finished(FinishReason.STOP),
            ),
        )
        val store = store()
        val loop = AgentLoop(
            providers = SimpleProviderRegistry(listOf(provider)),
            tools = ToolRegistry(emptyList()),
            store = store,
            bus = EventBus(),
            clock = { 0 },
        )
        newSession(store, "ses_retry_usage")

        val result = loop.prompt(
            SessionId("ses_retry_usage"),
            "go",
            "fake/fake-1",
            agent = AgentConfig(maxRetries = 1),
        )

        assertEquals(7, result.usage.inputTokens)
        assertEquals(3, result.usage.outputTokens)
        assertEquals(10, result.usage.totalTokens)
    }

    @Test
    fun `provider failure keeps reasoning and emits terminal part updates`() = runTest {
        val provider = ScriptedProvider(
            listOf(
                ProviderEvent.ReasoningDelta("thinking"),
                ProviderEvent.TextDelta("partial"),
                ProviderEvent.Failure("boom"),
            ),
        )
        val store = store()
        val bus = EventBus()
        val loop = AgentLoop(
            providers = SimpleProviderRegistry(listOf(provider)),
            tools = ToolRegistry(emptyList()),
            store = store,
            bus = bus,
        )
        newSession(store, "ses_fail_parts")

        val events = mutableListOf<AgentEvent>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            bus.events.collect { events += it }
        }
        val result = loop.prompt(SessionId("ses_fail_parts"), "hi", "fake/fake-1")
        job.cancel()

        assertEquals("thinking", result.parts.filterIsInstance<Part.Reasoning>().single().text)
        assertEquals("partial", result.parts.filterIsInstance<Part.Text>().single().text)
        val updated = events.filterIsInstance<AgentEvent.PartUpdated>()
        assertTrue(updated.any { (it.part as? Part.Reasoning)?.text == "thinking" })
        assertTrue(updated.any { (it.part as? Part.Text)?.text == "partial" })
    }

    @Test
    fun `cancellation aborts only the assistant and emits one idle`() = runTest {
        val provider = object : Provider {
            override val id = "fake"
            override suspend fun models() =
                listOf(ModelInfo(providerId = id, id = "fake-1", label = "fake"))

            override fun stream(request: ChatRequest): Flow<ProviderEvent> = flow {
                emit(ProviderEvent.TextDelta("partial"))
                awaitCancellation()
            }
        }
        val store = store()
        val bus = EventBus()
        val loop = AgentLoop(
            providers = SimpleProviderRegistry(listOf(provider)),
            tools = ToolRegistry(emptyList()),
            store = store,
            bus = bus,
        )
        newSession(store, "ses_cancel")

        val events = mutableListOf<AgentEvent>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            bus.events.collect { events += it }
        }
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            runCatching { loop.prompt(SessionId("ses_cancel"), "hello", "fake/fake-1") }
        }
        job.cancel()
        job.join()
        collector.cancel()

        val user = store.messages(SessionId("ses_cancel")).first { it.role == Role.USER }
        assertEquals(null, user.finish)
        assertEquals(null, user.error)

        val idle = events.filterIsInstance<AgentEvent.StateChanged>().filter { it.state == SessionState.IDLE }
        assertEquals(1, idle.size)
    }
}
