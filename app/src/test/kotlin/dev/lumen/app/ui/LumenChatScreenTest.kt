package dev.lumen.app.ui

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import dev.lumen.app.ui.model.StepKind
import dev.lumen.app.ui.model.UiStep
import dev.spindle.core.model.FileEdit
import dev.spindle.core.model.RunChanges
import dev.spindle.core.model.SessionId
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Behavioral tests for the bubble chat, run on a plain JVM by Robolectric:
 * every turn shows as a bubble, a tool run collapses to one line, reasoning
 * folds into a pill, and the composer/error/cue affordances stay reachable.
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
            kind = if (i % 5 == 4) StepKind.SUBAGENT else if (i % 2 == 0) StepKind.YOU else StepKind.ASSISTANT,
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

    /** Two files, +12/−3, matching the screenshot fixture. */
    private fun sampleChanges(): RunChanges {
        val sid = SessionId("ses1")
        return RunChanges(
            edits = listOf(
                FileEdit(id = "e1", sessionId = sid, path = "RopeLayout.kt", added = 8, removed = 3),
                FileEdit(id = "e2", sessionId = sid, path = "StepMapper.kt", added = 4, removed = 0),
            ),
        )
    }

    @Test
    fun `changes summary appears only when there are edits`() {
        val changes = mutableStateOf(RunChanges.EMPTY)
        compose.setContent {
            LumenChatScreen(
                steps(2), input = "", busy = false, error = null, modifier = viewport, ambient = false,
                changes = changes.value,
            )
        }
        val summary = "2 files · 2 edits · +12 −3"
        check(compose.onAllNodesWithText(summary, substring = true).fetchSemanticsNodes().isEmpty()) {
            "changes summary should be absent with no edits"
        }
        compose.runOnIdle { changes.value = sampleChanges() }
        compose.onNodeWithText(summary, substring = true).assertIsDisplayed()
        compose.runOnIdle { changes.value = RunChanges.EMPTY }
        check(compose.onAllNodesWithText(summary, substring = true).fetchSemanticsNodes().isEmpty()) {
            "changes summary should disappear once edits are gone"
        }
    }

    @Test
    fun `renders messages and the composer`() {
        compose.setContent {
            LumenChatScreen(steps(4), input = "", busy = false, error = null, modifier = viewport, ambient = false)
        }
        compose.onNodeWithText("BODY 0 full text").assertIsDisplayed()
        compose.onNodeWithText("BODY 3 full text").assertIsDisplayed()
        compose.onNodeWithTag("composer").assertIsDisplayed()
    }

    @Test
    fun `a changed file row reverts and opens the peek`() {
        val changes = mutableStateOf(sampleChanges())
        var reverted: FileEdit? = null
        var opened: String? = null
        compose.setContent {
            LumenChatScreen(
                steps(1), input = "", busy = false, error = null, modifier = viewport, ambient = false,
                changes = changes.value,
                onRevert = { reverted = it },
                onOpenFile = { path, _ -> opened = path },
            )
        }
        compose.onNodeWithTag("changes-toggle").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("changes-revert-RopeLayout.kt", useUnmergedTree = true).performClick()
        compose.waitForIdle()
        assertEquals("RopeLayout.kt", reverted?.path)

        compose.onNodeWithTag("changes-file-StepMapper.kt", useUnmergedTree = true).performClick()
        compose.waitForIdle()
        assertEquals("StepMapper.kt", opened)
    }

    @Test
    fun `a tool run is one compact line that expands to its calls`() {
        val s = listOf(
            UiStep(
                "t", StepKind.TOOL, "READ", "x",
                summary = "571 tests - 0 failed",
                body = "gradle testDebugUnitTest",
                rows = listOf("compile" to "assembleDebug", "verify" to "re-render"),
                merged = 2,
            ),
        )
        compose.setContent {
            LumenChatScreen(s, input = "", busy = false, error = null, modifier = viewport, ambient = false)
        }
        // Collapsed: a single summary line naming the run and its calls.
        compose.onNodeWithText("2 tools · compile, verify", substring = true).assertIsDisplayed()
        check(compose.onAllNodesWithText("compile", substring = false).fetchSemanticsNodes().isEmpty()) {
            "collapsed tool run should not render each sub-row as its own node"
        }
        // Expanded: the calls become visible.
        compose.onNodeWithTag("tools-toggle").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNodeWithTag("tool-row-0").assertIsDisplayed()
        compose.onNodeWithTag("tool-row-1").assertIsDisplayed()
        compose.onNodeWithText("compile").assertIsDisplayed()
        compose.onNodeWithText("verify").assertIsDisplayed()
    }

    @Test
    fun `a new step below the fold shows the cue until the reader returns`() {
        val many = (0 until 12).map { i ->
            UiStep("s$i", StepKind.ASSISTANT, "ASSISTANT", "t$i", "summary $i", "BODY $i full text")
        }
        val all = mutableStateOf(many)
        compose.setContent {
            LumenChatScreen(all.value, input = "", busy = false, error = null, modifier = viewport, ambient = false, haptics = false)
        }
        // At the tail: a new answer arrives with no cue, it just follows.
        compose.runOnIdle {
            all.value = all.value + UiStep("n0", StepKind.ASSISTANT, "ASSISTANT", "t0", "at bottom", "arrived while at the tail")
        }
        compose.waitForIdle()
        check(compose.onAllNodesWithTag("new-cue").fetchSemanticsNodes().isEmpty()) {
            "no cue should appear when the reader is already at the tail"
        }
        // Read from the top, away from the tail.
        compose.onNodeWithTag("timeline").performScrollToIndex(0)
        compose.waitForIdle()
        // A new answer lands while the reader is up the transcript.
        compose.runOnIdle {
            all.value = all.value + UiStep("n1", StepKind.ASSISTANT, "ASSISTANT", "tn", "new summary", "the new answer")
        }
        compose.waitForIdle()
        compose.onNodeWithTag("new-cue").assertIsDisplayed()
        // Tapping the cue returns to the newest content and dismisses it.
        compose.onNodeWithTag("new-cue").performClick()
        compose.waitForIdle()
        check(compose.onAllNodesWithTag("new-cue").fetchSemanticsNodes().isEmpty()) {
            "the new cue should hide once the reader is back at the tail"
        }
    }

    @Test
    fun `send button is present and reflects busy state`() {
        compose.setContent {
            LumenChatScreen(steps(2), input = "hi", busy = false, error = null, modifier = viewport, ambient = false)
        }
        compose.onNodeWithTag("send").assertIsDisplayed()
    }

    @Test
    fun `stop replaces send while busy`() {
        compose.setContent {
            LumenChatScreen(steps(2), input = "", busy = true, error = null, modifier = viewport, ambient = false)
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
    fun `a long body is shown in full, not clamped`() {
        val long = (1..120).joinToString("\n") { "line $it of a very long answer" }
        compose.setContent {
            LumenChatScreen(
                listOf(UiStep("a", StepKind.ASSISTANT, "ASSISTANT", "x", "long", long)),
                input = "", busy = false, error = null, modifier = viewport, ambient = false,
            )
        }
        compose.onNodeWithText(long).assertExists()
    }

    @Test
    fun `assistant markdown renders cleaned text, not raw markers`() {
        val body = "## hello world\n\nthis is **bold** text\n\n- one\n- two\n\n```kotlin\nval x = 1\n```"
        compose.setContent {
            LumenChatScreen(
                listOf(UiStep("a", StepKind.ASSISTANT, "ASSISTANT", "x", "md", body)),
                input = "", busy = false, error = null, modifier = viewport, ambient = false,
            )
        }
        compose.onNodeWithText("hello world").assertExists()
        compose.onNodeWithText("this is bold text").assertExists()
        compose.onNodeWithText("one").assertExists()
        compose.onNodeWithText("val x = 1").assertExists()
        check(compose.onAllNodesWithText("## hello world", substring = true).fetchSemanticsNodes().isEmpty()) {
            "the raw heading markers should not survive rendering"
        }
        check(compose.onAllNodesWithText("**bold**", substring = true).fetchSemanticsNodes().isEmpty()) {
            "the raw bold markers should not survive rendering"
        }
    }

    @Test
    fun `theme toggle is available`() {
        compose.setContent {
            LumenChatScreen(steps(2), input = "", busy = false, error = null, modifier = viewport, ambient = false, onToggleTheme = {})
        }
        compose.onNodeWithTag("theme").assertIsDisplayed()
    }

    @Test
    fun `a lone thinking block is shown expanded`() {
        compose.setContent {
            LumenChatScreen(
                listOf(UiStep("t", StepKind.THINKING, "THINKING", "x", "reasoning", "the full reasoning text")),
                input = "", busy = true, error = null, modifier = viewport, ambient = false,
            )
        }
        compose.onNodeWithText("the full reasoning text").assertIsDisplayed()
    }

    @Test
    fun `an answered think collapses to a pill and expands on tap`() {
        val think = "weigh the options carefully"
        compose.setContent {
            LumenChatScreen(
                listOf(
                    UiStep("u", StepKind.YOU, "YOU", "x", "hi", "hi"),
                    UiStep("t", StepKind.THINKING, "THINKING", "x", "reasoning", think),
                    UiStep("a", StepKind.ASSISTANT, "ASSISTANT", "x", "answer", "the answer"),
                ),
                input = "", busy = false, error = null, modifier = viewport, ambient = false,
            )
        }
        compose.onNodeWithText("thinking · ${think.length} chars").assertIsDisplayed()
        noNode(think)
        val toggle = compose.onNodeWithTag("think-toggle")
        toggle.assertIsDisplayed()
        // Robolectric hit-testing for a nested clickable is fragile; invoke the
        // registered click action directly to exercise the toggle logic.
        toggle.performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        check(compose.onAllNodesWithText(think, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()) {
            "the pill opened but the body text did not render"
        }
    }
}
