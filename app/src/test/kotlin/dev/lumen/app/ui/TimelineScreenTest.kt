package dev.lumen.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import dev.lumen.app.ui.model.StepKind
import dev.lumen.app.ui.model.UiStep
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Behavioral tests for the timeline, run on a plain JVM by Robolectric — this is
 * what CI verifies. They encode the contract the user asked for:
 *   - the row at the bottom of the viewport is the open one
 *   - scrolling moves which row is open
 *   - collapsed rows show a one-line summary, the open one shows its body
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TimelineScreenTest {

    @get:Rule
    val compose = createComposeRule()

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
        val count = compose.onAllNodesWithText(text).fetchSemanticsNodes().size
        check(count == 0) { "expected no node with text '$text', found $count" }
    }

    @Test
    fun `renders the demo without crashing`() {
        compose.setContent { TimelineScreen(sampleDemoSteps()) }
        compose.onNodeWithText("YOU").assertIsDisplayed()
    }

    @Test
    fun `the bottom row is open and shows its body, earlier rows are collapsed`() {
        compose.setContent { TimelineScreen(steps(3)) }

        // With three short rows all on screen, the last is at the bottom -> open.
        compose.onNodeWithText("BODY 2 full text").assertIsDisplayed()
        // An earlier row is collapsed -> summary only, body hidden.
        compose.onNodeWithText("summary 0").assertIsDisplayed()
        noNode("BODY 0 full text")
    }

    @Test
    fun `scrolling the list changes which row is open`() {
        compose.setContent { TimelineScreen(steps(30)) }

        // At rest, row 0 is not the open one (it is at the top).
        noNode("BODY 0 full text")

        // Scroll to the top row; it should become the open one.
        compose.onNodeWithTag("timeline").performScrollToIndex(0)
        compose.onNodeWithText("BODY 0 full text").assertIsDisplayed()
    }

    @Test
    fun `tool rows expose their sub-rows while open`() {
        val s = listOf(
            UiStep(
                "t", StepKind.TOOL, "1 RUN TESTS", "x",
                summary = "571 tests - 0 failed",
                body = "gradle testDebugUnitTest",
                rows = listOf("compile" to "assembleDebug", "test" to "571 passed"),
            ),
        )
        compose.setContent { TimelineScreen(s) }
        compose.onNodeWithText("compile").assertIsDisplayed()
        compose.onNodeWithText("test").assertIsDisplayed()
    }
}
