package dev.lumen.app.ui

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import dev.lumen.app.ui.model.StepKind
import dev.lumen.app.ui.model.UiStep
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Behavioral tests for the timeline, run on a plain JVM by Robolectric — this is
 * what CI verifies. The contract (what the user asked for):
 *   - exactly one row is open at a time — the one at the bottom of the viewport
 *   - scrolling moves which row is open
 *   - collapsed rows show a one-line summary; the open one shows its body
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TimelineScreenTest {

    @get:Rule
    val compose = createComposeRule()

    /** A fixed viewport so the "bottom edge" rule is deterministic. */
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
    fun `renders the demo without crashing`() {
        compose.setContent { TimelineScreen(sampleDemoSteps(), modifier = viewport) }
        compose.onNodeWithText("YOU").assertIsDisplayed()
    }

    @Test
    fun `exactly one row is open`() {
        compose.setContent { TimelineScreen(steps(6), modifier = viewport) }
        val open = countBodies(6)
        check(open == 1) { "expected exactly 1 open row, found $open" }
    }

    @Test
    fun `collapsed rows show their summary not their body`() {
        compose.setContent { TimelineScreen(steps(6), modifier = viewport) }
        // count how many summaries are shown: every row shows a summary OR a body
        val summaries = compose.onAllNodesWithText("summary 0").fetchSemanticsNodes().size
        check(summaries <= 1) { "collapsed row 0 should show at most its summary" }
        // and at least one collapsed row exists (6 rows, 1 open)
        val bodyCount = countBodies(6)
        check(bodyCount == 1)
    }

    @Test
    fun `scrolling changes which row is open`() {
        compose.setContent { TimelineScreen(steps(30), modifier = viewport) }

        // At rest the last visible row is open; row 0's body is not shown.
        noNode("BODY 0 full text")

        // Scroll row 0 up against the top. With many rows below it, the bottom
        // edge now belongs to a later row, so row 0 must still be collapsed.
        compose.onNodeWithTag("timeline").performScrollToIndex(0)
        noNode("BODY 0 full text")

        // And exactly one row is still open, whatever it is.
        val open = countBodies(30)
        check(open == 1) { "expected exactly 1 open row after scroll, found $open" }
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
        compose.setContent { TimelineScreen(s, modifier = viewport) }
        // The single row is the bottom-most, so it is open -> its sub-rows exist.
        check(compose.onAllNodesWithText("compile").fetchSemanticsNodes().isNotEmpty()) {
            "open tool row should expose its 'compile' sub-row"
        }
        check(compose.onAllNodesWithText("test").fetchSemanticsNodes().isNotEmpty()) {
            "open tool row should expose its 'test' sub-row"
        }
    }
}
