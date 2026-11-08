package dev.lumen.app.ui

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import dev.lumen.app.Backlink
import dev.lumen.app.FilePeek
import dev.lumen.app.ui.model.StepKind
import dev.lumen.app.ui.model.UiStep
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * The composer's `@` completion and the peek sheet's backlinks section, driven
 * through [LumenChatScreen] with pure hoisted state (no view model needed).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ComposerCompletionTest {

    @get:Rule
    val compose = createComposeRule()

    private val viewport = Modifier.size(width = 360.dp, height = 640.dp)

    @Test
    fun `typing an at-token shows completions and picking one completes the path`() {
        val input = mutableStateOf("")
        compose.setContent {
            LumenChatScreen(
                steps = emptyList(),
                input = input.value,
                busy = false,
                error = null,
                modifier = viewport,
                ambient = false,
                onInput = { input.value = it },
                onCompleteFiles = { prefix ->
                    if (prefix == "src") listOf("src/App.kt", "src/lib/Util.kt") else emptyList()
                },
            )
        }

        compose.onNodeWithTag("composer").performClick()
        compose.onNodeWithTag("composer").performTextInput("@src")
        // The completion walk is a `withContext(Dispatchers.IO)` inside a
        // `LaunchedEffect`, so `waitForIdle()` alone does not guarantee it has
        // landed — it can return before the IO hop completes. Poll until the
        // popup exists instead of racing it (this is the class of flake that made
        // the suite fail on one sha and pass on the next).
        awaitTag("file-suggestions")

        compose.onNodeWithTag("file-suggestions").assertIsDisplayed()
        compose.onNodeWithText("src/App.kt").assertIsDisplayed()

        compose.onNodeWithTag("file-suggestion-0").performSemanticsAction(SemanticsActions.OnClick)
        // The popup hides once the completed token has been pushed back through
        // `onInput` and re-derived; poll rather than assume one idle pass covers it.
        awaitNoTag("file-suggestions")

        assertEquals("@src/App.kt ", input.value)
    }

    @Test
    fun `normal typing without an at-token shows no completions`() {
        compose.setContent {
            LumenChatScreen(
                steps = emptyList(),
                input = "hello world",
                busy = false,
                error = null,
                modifier = viewport,
                ambient = false,
                onCompleteFiles = { listOf("src/App.kt") },
            )
        }
        check(compose.onAllNodesWithTag("file-suggestions").fetchSemanticsNodes().isEmpty()) {
            "no popup without a trailing at-token"
        }
    }

    @Test
    fun `the peek overlay renders a backlinks section that can jump`() {
        var jumped: Int? = null
        compose.setContent {
            LumenChatScreen(
                steps = listOf(UiStep("a", StepKind.ASSISTANT, "ASSISTANT", "x", "answer", "the answer")),
                input = "",
                busy = false,
                error = null,
                modifier = viewport,
                ambient = false,
                peek = FilePeek("src/App.kt", listOf("fun main() {}")),
                onBacklinks = { listOf(Backlink(0, "ASSISTANT", "see src/App.kt", touched = true)) },
                onJumpToStep = { jumped = it },
            )
        }
        compose.waitForIdle()

        compose.onNodeWithTag("peek-backlinks", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("backlinks", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("see src/App.kt", useUnmergedTree = true).assertExists()

        compose.onNodeWithTag("backlink-0", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals(0, jumped)
    }

    /** Poll until [tag] exists, or fail with a clear message. */
    private fun awaitTag(tag: String) {
        val deadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < deadline) {
            compose.waitForIdle()
            if (compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()) return
            Thread.sleep(20)
        }
        throw AssertionError("'$tag' never appeared")
    }

    /** Poll until [tag] is gone, or fail with a clear message. */
    private fun awaitNoTag(tag: String) {
        val deadline = System.currentTimeMillis() + 5_000L
        var remaining = -1
        while (System.currentTimeMillis() < deadline) {
            compose.waitForIdle()
            remaining = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size
            if (remaining == 0) return
            Thread.sleep(20)
        }
        throw AssertionError("'$tag' was still present ($remaining nodes)")
    }
}
