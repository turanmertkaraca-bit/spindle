package dev.spindle.core.agent

import dev.spindle.core.event.AgentEvent
import dev.spindle.core.event.EventBus
import dev.spindle.core.model.FileEdit
import dev.spindle.core.model.FinishReason
import dev.spindle.core.model.Part
import dev.spindle.core.model.Session
import dev.spindle.core.model.SessionId
import dev.spindle.core.model.Snapshot
import dev.spindle.core.model.ToolState
import dev.spindle.core.model.Usage
import dev.spindle.core.provider.ChatRequest
import dev.spindle.core.provider.ModelInfo
import dev.spindle.core.provider.Provider
import dev.spindle.core.provider.ProviderEvent
import dev.spindle.core.provider.SimpleProviderRegistry
import dev.spindle.core.provider.ToolSpec
import dev.spindle.core.store.InMemorySessionStore
import dev.spindle.core.store.InMemorySnapshotStore
import dev.spindle.core.tool.ApprovalDecision
import dev.spindle.core.tool.ApprovalPolicy
import dev.spindle.core.tool.ApprovalRequest
import dev.spindle.core.tool.Tool
import dev.spindle.core.tool.ToolContext
import dev.spindle.core.tool.ToolOutcome
import dev.spindle.core.tool.ToolRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AgentCapabilitiesTest {

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

    private class FixedPolicy(private val decision: ApprovalDecision) : ApprovalPolicy {
        var decisions = 0
        var remembered: ApprovalDecision? = null
        override suspend fun decide(request: ApprovalRequest): ApprovalDecision {
            decisions++
            return decision
        }
        override suspend fun remember(request: ApprovalRequest, decision: ApprovalDecision) {
            remembered = decision
        }
    }

    private class RecordingTool : Tool {
        var calls = 0
        override val spec = ToolSpec(
            name = "edit",
            description = "records that it ran",
            parametersJson = """{"type":"object","properties":{"path":{"type":"string"}}}""",
        )
        override suspend fun run(input: JsonObject, ctx: ToolContext): ToolOutcome {
            calls++
            return ToolOutcome("ran")
        }
    }

    /** Mimics a real mutating tool: records a snapshot and returns a structured edit. */
    private class SnapshotEditTool : Tool {
        override val spec = ToolSpec(
            name = "edit",
            description = "records a snapshot and returns a FileEdit",
            parametersJson = """{"type":"object","properties":{"path":{"type":"string"}}}""",
        )
        override suspend fun run(input: JsonObject, ctx: ToolContext): ToolOutcome {
            ctx.snapshots?.record(
                Snapshot(
                    id = "snap_1",
                    sessionId = ctx.sessionId,
                    path = "a.txt",
                    content = "before",
                    sha256 = "hash",
                    createdAt = 0,
                ),
            )
            return ToolOutcome(
                output = "edited",
                edit = FileEdit(
                    id = "edit_1",
                    sessionId = ctx.sessionId,
                    path = "a.txt",
                    added = 2,
                    removed = 1,
                    unifiedDiff = "--- a/a.txt\n+++ b/a.txt\n",
                    created = false,
                    at = 0,
                ),
                snapshotId = "snap_1",
            )
        }
    }

    private fun toolTurn(name: String = "edit") = listOf(
        ProviderEvent.ToolCallStart(0, "call_1", name),
        ProviderEvent.ToolCallArgsDelta(0, "{\"path\":\"a.txt\"}"),
        ProviderEvent.ToolCallEnd(0),
        ProviderEvent.Finished(FinishReason.TOOL_CALLS),
    )

    private fun doneTurn(text: String = "done") = listOf(
        ProviderEvent.TextDelta(text),
        ProviderEvent.Finished(FinishReason.STOP),
    )

    private suspend fun newSession(store: InMemorySessionStore, id: String, cwd: String = System.getProperty("user.dir")) {
        store.createSession(
            Session(id = SessionId(id), cwd = cwd, createdAt = 0, updatedAt = 0),
        )
    }

    @Test
    fun `approval ALLOW runs the tool without asking the gate`() = runTest {
        val provider = ScriptedProvider(toolTurn(), doneTurn())
        val store = InMemorySessionStore()
        newSession(store, "ses_allow")
        val tool = RecordingTool()
        val policy = FixedPolicy(ApprovalDecision.ALLOW)
        var gateCalls = 0
        val loop = AgentLoop(
            providers = SimpleProviderRegistry(listOf(provider)),
            tools = ToolRegistry(listOf(tool)),
            store = store,
            bus = EventBus(),
            permissions = { _, _, _ -> gateCalls++; true },
            approval = policy,
        )

        loop.prompt(SessionId("ses_allow"), "go", "fake/fake-1")

        assertEquals(1, tool.calls)
        assertEquals(0, gateCalls)
        assertEquals(1, policy.decisions)
        val result = store.messages(SessionId("ses_allow"))
            .flatMap { it.parts }.filterIsInstance<Part.Tool>().single().result
        assertTrue(result != null && !result.isError)
    }

    @Test
    fun `approval DENY blocks the tool without asking the gate`() = runTest {
        val provider = ScriptedProvider(toolTurn(), doneTurn())
        val store = InMemorySessionStore()
        newSession(store, "ses_deny")
        val tool = RecordingTool()
        var gateCalls = 0
        val loop = AgentLoop(
            providers = SimpleProviderRegistry(listOf(provider)),
            tools = ToolRegistry(listOf(tool)),
            store = store,
            bus = EventBus(),
            permissions = { _, _, _ -> gateCalls++; true },
            approval = FixedPolicy(ApprovalDecision.DENY),
        )

        loop.prompt(SessionId("ses_deny"), "go", "fake/fake-1")

        assertEquals(0, tool.calls)
        assertEquals(0, gateCalls)
        val part = store.messages(SessionId("ses_deny"))
            .flatMap { it.parts }.filterIsInstance<Part.Tool>().single()
        assertEquals(ToolState.ERROR, part.state)
        assertTrue(part.result!!.isError)
        assertTrue(part.result!!.output.contains("denied", ignoreCase = true), part.result!!.output)
    }

    @Test
    fun `approval ASK consults the gate and runs when approved`() = runTest {
        val provider = ScriptedProvider(toolTurn(), doneTurn())
        val store = InMemorySessionStore()
        newSession(store, "ses_ask_yes")
        val tool = RecordingTool()
        var gateCalls = 0
        val policy = FixedPolicy(ApprovalDecision.ASK)
        val loop = AgentLoop(
            providers = SimpleProviderRegistry(listOf(provider)),
            tools = ToolRegistry(listOf(tool)),
            store = store,
            bus = EventBus(),
            permissions = { _, _, _ -> gateCalls++; true },
            approval = policy,
        )

        loop.prompt(SessionId("ses_ask_yes"), "go", "fake/fake-1")

        assertEquals(1, gateCalls)
        assertEquals(1, tool.calls)
        assertEquals(ApprovalDecision.ALLOW, policy.remembered)
    }

    @Test
    fun `approval ASK blocks the tool when the gate refuses`() = runTest {
        val provider = ScriptedProvider(toolTurn(), doneTurn())
        val store = InMemorySessionStore()
        newSession(store, "ses_ask_no")
        val tool = RecordingTool()
        var gateCalls = 0
        val policy = FixedPolicy(ApprovalDecision.ASK)
        val loop = AgentLoop(
            providers = SimpleProviderRegistry(listOf(provider)),
            tools = ToolRegistry(listOf(tool)),
            store = store,
            bus = EventBus(),
            permissions = { _, _, _ -> gateCalls++; false },
            approval = policy,
        )

        loop.prompt(SessionId("ses_ask_no"), "go", "fake/fake-1")

        assertEquals(1, gateCalls)
        assertEquals(0, tool.calls)
        assertEquals(ApprovalDecision.DENY, policy.remembered)
    }

    @Test
    fun `loop emits FileEdited and SnapshotCreated for a structured edit`() = runTest {
        val provider = ScriptedProvider(toolTurn(), doneTurn())
        val store = InMemorySessionStore()
        newSession(store, "ses_edit")
        val snapshots = InMemorySnapshotStore()
        val bus = EventBus()
        val loop = AgentLoop(
            providers = SimpleProviderRegistry(listOf(provider)),
            tools = ToolRegistry(listOf(SnapshotEditTool())),
            store = store,
            bus = bus,
            snapshots = snapshots,
        )

        val events = mutableListOf<AgentEvent>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            bus.events.collect { events += it }
        }
        loop.prompt(SessionId("ses_edit"), "go", "fake/fake-1")
        job.cancel()

        val fileEdited = events.filterIsInstance<AgentEvent.FileEdited>().single()
        assertEquals("a.txt", fileEdited.edit.path)
        assertEquals(2, fileEdited.edit.added)
        assertEquals(1, fileEdited.edit.removed)
        assertNotNull(fileEdited.edit.messageId)

        val snapshotCreated = events.filterIsInstance<AgentEvent.SnapshotCreated>().single()
        assertEquals("snap_1", snapshotCreated.snapshotId)
        assertEquals("a.txt", snapshotCreated.path)

        assertEquals(1, snapshots.forSession(SessionId("ses_edit")).size)
    }

    @Test
    fun `emits running session usage totals after each assistant message`() = runTest {
        val provider = ScriptedProvider(
            listOf(
                ProviderEvent.ToolCallStart(0, "call_1", "edit"),
                ProviderEvent.ToolCallArgsDelta(0, "{}"),
                ProviderEvent.ToolCallEnd(0),
                ProviderEvent.UsageEvent(Usage(inputTokens = 100, outputTokens = 20)),
                ProviderEvent.Finished(FinishReason.TOOL_CALLS),
            ),
            listOf(
                ProviderEvent.TextDelta("done"),
                ProviderEvent.UsageEvent(Usage(inputTokens = 200, outputTokens = 5)),
                ProviderEvent.Finished(FinishReason.STOP),
            ),
        )
        val store = InMemorySessionStore()
        newSession(store, "ses_usage")
        val bus = EventBus()
        val loop = AgentLoop(
            providers = SimpleProviderRegistry(listOf(provider)),
            tools = ToolRegistry(listOf(RecordingTool())),
            store = store,
            bus = bus,
        )

        val events = mutableListOf<AgentEvent>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            bus.events.collect { events += it }
        }
        loop.prompt(SessionId("ses_usage"), "go", "fake/fake-1")
        job.cancel()

        val updates = events.filterIsInstance<AgentEvent.UsageUpdated>()
        assertEquals(2, updates.size)
        val total = updates.last().usage
        assertEquals(300, total.inputTokens)
        assertEquals(25, total.outputTokens)
        assertEquals(325, total.totalTokens)
    }

    @Test
    fun `injects AGENTS md and extra rules into the outgoing system prompt`() = runTest {
        val dir = Files.createTempDirectory("spindle-agents-md")
        try {
            Files.writeString(dir.resolve("AGENTS.md"), "Always use tabs.\nPrefer small commits.")
            val provider = ScriptedProvider(doneTurn())
            val store = InMemorySessionStore()
            newSession(store, "ses_rules", cwd = dir.toString())
            val loop = AgentLoop(
                providers = SimpleProviderRegistry(listOf(provider)),
                tools = ToolRegistry(emptyList()),
                store = store,
                bus = EventBus(),
            )

            loop.prompt(SessionId("ses_rules"), "go", "fake/fake-1", rules = "Host rule: be terse.")

            val system = provider.requests.single().system
            assertTrue(system.contains("Project rules"), system)
            assertTrue(system.contains("Always use tabs."), system)
            assertTrue(system.contains("Additional rules"), system)
            assertTrue(system.contains("Host rule: be terse."), system)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `resolves a primary agent by name`() = runTest {
        val provider = ScriptedProvider(doneTurn())
        val store = InMemorySessionStore()
        newSession(store, "ses_named")
        val loop = AgentLoop(
            providers = SimpleProviderRegistry(listOf(provider)),
            tools = ToolRegistry(emptyList()),
            store = store,
            bus = EventBus(),
        )

        loop.prompt(SessionId("ses_named"), "go", "fake/fake-1", agentName = "plan")

        val assistant = store.messages(SessionId("ses_named"))
            .first { it.role == dev.spindle.core.model.Role.ASSISTANT }
        assertEquals("plan", assistant.agent)
    }
}
