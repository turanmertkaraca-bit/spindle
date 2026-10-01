package dev.lumen.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.lumen.app.data.KeyStore
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
import dev.spindle.core.store.InMemorySessionStore
import kotlinx.coroutines.Dispatchers
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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Backlinks over the current session, plus the `@` file completion index. Both
 * are wired to the real [ChatViewModel] on a temp workspace, with an unconfined
 * main so the init/open coroutines settle inline.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BacklinksTest {

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

    private fun tmp(): File = Files.createTempDirectory("lumen-backlinks").toFile()

    private fun write(dir: File, rel: String, text: String) {
        val f = File(dir, rel)
        f.parentFile?.mkdirs()
        f.writeText(text)
    }

    @Test
    fun `backlinksFor finds an assistant reference and a tool metadata path, marks touched`() {
        val dir = tmp()
        write(dir, "src/App.kt", "fun main() {}")
        val store = InMemorySessionStore()
        val sid = SessionId("ses_backlinks")
        runBlocking {
            store.createSession(Session(sid, "t", dir.path, 0, 0))
            store.appendMessage(
                Message(
                    id = MessageId("m1"), sessionId = sid, role = Role.ASSISTANT,
                    parts = listOf(Part.Text(PartId("p1"), "the fix lives in src/App.kt today")),
                    createdAt = 1,
                ),
            )
            store.appendMessage(
                Message(
                    id = MessageId("m2"), sessionId = sid, role = Role.ASSISTANT,
                    parts = listOf(
                        Part.Tool(
                            PartId("p2"),
                            ToolCall("c1", "edit", "{}"),
                            ToolState.DONE,
                            ToolResult("c1", "ok", metadata = mapOf("path" to "src/App.kt")),
                        ),
                    ),
                    createdAt = 2,
                ),
            )
        }

        val vm = ChatViewModel(dir.toPath(), keys(), store)
        vm.openSession(sid.value)

        val links = vm.backlinksFor("src/App.kt")
        assertEquals(2, links.size, "assistant prose and tool metadata should both backlink")
        assertEquals(listOf(0, 1), links.map { it.stepIndex }, "timeline order")
        assertEquals("ASSISTANT", links[0].label)
        assertEquals("EDIT", links[1].label)
        assertTrue(links.none { it.touched }, "no changes yet, so nothing is touched")

        vm.recordChange(FileEdit(id = "e1", sessionId = sid, path = "src/App.kt", added = 3, removed = 1))
        val touched = vm.backlinksFor("src/App.kt")
        assertEquals(2, touched.size)
        assertTrue(touched.all { it.touched }, "the edit for the path marks every backlink touched")
    }

    @Test
    fun `backlinksFor ignores a path that only occurs inside a longer token`() {
        val dir = tmp()
        write(dir, "src/App.kt", "x")
        val store = InMemorySessionStore()
        val sid = SessionId("ses_longer")
        runBlocking {
            store.createSession(Session(sid, "t", dir.path, 0, 0))
            store.appendMessage(
                Message(
                    MessageId("m1"), sid, Role.ASSISTANT,
                    listOf(Part.Text(PartId("p1"), "see src/MyApp.kt here")), 1,
                ),
            )
        }
        val vm = ChatViewModel(dir.toPath(), keys(), store)
        vm.openSession(sid.value)

        assertTrue(vm.backlinksFor("src/App.kt").isEmpty(), "MyApp.kt must not match App.kt")
    }

    @Test
    fun `completeFiles ranks prefix matches and stays inside the workspace`() {
        val dir = tmp()
        write(dir, "src/App.kt", "")
        write(dir, "src/main/Util.kt", "")
        write(dir, "test/AppTest.kt", "")
        write(dir, "README.md", "")
        File(dir, ".git").mkdirs()
        write(dir, ".git/config", "")
        File(dir, "node_modules").mkdirs()
        write(dir, "node_modules/src/dep.js", "")

        val vm = ChatViewModel(dir.toPath(), keys(), InMemorySessionStore())

        val hits = vm.completeFiles("src")
        assertEquals(listOf("src/App.kt", "src/main/Util.kt"), hits)
        assertTrue(hits.all { !it.startsWith("/") && !it.contains("..") }, "only workspace-relative paths")
        assertFalse(hits.any { it.startsWith(".git") || it.startsWith("node_modules") }, "heavy dirs are skipped")

        val root = vm.completeFiles("")
        assertTrue(root.contains("README.md"), "an empty prefix keeps root entries")
        assertTrue(root.none { it.contains('/') }, "an empty prefix yields root-level entries")
    }
}
