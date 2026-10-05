package dev.lumen.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.lumen.app.data.KeyStore
import dev.lumen.app.platform.PerfSampler
import dev.lumen.app.ui.model.StepKind
import dev.spindle.core.model.FileEdit
import dev.spindle.core.model.Message
import dev.spindle.core.model.MessageId
import dev.spindle.core.model.Part
import dev.spindle.core.model.PartId
import dev.spindle.core.model.Role
import dev.spindle.core.model.Session
import dev.spindle.core.model.SessionId
import dev.spindle.core.model.ToolCall
import dev.spindle.core.model.ToolResult
import dev.spindle.core.model.ToolState
import dev.spindle.core.provider.ChatRequest
import dev.spindle.core.provider.ModelInfo
import dev.spindle.core.provider.Provider
import dev.spindle.core.provider.ProviderEvent
import dev.spindle.core.provider.SimpleProviderRegistry
import dev.spindle.core.store.InMemorySessionStore
import dev.spindle.core.tool.PtySession
import dev.spindle.core.tool.ShellExecutor
import dev.spindle.core.tool.ShellResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Long-session retention: session-scoped caches are dropped on a session switch,
 * per-session collections are bounded, and the frame sampler is always torn
 * down. Robolectric + an unconfined main so launched coroutines settle inline,
 * matching the [RevertTest] discipline.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionRetentionTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun keys(): KeyStore =
        KeyStore(ApplicationProvider.getApplicationContext<Context>())

    private fun tmp(): File = Files.createTempDirectory("lumen-retention").toFile()

    private fun privateField(vm: ChatViewModel, name: String): Any {
        val f = ChatViewModel::class.java.getDeclaredField(name)
        f.isAccessible = true
        return f.get(vm)!!
    }

    private fun sizeOf(vm: ChatViewModel, name: String): Int = when (val v = privateField(vm, name)) {
        is Map<*, *> -> v.size
        is Collection<*> -> v.size
        else -> error("$name is not a collection")
    }

    private fun samplerRunning(perf: PerfSampler): Boolean {
        val f = PerfSampler::class.java.getDeclaredField("running")
        f.isAccessible = true
        return f.getBoolean(perf)
    }

    @Test
    fun `switching sessions drops the subagent caches`() {
        val dir = tmp()
        val store = InMemorySessionStore()
        val parent = SessionId("ses_parent")
        val child = SessionId("ses_child")
        val other = SessionId("ses_other")
        runBlocking {
            store.createSession(Session(parent, "p", dir.path, 0, 0))
            store.createSession(Session(child, "c", dir.path, 0, 0))
            store.createSession(Session(other, "o", dir.path, 0, 0))
            store.appendMessage(
                Message(
                    id = MessageId("m1"), sessionId = parent, role = Role.ASSISTANT,
                    parts = listOf(
                        Part.Tool(
                            PartId("p1"),
                            ToolCall("c1", "task", "{}"),
                            ToolState.DONE,
                            ToolResult("c1", "done", metadata = mapOf("sessionId" to child.value)),
                        ),
                    ),
                    createdAt = 1,
                ),
            )
        }

        val vm = ChatViewModel(dir.toPath(), keys(), store)
        vm.openSession(parent.value)

        val sub = vm.state.value.steps.single { it.kind == StepKind.SUBAGENT }
        vm.expandSubagent(sub)
        assertEquals(1, sizeOf(vm, "childCache"), "the child transcript is cached")
        assertEquals(1, sizeOf(vm, "expandedChildren"), "the expansion is remembered")

        vm.openSession(other.value)
        assertEquals(0, sizeOf(vm, "childCache"), "a session switch drops the child cache")
        assertEquals(0, sizeOf(vm, "expandedChildren"), "a session switch drops expanded children")
    }

    @Test
    fun `recordChange keeps only the most recent changes`() {
        val dir = tmp()
        val vm = ChatViewModel(dir.toPath(), keys(), InMemorySessionStore())
        val sid = SessionId("ses_changes")

        for (i in 0 until ChatViewModel.MAX_RUN_CHANGES + 25) {
            vm.recordChange(FileEdit(id = "e$i", sessionId = sid, path = "f$i.txt", at = i.toLong()))
        }

        val edits = vm.state.value.changes.edits
        assertEquals(ChatViewModel.MAX_RUN_CHANGES, edits.size, "the change list stays bounded")
        assertEquals("e25", edits.first().id, "the oldest edits are dropped")
        assertEquals("e${ChatViewModel.MAX_RUN_CHANGES + 24}", edits.last().id, "the newest edit is retained")
    }

    @Test
    fun `stop tears down the frame sampler`() {
        val dir = tmp()
        val store = InMemorySessionStore()
        val sid = SessionId("ses_stop")
        runBlocking { store.createSession(Session(sid, "t", dir.path, 0, 0)) }
        val perf = PerfSampler()
        val runScope = CoroutineScope(Dispatchers.Unconfined)
        // The provider never finishes, so the run is still live when stop() is
        // called; startPerf has already run by the time send() returns.
        val vm = runnableVm(dir, store, perf, runScope, SuspendingProvider())

        vm.openSession(sid.value)
        vm.onInput("hi")
        vm.send()
        assertTrue(samplerRunning(perf), "the live run owns the sampler")

        vm.stop()
        assertFalse(samplerRunning(perf), "stop() must stop the sampler")
        runScope.cancel()
    }

    @Test
    fun `onCleared tears down the frame sampler`() {
        val dir = tmp()
        val store = InMemorySessionStore()
        val sid = SessionId("ses_clear")
        runBlocking { store.createSession(Session(sid, "t", dir.path, 0, 0)) }
        val perf = PerfSampler()
        val runScope = CoroutineScope(Dispatchers.Unconfined)
        val vm = runnableVm(dir, store, perf, runScope, SuspendingProvider())

        vm.openSession(sid.value)
        vm.onInput("hi")
        vm.send()
        assertTrue(samplerRunning(perf), "the live run owns the sampler")

        val cleared = ChatViewModel::class.java.getDeclaredMethod("onCleared")
        cleared.isAccessible = true
        cleared.invoke(vm)
        assertFalse(samplerRunning(perf), "onCleared must stop the sampler")
        runScope.cancel()
    }

    @Test
    fun `terminal output is capped`() {
        val dir = tmp()
        val chunks = (0 until ChatViewModel.MAX_TERMINAL_CHUNKS + 25).map { "line $it\n" }
        val vm = ChatViewModel(
            dir.toPath(),
            keys(),
            InMemorySessionStore(),
            shell = FakeShell(StreamingPty(chunks)),
        )

        vm.openTerminal()

        val lines = vm.state.value.terminal.lines
        assertEquals(ChatViewModel.MAX_TERMINAL_CHUNKS, lines.size, "terminal output stays bounded")
        assertEquals("line ${ChatViewModel.MAX_TERMINAL_CHUNKS + 24}\n", lines.last())
    }

    /** A ViewModel wired to a scripted provider, with a real (test-owned) run scope. */
    private fun runnableVm(
        dir: File,
        store: InMemorySessionStore,
        perf: PerfSampler,
        runScope: CoroutineScope,
        provider: Provider,
    ): ChatViewModel {
        val keys = keys()
        keys.provider = "test"
        keys.model = "test/mock"
        keys.apiKey = "test-key"
        return ChatViewModel(
            dir.toPath(),
            keys,
            store,
            runScope = runScope,
            registryFactory = { _, _ -> SimpleProviderRegistry(listOf(provider)) },
            perf = perf,
        )
    }

    /** Emits one delta then never completes, so a run stays live until stopped. */
    private class SuspendingProvider : Provider {
        override val id = "test"
        override suspend fun models() = listOf(ModelInfo("test", "mock", contextWindow = 100_000))
        override fun stream(request: ChatRequest): Flow<ProviderEvent> = flow {
            emit(ProviderEvent.TextDelta("hi"))
            awaitCancellation()
        }
    }

    private class StreamingPty(private val chunks: List<String>) : PtySession {
        override val pid = 4242
        override val output: Flow<String> = flow { for (c in chunks) emit(c) }
        override suspend fun write(text: String) {}
        override suspend fun resize(cols: Int, rows: Int) {}
        override suspend fun close() {}
    }

    private class FakeShell(private val pty: PtySession) : ShellExecutor {
        override val id = "fake"
        override suspend fun run(
            command: String,
            cwd: Path,
            timeoutMs: Long,
            env: Map<String, String>,
        ): ShellResult = ShellResult(0, "")
        override suspend fun openPty(command: String, cwd: Path): PtySession = pty
    }
}
