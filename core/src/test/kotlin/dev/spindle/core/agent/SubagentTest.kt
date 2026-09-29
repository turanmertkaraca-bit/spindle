package dev.spindle.core.agent

import dev.spindle.core.event.EventBus
import dev.spindle.core.model.FinishReason
import dev.spindle.core.model.Message
import dev.spindle.core.model.MessageId
import dev.spindle.core.model.Part
import dev.spindle.core.model.Role
import dev.spindle.core.model.Session
import dev.spindle.core.model.SessionId
import dev.spindle.core.model.ToolState
import dev.spindle.core.provider.ChatRequest
import dev.spindle.core.provider.ModelInfo
import dev.spindle.core.provider.Provider
import dev.spindle.core.provider.ProviderEvent
import dev.spindle.core.provider.SimpleProviderRegistry
import dev.spindle.core.provider.ToolSpec
import dev.spindle.core.store.InMemorySessionStore
import dev.spindle.core.tool.SubagentSpec
import dev.spindle.core.tool.Tool
import dev.spindle.core.tool.ToolContext
import dev.spindle.core.tool.ToolOutcome
import dev.spindle.core.tool.ToolRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SubagentTest {

    private class ScriptedProvider(vararg turns: List<ProviderEvent>) : Provider {
        override val id = "fake"
        private val turns = turns.toMutableList()
        val requests = mutableListOf<ChatRequest>()
        override suspend fun models() = listOf(
            ModelInfo(providerId = id, id = "fake-1", label = "fake", supportsTools = true),
        )
        override fun stream(request: ChatRequest): Flow<ProviderEvent> {
            requests += request
            return turns.removeAt(0).asFlow()
        }
    }

    /** Minimal stand-in for the real `task` tool that lives in `:tools`. */
    private class TaskTool : Tool {
        override val spec = ToolSpec(
            name = "task",
            description = "spawns a subagent to work on a task",
            parametersJson = """
                {"type":"object","properties":{"description":{"type":"string"},"prompt":{"type":"string"}},"required":["description","prompt"]}
            """.trimIndent(),
        )

        override suspend fun run(input: JsonObject, ctx: ToolContext): ToolOutcome {
            val description = input["description"]?.jsonPrimitive?.contentOrNull ?: ""
            val prompt = input["prompt"]?.jsonPrimitive?.contentOrNull ?: ""
            val result = ctx.subagent(SubagentSpec(description = description, prompt = prompt))
            return ToolOutcome("[subagent ${result.sessionId.value}] ${result.text}")
        }
    }

    private suspend fun newSession(
        store: InMemorySessionStore,
        id: String,
        model: String? = null,
        provider: String? = null,
    ) {
        store.createSession(
            Session(
                id = SessionId(id),
                cwd = System.getProperty("user.dir"),
                createdAt = 0,
                updatedAt = 0,
                model = model,
                providerId = provider,
            ),
        )
    }

    @Test
    fun `task tool spawns a child session and wires its report back`() = runTest {
        val provider = ScriptedProvider(
            // parent turn 1: ask for the task tool
            listOf(
                ProviderEvent.ToolCallStart(0, "call_task", "task"),
                ProviderEvent.ToolCallArgsDelta(0, "{\"description\":\"x\",\"prompt\":\"look around\"}"),
                ProviderEvent.ToolCallEnd(0),
                ProviderEvent.Finished(FinishReason.TOOL_CALLS),
            ),
            // child turn: normal answer
            listOf(
                ProviderEvent.TextDelta("child report text"),
                ProviderEvent.Finished(FinishReason.STOP),
            ),
            // parent turn 2: final answer after seeing the tool result
            listOf(
                ProviderEvent.TextDelta("parent done"),
                ProviderEvent.Finished(FinishReason.STOP),
            ),
        )
        val store = InMemorySessionStore()
        val loop = AgentLoop(
            providers = SimpleProviderRegistry(listOf(provider)),
            tools = ToolRegistry(listOf(TaskTool())),
            store = store,
            bus = EventBus(),
        )
        val parentId = SessionId("ses_parent")
        newSession(store, parentId.value, model = "fake-1", provider = "fake")

        val last = loop.prompt(parentId, "please delegate", "fake/fake-1")

        assertEquals(
            "parent done",
            last.parts.filterIsInstance<Part.Text>().joinToString("") { it.text },
        )

        // A child session was created and parented to the caller.
        val children = store.sessions(includeChildren = true).filter { it.parentId == parentId }
        assertEquals(1, children.size)
        val child = children.single()

        // The child actually ran the delegated prompt.
        val childMessages = store.messages(child.id)
        assertTrue(
            childMessages.any { m ->
                m.role == Role.USER && m.parts.filterIsInstance<Part.Text>().any { it.text == "look around" }
            },
            "child never received the prompt: $childMessages",
        )
        assertTrue(
            childMessages.any { m ->
                m.role == Role.ASSISTANT && m.parts.filterIsInstance<Part.Text>().any { it.text.contains("child report") }
            },
            "child never produced its report: $childMessages",
        )

        // The parent's tool part completed with the subagent result.
        val toolPart = store.messages(parentId)
            .flatMap { it.parts }
            .filterIsInstance<Part.Tool>()
            .single { it.call.name == "task" }
        assertEquals(ToolState.DONE, toolPart.state)
        val output = toolPart.result?.output ?: ""
        assertTrue(output.contains("[subagent"), "unexpected tool output: $output")

        // The parent's follow-up model turn received that text as a tool wire message.
        val parentRequests = provider.requests.filter { it.sessionHint == parentId.value }
        assertEquals(2, parentRequests.size)
        val followUp = parentRequests[1].messages
        assertTrue(
            followUp.any { it.role == "tool" && it.text?.contains("[subagent") == true },
            "follow-up wire messages did not include the subagent result: $followUp",
        )
    }

    @Test
    fun `inheritance prefers the latest assistant message over stale session fields`() {
        val parent = Session(
            id = SessionId("ses_p"),
            cwd = "/tmp",
            createdAt = 0,
            updatedAt = 0,
            model = "stale-model",
            providerId = "stale-provider",
        )
        val assistant = Message(
            id = MessageId("msg_1"),
            sessionId = parent.id,
            role = Role.ASSISTANT,
            createdAt = 1,
            model = "fake-1",
            providerId = "fake",
        )

        assertEquals("fake" to "fake-1", SubagentInheritance.resolve(parent, listOf(assistant)))
        assertEquals("stale-provider" to "stale-model", SubagentInheritance.resolve(parent, emptyList()))
        assertNull(SubagentInheritance.resolve(parent.copy(model = null, providerId = null), emptyList()))
    }

    @Test
    fun `child inherits provider and model from the parent's latest assistant message`() = runTest {
        val provider = ScriptedProvider(
            listOf(
                ProviderEvent.ToolCallStart(0, "call_task", "task"),
                ProviderEvent.ToolCallArgsDelta(0, "{\"description\":\"x\",\"prompt\":\"look\"}"),
                ProviderEvent.ToolCallEnd(0),
                ProviderEvent.Finished(FinishReason.TOOL_CALLS),
            ),
            listOf(
                ProviderEvent.TextDelta("child ok"),
                ProviderEvent.Finished(FinishReason.STOP),
            ),
            listOf(
                ProviderEvent.TextDelta("parent ok"),
                ProviderEvent.Finished(FinishReason.STOP),
            ),
        )
        val store = InMemorySessionStore()
        val loop = AgentLoop(
            providers = SimpleProviderRegistry(listOf(provider)),
            tools = ToolRegistry(listOf(TaskTool())),
            store = store,
            bus = EventBus(),
        )
        val parentId = SessionId("ses_parent")
        // Stale session fields; the chosen model is only recorded on the assistant message.
        newSession(store, parentId.value, model = "stale-model", provider = "stale-provider")

        val last = loop.prompt(parentId, "please delegate", "fake/fake-1")
        assertEquals(
            "parent ok",
            last.parts.filterIsInstance<Part.Text>().joinToString("") { it.text },
        )

        val child = store.sessions(includeChildren = true).single { it.parentId == parentId }
        assertEquals("fake", child.providerId)
        assertEquals("fake-1", child.model)
        assertTrue(
            store.messages(child.id).any { m ->
                m.role == Role.ASSISTANT && m.parts.filterIsInstance<Part.Text>().any { it.text.contains("child ok") }
            },
            "child never ran with the inherited model: ${store.messages(child.id)}",
        )
    }
}
