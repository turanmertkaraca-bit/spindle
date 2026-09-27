package dev.spindle.core.agent

import dev.spindle.core.event.AgentEvent
import dev.spindle.core.event.EventBus
import dev.spindle.core.model.FinishReason
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
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

    private fun store() = InMemorySessionStore()

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
}
