package dev.lumen.app.ui

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.lumen.app.ChatViewModel
import dev.lumen.app.data.KeyStore
import dev.lumen.app.ui.model.StepKind
import dev.lumen.app.ui.model.UiStep
import dev.spindle.core.store.InMemorySessionStore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * File references in assistant markdown open a peek, backed by the real
 * [ChatViewModel] and its pure resolver wiring. A path that exists links and
 * opens; one that does not stays inert.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FileReferenceTest {

    @get:Rule
    val compose = createComposeRule()

    private fun tempWorkspace(): File {
        val dir = Files.createTempDirectory("lumen-ws").toFile()
        dir.deleteOnExit()
        return dir
    }

    private fun viewModel(dir: File): ChatViewModel = ChatViewModel(
        workspace = dir.toPath(),
        keys = KeyStore(ApplicationProvider.getApplicationContext()),
        store = InMemorySessionStore(),
    )

    private fun writeFile(dir: File, rel: String, content: String): File {
        val f = File(dir, rel)
        f.parentFile?.mkdirs()
        f.writeText(content)
        return f
    }

    private fun step(body: String) =
        listOf(UiStep("a1", StepKind.ASSISTANT, "ASSISTANT", "x", "answer", body))

    @Test
    fun `an existing reference links and opens the peek at its line`() {
        val dir = tempWorkspace()
        writeFile(dir, "src/App.kt", (1..50).joinToString("\n") { "line $it" })
        val vm = viewModel(dir)

        compose.setContent {
            val state by vm.state.collectAsState()
            LumenChatScreen(
                steps = step("The fix is in src/App.kt:42 — set the cookie first."),
                input = "", busy = false, error = null,
                modifier = Modifier.size(width = 360.dp, height = 640.dp),
                ambient = false,
                cwd = vm.workspacePath,
                exists = vm::fileExists,
                onOpenFile = vm::openFile,
                onClosePeek = vm::closePeek,
                peek = state.peek,
            )
        }

        // The reference renders and carries a click action.
        val link = compose.onNodeWithText("src/App.kt:42", substring = true).fetchSemanticsNode()
        assertTrue(link.config.contains(SemanticsActions.OnClick), "reference should be clickable")

        compose.onNodeWithText("src/App.kt:42", substring = true).performClick()
        compose.waitForIdle()

        val peek = vm.state.value.peek
        assertNotNull(peek, "clicking a reference should open the peek")
        assertEquals("src/App.kt", peek.path)
        assertEquals(42, peek.highlight)
        assertEquals("line 42", peek.lines[41])
        assertFalse(peek.truncated)

        compose.onNodeWithTag("file-peek").assertExists()
        compose.onNodeWithTag("peek-path", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("peek-highlight", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `a non-existent path is not linked`() {
        val dir = tempWorkspace()
        val vm = viewModel(dir)

        compose.setContent {
            val state by vm.state.collectAsState()
            LumenChatScreen(
                steps = step("Missing file missing/Nope.kt is not there."),
                input = "", busy = false, error = null,
                modifier = Modifier.size(width = 360.dp, height = 640.dp),
                ambient = false,
                cwd = vm.workspacePath,
                exists = vm::fileExists,
                onOpenFile = vm::openFile,
                peek = state.peek,
            )
        }

        val node = compose.onNodeWithText("missing/Nope.kt", substring = true).fetchSemanticsNode()
        assertFalse(node.config.contains(SemanticsActions.OnClick), "dead path should not link")
        assertNull(vm.state.value.peek)
    }

    @Test
    fun `openFile refuses to escape the workspace`() {
        val dir = tempWorkspace()
        val vm = viewModel(dir)
        vm.openFile("../secret.txt")
        assertNull(vm.state.value.peek, "an escaping path must not open")
        assertNotNull(vm.state.value.error, "an escaping path should surface an error")
    }

    @Test
    fun `openFile caps long files and notes truncation`() {
        val dir = tempWorkspace()
        writeFile(dir, "big.txt", (1..(ChatViewModel.MAX_PEEK_LINES + 25)).joinToString("\n") { "row $it" })
        val vm = viewModel(dir)
        vm.openFile("big.txt")
        val peek = vm.state.value.peek
        assertNotNull(peek)
        assertEquals(ChatViewModel.MAX_PEEK_LINES, peek.lines.size)
        assertTrue(peek.truncated, "a capped file should be flagged as truncated")
    }
}
