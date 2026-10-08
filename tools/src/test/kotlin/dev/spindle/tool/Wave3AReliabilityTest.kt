package dev.spindle.tool

import dev.spindle.core.agent.AgentLoop
import dev.spindle.core.event.EventBus
import dev.spindle.core.model.FinishReason
import dev.spindle.core.model.Part
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
import dev.spindle.core.tool.Tool
import dev.spindle.core.tool.ToolContext
import dev.spindle.core.tool.ToolOutcome
import dev.spindle.core.tool.ToolRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val wave3aJson = Json { ignoreUnknownKeys = true; isLenient = true }

private fun wave3aObj(text: String): JsonObject = wave3aJson.parseToJsonElement(text) as JsonObject

private suspend fun <T> wave3aTempDir(block: suspend (Path) -> T): T {
    val dir = Files.createTempDirectory("spindle-wave3a-test")
    try {
        return block(dir)
    } finally {
        dir.toFile().deleteRecursively()
    }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class Wave3AReliabilityTest {

    @Test
    fun `withPathLock serializes concurrent blocks on the same path`() = runTest {
        wave3aTempDir { dir ->
            val file = dir.resolve("shared.txt")
            Files.writeString(file, "seed")

            val inside = AtomicInteger(0)
            val maxInside = AtomicInteger(0)
            val order = Collections.synchronizedList(ArrayList<String>())

            val jobs = (1..4).map { i ->
                launch(UnconfinedTestDispatcher(testScheduler)) {
                    withPathLock(file.toString()) {
                        inside.incrementAndGet()
                        maxInside.updateAndGet { maxOf(it, inside.get()) }
                        order.add("enter$i")
                        delay(20)
                        order.add("exit$i")
                        inside.decrementAndGet()
                    }
                }
            }
            jobs.forEach { it.join() }

            assertEquals(1, maxInside.get(), "critical sections must never overlap: $order")
            assertEquals(8, order.size)
            for (k in 0 until order.size step 2) {
                val enter = order[k]
                val exit = order[k + 1]
                assertTrue(enter.startsWith("enter"), "unpaired entry: $order")
                assertEquals(
                    enter.removePrefix("enter"),
                    exit.removePrefix("exit"),
                    "blocks interleaved: $order",
                )
            }
        }
    }

    @Test
    fun `glob enforces the result cap during the walk and skips heavy dirs`() = runTest {
        wave3aTempDir { dir ->
            val ctx = ToolCtx(dir)
            Files.createDirectories(dir.resolve("build"))
            Files.writeString(dir.resolve("build/ignored.txt"), "x")
            Files.createDirectories(dir.resolve(".git"))
            Files.writeString(dir.resolve(".git/ignored.txt"), "x")
            Files.createDirectories(dir.resolve("node_modules"))
            Files.writeString(dir.resolve("node_modules/ignored.txt"), "x")

            val total = Limits.GLOB_MAX_RESULTS + 25
            repeat(total) { i ->
                Files.writeString(dir.resolve("f%05d.txt".format(i)), "x")
            }

            val outcome = GlobTool().run(wave3aObj("""{"pattern":"**/*.txt"}"""), ctx)
            assertFalse(outcome.isError, outcome.output)
            assertEquals(Limits.GLOB_MAX_RESULTS.toString(), outcome.metadata["count"])
            assertEquals("true", outcome.metadata["truncated"])
            assertFalse(outcome.output.contains("build/ignored.txt"), outcome.output)
            assertFalse(outcome.output.contains(".git/ignored.txt"), outcome.output)
            assertFalse(outcome.output.contains("node_modules/ignored.txt"), outcome.output)
        }
    }

    @Test
    fun `agent loop rejects malformed tool arguments without running the tool`() = runTest {
        val ran = AtomicBoolean(false)
        val tool = object : Tool {
            override val spec = ToolSpec(
                name = "echo",
                description = "echo",
                parametersJson = """{"type":"object","properties":{"text":{"type":"string"}}}""",
            )
            override suspend fun run(input: JsonObject, ctx: ToolContext): ToolOutcome {
                ran.set(true)
                return ToolOutcome("ran")
            }
        }
        val provider = Wave3AProvider(
            listOf(
                ProviderEvent.ToolCallStart(0, "call_1", "echo"),
                ProviderEvent.ToolCallArgsDelta(0, "{not valid json"),
                ProviderEvent.Finished(FinishReason.TOOL_CALLS),
            ),
        )
        val store = InMemorySessionStore()
        store.createSession(
            Session(
                id = SessionId("ses_badargs"),
                cwd = System.getProperty("user.dir"),
                createdAt = 0,
                updatedAt = 0,
            ),
        )
        val loop = AgentLoop(
            providers = SimpleProviderRegistry(listOf(provider)),
            tools = ToolRegistry(listOf(tool)),
            store = store,
            bus = EventBus(),
        )

        loop.prompt(SessionId("ses_badargs"), "go", "fake/fake-1")

        assertFalse(ran.get(), "the tool must not run when its arguments are malformed")
        val part = store.messages(SessionId("ses_badargs"))
            .flatMap { it.parts }.filterIsInstance<Part.Tool>().single()
        assertEquals(ToolState.ERROR, part.state)
        val output = part.result?.output.orEmpty()
        assertTrue(output.contains("Invalid arguments for echo"), output)
        assertTrue(output.contains("Rewrite the input as valid JSON"), output)
    }

    /** Minimal ToolContext backed by a temp directory. */
    private class ToolCtx(override val cwd: Path) : ToolContext {
        override val sessionId = SessionId("wave3a")
        override val session = Session(id = sessionId, cwd = cwd.toString(), createdAt = 0, updatedAt = 0)
        override suspend fun requestPermission(tool: String, detail: String, pattern: String?): Boolean = true
        override suspend fun ask(question: String, options: List<String>, multiple: Boolean): List<String> =
            emptyList()
        override fun emit(event: dev.spindle.core.tool.ToolProgress) {}
        override suspend fun checkAborted() {}
        override suspend fun setTodos(todos: List<dev.spindle.core.model.TodoItem>) {}
        override suspend fun todos(): List<dev.spindle.core.model.TodoItem> = emptyList()
        override suspend fun subagent(spec: dev.spindle.core.tool.SubagentSpec) =
            dev.spindle.core.tool.SubagentResult(SessionId("child"), "ok", true)
    }

    /** Returns the scripted turns in order, then a plain completion. */
    private class Wave3AProvider(vararg turns: List<ProviderEvent>) : Provider {
        override val id = "fake"
        private val turns = turns.toMutableList()
        override suspend fun models() = listOf(
            ModelInfo(providerId = id, id = "fake-1", label = "fake", supportsTools = true),
        )
        override fun stream(request: ChatRequest): Flow<ProviderEvent> {
            val next = if (turns.isNotEmpty()) {
                turns.removeAt(0)
            } else {
                listOf(ProviderEvent.TextDelta("done"), ProviderEvent.Finished(FinishReason.STOP))
            }
            return next.asFlow()
        }
    }
}
