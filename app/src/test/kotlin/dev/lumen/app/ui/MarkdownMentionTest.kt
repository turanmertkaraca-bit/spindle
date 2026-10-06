package dev.lumen.app.ui

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import dev.spindle.core.model.Usage
import dev.spindle.core.refs.FileKind
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Assistant mentions: with a kind gate, directory references link exactly like
 * files (opening at line null); and because existence is not part of the memo
 * key, a file the agent creates after first render links once the workspace
 * revision bumps.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MarkdownMentionTest {

    @get:Rule
    val compose = createComposeRule()

    private val viewport = Modifier.size(width = 360.dp, height = 640.dp)

    @Test
    fun `a directory mention links when a kind gate is supplied`() {
        var opened: String? = null
        var openedLine: Int? = -1
        compose.setContent {
            MarkdownBody(
                markdown = "See core/ for the modules.",
                colors = LumenColors.Light,
                modifier = viewport,
                cwd = "/ws",
                fileKind = { path -> if (path == "core") FileKind.DIRECTORY else null },
                onOpenFile = { path, line ->
                    opened = path
                    openedLine = line
                },
            )
        }
        val node = compose.onNodeWithText("core/", substring = true).fetchSemanticsNode()
        assertTrue(node.config.contains(SemanticsActions.OnClick), "a directory mention should link")

        compose.onNodeWithText("core/", substring = true)
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals("core", opened)
        assertNull(openedLine, "a directory opens at no line")
    }

    @Test
    fun `a directory stays inert without a kind gate`() {
        compose.setContent {
            MarkdownBody(
                markdown = "See core/ for the modules.",
                colors = LumenColors.Light,
                modifier = viewport,
                cwd = "/ws",
                exists = { it != "core" },
                onOpenFile = { _, _ -> },
            )
        }
        val node = compose.onNodeWithText("core/", substring = true).fetchSemanticsNode()
        assertFalse(node.config.contains(SemanticsActions.OnClick), "files-only mode must not link a directory")
    }

    @Test
    fun `a file created after first render links once the revision bumps`() {
        val revision = mutableStateOf(0)
        val created = mutableStateOf(false)
        compose.setContent {
            MarkdownBody(
                markdown = "The new file src/New.kt is ready.",
                colors = LumenColors.Light,
                modifier = viewport,
                cwd = "/ws",
                exists = { path -> path == "src/New.kt" && created.value },
                fileRevision = revision.value,
                onOpenFile = { _, _ -> },
            )
        }
        // The file does not exist yet: the mention is plain text.
        assertFalse(
            compose.onNodeWithText("src/New.kt", substring = true)
                .fetchSemanticsNode().config.contains(SemanticsActions.OnClick),
        )
        // The agent creates the file and the workspace revision advances.
        compose.runOnIdle {
            created.value = true
            revision.value = 1
        }
        compose.waitForIdle()
        assertTrue(
            compose.onNodeWithText("src/New.kt", substring = true)
                .fetchSemanticsNode().config.contains(SemanticsActions.OnClick),
            "a revision bump must re-resolve the stale mention",
        )
    }

    @Test
    fun `the model chip shows the basename and opens the picker`() {
        var picked = 0
        compose.setContent {
            LumenChatScreen(
                steps = emptyList(),
                input = "", busy = false, error = null,
                modifier = Modifier.size(width = 400.dp, height = 640.dp),
                ambient = false,
                model = "opencode-go/claude-3-5-sonnet",
                onModel = { picked++ },
            )
        }
        compose.onNodeWithTag("model-chip", useUnmergedTree = true).assertIsDisplayed().performClick()
        compose.waitForIdle()
        assertEquals(1, picked)
        compose.onNodeWithText("claude-3-5-sonnet", substring = true).assertIsDisplayed()
    }

    @Test
    fun `the usage meter is compact when a context window is known`() {
        compose.setContent {
            LumenChatScreen(
                steps = emptyList(),
                input = "", busy = false, error = null,
                modifier = viewport,
                ambient = false,
                usage = Usage(inputTokens = 1200, outputTokens = 300, costUsd = 0.012),
                contextWindow = 200_000,
                nextCostUsd = 0.0042,
                newTokens = 42,
            )
        }
        compose.onNodeWithTag("usage-meter", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("42 new", substring = true).assertIsDisplayed()
        compose.onNodeWithText("ctx 200000", substring = true).assertIsDisplayed()
    }
}
