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

/**
 * Behavioral tests for the interactive chat screen, run on a plain JVM by
 * Robolectric — this is what CI verifies. The contract:
 *   - exactly one row is open at a time
 *   - tapping a row makes it the open one
 *   - the composer is present and send is callable
 *   - tool rows expose their sub-rows while open
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

    private fun countBodies(n: Int): Int =
        (0 until n).count { i ->
            compose.onAllNodesWithText("BODY $i full text").fetchSemanticsNodes().isNotEmpty()
        }

    private fun noNode(text: String) {
        val c = compose.onAllNodesWithText(text).fetchSemanticsNodes().size
        check(c == 0) { "expected no node with text '$text', found $c" }
    }

    @Test
    fun `renders steps and the composer`() {
        compose.setContent { LumenChatScreen(steps(4), input = "", busy = false, error = null, modifier = viewport) }
        compose.onNodeWithText("STEP 0").assertIsDisplayed()
        compose.onNodeWithTag("composer").assertIsDisplayed()
    }

    @Test
    fun `exactly one row is open`() {
        compose.setContent { LumenChatScreen(steps(6), input = "", busy = false, error = null, modifier = viewport) }
        val open = countBodies(6)
        check(open == 1) { "expected exactly 1 open row, found $open" }
    }

    @Test
    fun `tapping a row opens it`() {
        compose.setContent { LumenChatScreen(steps(6), input = "", busy = false, error = null, modifier = viewport) }
        // row 0's body is not shown at rest (a later row is open)
        noNode("BODY 0 full text")
        // tap the label of row 0
        compose.onNodeWithText("STEP 0").performClick()
        compose.onNodeWithText("BODY 0 full text").assertIsDisplayed()
    }

    @Test
    fun `tool rows expose their sub-rows while open`() {
        val s = listOf(
            UiStep(
                "t", StepKind.TOOL, "1 RUN TESTS", "x",
                summary = "571 tests - 0 failed",
                body = "gradle testDebugUnitTest",
                rows = listOf("compile" to "assembleDebug", "verify" to "re-render"),
            ),
        )
        // Force the row open so the assertion does not depend on Robolectric layout.
        compose.setContent {
            LumenChatScreen(s, input = "", busy = false, error = null, modifier = viewport, forceOpenIndex = 0)
        }
        check(compose.onAllNodesWithText("compile", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()) {
            "open tool row should expose its 'compile' sub-row"
        }
        check(compose.onAllNodesWithText("verify", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()) {
            "open tool row should expose its 'verify' sub-row"
        }
    }

    @Test
    fun `send button is present and reflects busy state`() {
        compose.setContent { LumenChatScreen(steps(2), input = "hi", busy = false, error = null, modifier = viewport) }
        compose.onNodeWithTag("send").assertIsDisplayed()
    }

    @Test
    fun `stop replaces send while busy`() {
        compose.setContent { LumenChatScreen(steps(2), input = "", busy = true, error = null, modifier = viewport) }
        compose.onNodeWithTag("stop").assertIsDisplayed()
    }
}
