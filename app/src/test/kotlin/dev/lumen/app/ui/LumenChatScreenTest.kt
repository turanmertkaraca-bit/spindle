package dev.lumen.app.ui

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import dev.lumen.app.ui.model.StepKind
import dev.lumen.app.ui.model.UiImage
import dev.lumen.app.ui.model.UiStep
import dev.spindle.core.model.FileEdit
import dev.spindle.core.model.RunChanges
import dev.spindle.core.model.SessionId
import dev.spindle.core.model.TodoItem
import dev.spindle.core.model.TodoStatus
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

    /**
     * The longest single laid-out string the whole tree currently holds. If a
     * 200k-char source were rendered as one Text this would report ~200k; a
     * bounded window/chunk keeps it near the UI's cap.
     */
    private fun maxRenderedTextLength(): Int =
        compose
            .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .flatMap { node ->
                if (node.config.contains(SemanticsProperties.Text)) {
                    node.config[SemanticsProperties.Text]
                } else {
                    emptyList()
                }
            }
            .maxOfOrNull { it.text.length } ?: 0

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
    fun `a tool card starts collapsed and reveals raw output only when expanded`() {
        val raw = "failed: [exit 159] uname -a\n=== System Info ===\nLinux localhost 5.10.0 aarch64"
        val s = listOf(
            UiStep(
                "t", StepKind.TOOL, "BASH", "x",
                summary = "uname -a", body = raw, failed = true,
            ),
        )
        compose.setContent {
            LumenChatScreen(s, input = "", busy = false, error = null, modifier = viewport, ambient = false)
        }
        // A distinct card with a header; the raw body stays hidden.
        compose.onNodeWithTag("tool-card").assertIsDisplayed()
        compose.onNodeWithTag("tool-status", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("uname -a").assertIsDisplayed()
        check(compose.onAllNodesWithText("System Info", substring = true).fetchSemanticsNodes().isEmpty()) {
            "collapsed tool card must not render its raw output"
        }
        // Expand: the raw output is the only place it shows.
        compose.onNodeWithTag("tools-toggle").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNodeWithText("System Info", substring = true).assertIsDisplayed()
    }

    @Test
    fun `an expanded tool card shows a late line of a 50-line output`() {
        // 50 non-blank lines: parseToolRows turns the first 6 into rows; the rest
        // must still be reachable in the bounded scroll area.
        val lines = (1..50).joinToString("\n") { "output line $it with enough words to wrap" }
        // Mirror the store path: the first 6 lines become structured rows, the
        // rest must survive as the raw tail the expanded card renders.
        val rows = (1..6).map { "output" to "line $it with enough words to wrap" }
        val s = listOf(
            UiStep("t", StepKind.TOOL, "BASH", "x", summary = "big run", body = lines, rows = rows),
        )
        compose.setContent {
            LumenChatScreen(s, input = "", busy = false, error = null, modifier = viewport, ambient = false)
        }
        compose.onNodeWithTag("tools-toggle").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        // The raw tail is inside the bounded scroll region; scroll it into view.
        compose.onNodeWithTag("tool-output-rest", useUnmergedTree = true)
            .performScrollTo()
        compose.waitForIdle()
        compose.onNodeWithText("output line 50", substring = true, useUnmergedTree = true).assertExists()
    }

    @Test
    fun `an expanded tool card shows the structured inspector above its output`() {
        val s = listOf(
            UiStep(
                "t", StepKind.TOOL, "BASH", "x",
                summary = "uname -a", body = "Linux localhost 5.10.0 aarch64",
                toolMetadata = mapOf("exitCode" to "0", "durationMs" to "1200"),
            ),
        )
        compose.setContent {
            LumenChatScreen(s, input = "", busy = false, error = null, modifier = viewport, ambient = false)
        }
        // Collapsed: the header only, no inspector.
        check(compose.onAllNodesWithTag("tool-inspector").fetchSemanticsNodes().isEmpty()) {
            "a collapsed tool card must not render its inspector"
        }
        compose.onNodeWithTag("tools-toggle").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNodeWithText("exit 0", substring = true).assertIsDisplayed()
        compose.onNodeWithText("duration 1.2s", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Linux localhost", substring = true).assertIsDisplayed()
    }

    @Test
    fun `a tool card without metadata renders only its output`() {
        val s = listOf(
            UiStep("t", StepKind.TOOL, "BASH", "x", summary = "echo hi", body = "raw output line"),
        )
        compose.setContent {
            LumenChatScreen(s, input = "", busy = false, error = null, modifier = viewport, ambient = false)
        }
        compose.onNodeWithTag("tools-toggle").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        check(compose.onAllNodesWithTag("tool-inspector").fetchSemanticsNodes().isEmpty()) {
            "a metadata-free card must not render an inspector"
        }
        compose.onNodeWithText("raw output line", substring = true).assertIsDisplayed()
    }

    @Test
    fun `a growing streaming body keeps the end displayed at the tail`() {
        val many = (0 until 12).map { i ->
            UiStep("s$i", StepKind.ASSISTANT, "ASSISTANT", "t$i", "summary $i", "BODY $i full text")
        }
        val streaming = mutableStateOf(
            many + UiStep("tail", StepKind.ASSISTANT, "ASSISTANT", "tt", "growing", "start"),
        )
        compose.setContent {
            LumenChatScreen(streaming.value, input = "", busy = true, error = null, modifier = viewport, ambient = false, haptics = false)
        }
        compose.waitForIdle()
        // Establish the tail position explicitly, then grow the SAME assistant row
        // (only its body changes; id and count stay stable).
        compose.onNodeWithTag("timeline").performScrollToIndex(12)
        compose.waitForIdle()
        repeat(6) { n ->
            compose.runOnIdle {
                streaming.value = streaming.value.dropLast(1) + UiStep(
                    "tail", StepKind.ASSISTANT, "ASSISTANT", "tt", "growing",
                    (0..n).joinToString("\n") { "streamed answer line $it that wraps across the bubble width" },
                )
            }
            compose.waitForIdle()
        }
        // At the tail: the end of the streamed body must be visible and no cue shown.
        check(compose.onAllNodesWithTag("new-cue").fetchSemanticsNodes().isEmpty()) {
            "no cue should appear while the reader is at the tail"
        }
        compose.onNodeWithText("streamed answer line 5", substring = true).assertIsDisplayed()
    }

    @Test
    fun `a growing streaming body while scrolled up shows the new cue`() {
        val many = (0 until 12).map { i ->
            UiStep("s$i", StepKind.ASSISTANT, "ASSISTANT", "t$i", "summary $i", "BODY $i full text")
        }
        val streaming = mutableStateOf(
            many + UiStep("tail", StepKind.ASSISTANT, "ASSISTANT", "tt", "growing", "start"),
        )
        compose.setContent {
            LumenChatScreen(streaming.value, input = "", busy = true, error = null, modifier = viewport, ambient = false, haptics = false)
        }
        compose.waitForIdle()
        // Read from the top, away from the tail.
        compose.onNodeWithTag("timeline").performScrollToIndex(0)
        compose.waitForIdle()
        // Grow the last row's body while the reader is up the transcript.
        repeat(8) { n ->
            compose.runOnIdle {
                streaming.value = streaming.value.dropLast(1) + UiStep(
                    "tail", StepKind.ASSISTANT, "ASSISTANT", "tt", "growing",
                    (0..n).joinToString("\n") { "off-screen streamed line $it growing the bubble" },
                )
            }
            compose.waitForIdle()
        }
        compose.onNodeWithTag("new-cue").assertIsDisplayed()
    }

    @Test
    fun `a user message with an image part shows an image indicator`() {
        compose.setContent {
            LumenChatScreen(
                listOf(
                    UiStep(
                        "u", StepKind.YOU, "YOU", "x", "look at this", "look at this",
                        images = listOf(UiImage("photo.png", "image/png", "!!!not-base64!!!")),
                    ),
                ),
                input = "", busy = false, error = null, modifier = viewport, ambient = false,
            )
        }
        compose.onNodeWithTag("msg-image", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("photo.png").assertIsDisplayed()
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
        compose.onNodeWithTag("send").assertIsDisplayed().assertIsEnabled()
    }

    @Test
    fun `stop replaces send while busy`() {
        compose.setContent {
            LumenChatScreen(steps(2), input = "", busy = true, error = null, modifier = viewport, ambient = false)
        }
        compose.onNodeWithTag("stop").assertIsDisplayed()
        // There is no send affordance at all while busy, so a stray tap or IME
        // send cannot race the stop control into a second prompt.
        check(compose.onAllNodesWithTag("send").fetchSemanticsNodes().isEmpty()) {
            "send must be absent while busy"
        }
    }

    @Test
    fun `the send control is disabled while the input is blank`() {
        val input = mutableStateOf("")
        var sent = 0
        compose.setContent {
            LumenChatScreen(
                steps(2), input = input.value, busy = false, error = null, modifier = viewport, ambient = false,
                onInput = { input.value = it }, onSend = { sent++ },
            )
        }
        compose.onNodeWithTag("send").assertIsNotEnabled()
        compose.onNodeWithTag("send").performClick()
        compose.waitForIdle()
        assertEquals(0, sent)
        // A real prompt lights the button up and the tap goes through.
        compose.runOnIdle { input.value = "go" }
        compose.waitForIdle()
        compose.onNodeWithTag("send").assertIsEnabled()
        compose.onNodeWithTag("send").performClick()
        compose.waitForIdle()
        assertEquals(1, sent)
    }

    @Test
    fun `the composer hint hides once text is present`() {
        val input = mutableStateOf("")
        compose.setContent {
            LumenChatScreen(
                steps(1), input = input.value, busy = false, error = null, modifier = viewport, ambient = false,
                onInput = { input.value = it },
            )
        }
        compose.onNodeWithTag("composer-hint").assertIsDisplayed()
        compose.onNodeWithTag("composer").performClick()
        compose.onNodeWithTag("composer").performTextInput("hello")
        compose.waitForIdle()
        check(compose.onAllNodesWithTag("composer-hint").fetchSemanticsNodes().isEmpty()) {
            "the placeholder must disappear once the field has text"
        }
    }

    @Test
    fun `the app bar controls are tappable and invoke their callbacks`() {
        var home = 0
        var fork = 0
        var files = 0
        var shell = 0
        compose.setContent {
            LumenChatScreen(
                steps(1), input = "", busy = false, error = null, modifier = viewport, ambient = false,
                title = "a very long chat title that should ellipsize rather than crowd the controls",
                onHome = { home++ }, onFork = { fork++ }, onFiles = { files++ }, onTerminal = { shell++ },
            )
        }
        compose.onNodeWithTag("home").performClick()
        compose.onNodeWithTag("fork").performClick()
        compose.onNodeWithTag("open-files").performClick()
        compose.onNodeWithTag("open-terminal").performClick()
        compose.waitForIdle()
        assertEquals(1, home)
        assertEquals(1, fork)
        assertEquals(1, files)
        assertEquals(1, shell)
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
    fun `todo board shows the count and items and hides when empty`() {
        val todos = mutableStateOf(emptyList<TodoItem>())
        compose.setContent {
            LumenChatScreen(
                steps(2), input = "", busy = false, error = null, modifier = viewport,
                ambient = false, todos = todos.value,
            )
        }
        check(compose.onAllNodesWithTag("todo-toggle").fetchSemanticsNodes().isEmpty()) {
            "the board should hide until the session has todos"
        }
        compose.runOnIdle {
            todos.value = listOf(
                TodoItem("1", "read the layout rule", TodoStatus.DONE),
                TodoItem("2", "patch the redirect guard", TodoStatus.IN_PROGRESS),
                TodoItem("3", "run the unit tests", TodoStatus.PENDING),
            )
        }
        compose.waitForIdle()
        compose.onNodeWithText("1/3 tasks", substring = true).assertIsDisplayed()
        check(compose.onAllNodesWithText("patch the redirect guard").fetchSemanticsNodes().isEmpty()) {
            "the board starts collapsed, so items stay hidden"
        }
        compose.onNodeWithTag("todo-toggle").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("patch the redirect guard").assertIsDisplayed()
        compose.onNodeWithText("read the layout rule").assertIsDisplayed()
        compose.runOnIdle { todos.value = emptyList() }
        compose.waitForIdle()
        check(compose.onAllNodesWithTag("todo-toggle").fetchSemanticsNodes().isEmpty()) {
            "the board should disappear once the todo list is empty"
        }
    }

    @Test
    fun `a subagent with nested children renders an indented tree`() {
        val sub = UiStep(
            "s", StepKind.SUBAGENT, "TASK", "z", "fix the build", "spawn a subagent",
            childId = "child",
            childSteps = listOf(
                UiStep(
                    "c1", StepKind.TOOL, "READ", "x", "read config", "read config",
                    childSteps = listOf(
                        UiStep("g1", StepKind.ASSISTANT, "ASSISTANT", "y", "nested answer one", "nested answer one"),
                    ),
                ),
            ),
        )
        compose.setContent {
            LumenChatScreen(
                listOf(sub), input = "", busy = false, error = null, modifier = viewport, ambient = false,
            )
        }
        check(compose.onAllNodesWithText("read config").fetchSemanticsNodes().isEmpty()) {
            "a collapsed subagent card must not render its child tree"
        }
        compose.onNodeWithTag("tools-toggle").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNodeWithText("read config").assertIsDisplayed()
        compose.onNodeWithText("nested answer one").assertIsDisplayed()
        compose.onNodeWithTag("child-row-0", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("child-row-1", useUnmergedTree = true).assertExists()
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

    @Test
    fun `a huge reasoning block lays out a bounded prefix until show all`() {
        val start = "THINK_START_MARKER"
        val end = "THINK_END_MARKER"
        val think = start + "reasoning ".repeat(20_000) + end
        check(think.length > 190_000) { "fixture should be a pathological 200k string" }
        compose.setContent {
            LumenChatScreen(
                listOf(
                    UiStep(
                        "t", StepKind.ASSISTANT, "ASSISTANT", "x", "answer", "the answer",
                        think = think,
                    ),
                ),
                input = "", busy = false, error = null, modifier = viewport, ambient = false,
            )
        }
        // Collapsed: only the header + size, none of the reasoning laid out.
        compose.onNodeWithText("thinking · ${think.length} chars").assertIsDisplayed()
        check(compose.onAllNodesWithText(start, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) {
            "a collapsed reasoning pill must not lay out the reasoning body"
        }
        // Expand: a bounded prefix plus a truncation affordance.
        compose.onNodeWithTag("think-toggle").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNodeWithTag("think-body", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("chars hidden", substring = true, useUnmergedTree = true).assertExists()
        check(compose.onAllNodesWithText(end, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) {
            "the opened window must stop before the end of a 200k string"
        }
        check(maxRenderedTextLength() <= 7_000) {
            "the expanded window laid out ${maxRenderedTextLength()} chars; it must stay near the cap"
        }
        // Opt in: the whole reasoning becomes reachable, still chunked.
        compose.onNodeWithTag("think-show-all", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        check(compose.onAllNodesWithText(end, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()) {
            "show all must make the end of the reasoning reachable"
        }
        check(maxRenderedTextLength() <= 7_000) {
            "show all must chunk the full string; a single Text held ${maxRenderedTextLength()} chars"
        }
    }

    @Test
    fun `a long streaming body lays out only a bounded tail`() {
        val head = "STREAM_HEAD_MARKER"
        val tail = "STREAM_TAIL_MARKER"
        val body = head + "streamed ".repeat(20_000) + tail
        check(body.length > 170_000) { "fixture should be a pathological streaming body" }
        compose.setContent {
            LumenChatScreen(
                listOf(
                    UiStep("a", StepKind.ASSISTANT, "ASSISTANT", "x", "streaming", body, running = true),
                ),
                input = "", busy = true, error = null, modifier = viewport, ambient = false, haptics = false,
            )
        }
        compose.waitForIdle()
        // The newest text is on screen...
        compose.onNodeWithText(tail, substring = true, useUnmergedTree = true).assertExists()
        // ...but the accumulated head is windowed away and no Text is unbounded.
        check(compose.onAllNodesWithText(head, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) {
            "a streaming body must not lay out its full accumulated text"
        }
        check(maxRenderedTextLength() <= 7_000) {
            "a streamed frame laid out ${maxRenderedTextLength()} chars; each Text must stay bounded"
        }
    }
}
