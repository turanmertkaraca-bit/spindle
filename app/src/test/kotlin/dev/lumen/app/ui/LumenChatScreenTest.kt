package dev.lumen.app.ui

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import dev.lumen.app.ui.model.StepKind
import dev.lumen.app.ui.model.UiStep
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

/**
 * Behavioral tests for the spine timeline, run on a plain JVM by Robolectric:
 * only the focused node shows text, tool sub-rows surface, long bodies are
 * shown in full, and the composer/error affordances stay reachable.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LumenChatScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val viewport = Modifier.size(width = 360.dp, height = 480.dp)

    private fun steps(n: Int): List<UiStep> = (0 until n).map { i ->
        UiStep(
            id = "s$i",
            kind = if (i % 5 == 4) StepKind.SUBAGENT else StepKind.YOU,
            label = "STEP $i",
            tag = "tag$i",
            summary = "summary $i",
            body = "BODY $i full text",
        )
    }

    private fun noNode(text: String) {
        val c = compose.onAllNodesWithText(text).fetchSemanticsNodes().size
        check(c == 0) { "expected no node with text '$text', found $c" }
    }

    @Test
    fun `renders the focused step and the composer`() {
        compose.setContent {
            LumenChatScreen(steps(4), input = "", busy = false, error = null, modifier = viewport, ambient = false, forceOpenIndex = 0)
        }
        compose.onNodeWithText("STEP 0").assertIsDisplayed()
        compose.onNodeWithText("BODY 0 full text").assertIsDisplayed()
        compose.onNodeWithTag("composer").assertIsDisplayed()
    }

    @Test
    fun `only the focused step is bloomed`() {
        compose.setContent {
            LumenChatScreen(steps(6), input = "", busy = false, error = null, modifier = viewport, ambient = false, forceOpenIndex = 0)
        }
        compose.onNodeWithText("BODY 0 full text").assertIsDisplayed()
        noNode("BODY 1 full text")
        noNode("BODY 5 full text")
    }

    @Test
    fun `tool rows expose their sub-rows while bloomed`() {
        val s = listOf(
            UiStep(
                "t", StepKind.TOOL, "1 RUN TESTS", "x",
                summary = "571 tests - 0 failed",
                body = "gradle testDebugUnitTest",
                rows = listOf("compile" to "assembleDebug", "verify" to "re-render"),
            ),
        )
        compose.setContent {
            LumenChatScreen(s, input = "", busy = false, error = null, modifier = viewport, ambient = false, forceOpenIndex = 0)
        }
        check(compose.onAllNodesWithText("compile", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()) {
            "bloomed tool row should expose its 'compile' sub-row"
        }
        check(compose.onAllNodesWithText("verify", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()) {
            "bloomed tool row should expose its 'verify' sub-row"
        }
    }

    @Test
    fun `send button is present and reflects busy state`() {
        compose.setContent {
            LumenChatScreen(steps(2), input = "hi", busy = false, error = null, modifier = viewport, ambient = false, forceOpenIndex = 1)
        }
        compose.onNodeWithTag("send").assertIsDisplayed()
    }

    @Test
    fun `stop replaces send while busy`() {
        compose.setContent {
            LumenChatScreen(steps(2), input = "", busy = true, error = null, modifier = viewport, ambient = false, forceOpenIndex = 1)
        }
        compose.onNodeWithTag("stop").assertIsDisplayed()
    }

    @Test
    fun `an empty session shows an intentional start, not a blank`() {
        compose.setContent {
            LumenChatScreen(emptyList(), input = "", busy = false, error = null, modifier = viewport, ambient = false)
        }
        compose.onNodeWithText("lumen").assertIsDisplayed()
        compose.onNodeWithText("ask the agent to begin").assertIsDisplayed()
    }

    @Test
    fun `an auth error offers a way to fix the key`() {
        var edited = false
        compose.setContent {
            LumenChatScreen(
                emptyList(), input = "", busy = false,
                error = "OpenAI HTTP 401: Missing Authentication header",
                modifier = viewport, ambient = false,
                onEditKey = { edited = true },
            )
        }
        compose.onNodeWithText("update key").assertIsDisplayed().performClick()
        assertTrue(edited, "update key should route to the key screen")
    }

    @Test
    fun `a long focused body is shown in full`() {
        val long = (1..120).joinToString("\n") { "line $it of a very long focused answer" }
        compose.setContent {
            LumenChatScreen(
                listOf(UiStep("a", StepKind.ASSISTANT, "ASSISTANT", "x", "long", long)),
                input = "", busy = false, error = null, modifier = viewport, ambient = false,
                forceOpenIndex = 0,
            )
        }
        compose.onNodeWithText(long).assertExists()
    }

    @Test
    fun `theme toggle is available`() {
        compose.setContent {
            LumenChatScreen(steps(2), input = "", busy = false, error = null, modifier = viewport, ambient = false, forceOpenIndex = 0, onToggleTheme = {})
        }
        compose.onNodeWithTag("theme").assertIsDisplayed()
    }

    @Test
    fun `a lone thinking block is shown expanded`() {
        compose.setContent {
            LumenChatScreen(
                listOf(UiStep("t", StepKind.THINKING, "THINKING", "x", "reasoning", "the full reasoning text")),
                input = "", busy = true, error = null, modifier = viewport, ambient = false, forceOpenIndex = 0,
            )
        }
        compose.onNodeWithText("the full reasoning text").assertIsDisplayed()
    }

    @Test
    fun `an answered think collapses to a header and expands on tap`() {
        val think = "weigh the options carefully"
        compose.setContent {
            LumenChatScreen(
                listOf(
                    UiStep("u", StepKind.YOU, "YOU", "x", "hi", "hi"),
                    UiStep("t", StepKind.THINKING, "THINKING", "x", "reasoning", think),
                    UiStep("a", StepKind.ASSISTANT, "ASSISTANT", "x", "answer", "the answer"),
                ),
                input = "", busy = false, error = null, modifier = viewport, ambient = false, forceOpenIndex = 1,
            )
        }
        compose.onNodeWithText("thinking · ${think.length} chars").assertIsDisplayed()
        noNode(think)
        compose.onNodeWithTag("think-toggle").performClick()
        compose.waitForIdle()
        compose.onNodeWithText(think).assertExists()
    }
}
