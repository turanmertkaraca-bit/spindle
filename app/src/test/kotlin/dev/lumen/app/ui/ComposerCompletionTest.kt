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
        compose.waitForIdle()

        compose.onNodeWithTag("file-suggestions").assertIsDisplayed()
        compose.onNodeWithText("src/App.kt").assertIsDisplayed()

        compose.onNodeWithTag("file-suggestion-0").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()

        assertEquals("@src/App.kt ", input.value)
        check(compose.onAllNodesWithTag("file-suggestions").fetchSemanticsNodes().isEmpty()) {
            "the popup should hide once the token is completed"
        }
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

        compose.onNodeWithTag("peek-backlinks").assertIsDisplayed()
        compose.onNodeWithText("backlinks").assertIsDisplayed()
        compose.onNodeWithText("see src/App.kt").assertIsDisplayed()

        compose.onNodeWithTag("backlink-0").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals(0, jumped)
    }
}
