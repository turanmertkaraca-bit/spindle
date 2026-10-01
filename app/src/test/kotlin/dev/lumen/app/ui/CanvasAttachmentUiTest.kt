package dev.lumen.app.ui

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.dp
import dev.lumen.app.PendingImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * The vision affordances and the canvas route, rendered by Robolectric:
 * attachment chips appear and remove, the attach affordance is reachable, and
 * the canvas screen instantiates a WebView and loads an HTML string.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CanvasAttachmentUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val viewport = Modifier.size(width = 360.dp, height = 640.dp)

    @Test
    fun `attachment chips render and remove by index`() {
        var removed: Int? = null
        compose.setContent {
            LumenChatScreen(
                steps = emptyList(),
                input = "",
                busy = false,
                error = null,
                modifier = viewport,
                ambient = false,
                attachments = listOf(
                    PendingImage("a.png", "image/png", "AAAA"),
                    PendingImage("b.jpg", "image/jpeg", "BBBB"),
                ),
                onRemoveAttachment = { removed = it },
                onAttachImage = { _, _, _ -> },
            )
        }

        compose.onNodeWithText("a.png").assertIsDisplayed()
        compose.onNodeWithText("b.jpg").assertIsDisplayed()
        compose.onNodeWithTag("attach-image").assertIsDisplayed()

        compose.onNodeWithTag("attachment-remove-1").performClick()
        compose.waitForIdle()
        assertEquals(1, removed)
    }

    @Test
    fun `the attach affordance is hidden without a handler`() {
        compose.setContent {
            LumenChatScreen(
                steps = emptyList(),
                input = "",
                busy = false,
                error = null,
                modifier = viewport,
                ambient = false,
            )
        }
        check(compose.onAllNodesWithTag("attach-image").fetchSemanticsNodes().isEmpty()) {
            "no attach affordance should render when onAttachImage is null"
        }
    }

    @Test
    fun `an html mention shows the canvas view action`() {
        var opened: String? = null
        compose.setContent {
            LumenChatScreen(
                steps = listOf(
                    dev.lumen.app.ui.model.UiStep(
                        id = "a",
                        kind = dev.lumen.app.ui.model.StepKind.ASSISTANT,
                        label = "ASSISTANT",
                        tag = "x",
                        summary = "wrote pages/demo.html",
                        body = "I wrote pages/demo.html for you",
                    ),
                ),
                input = "",
                busy = false,
                error = null,
                modifier = viewport,
                ambient = false,
                onOpenCanvas = { opened = it },
            )
        }

        compose.onNodeWithTag("canvas-view-a").assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals("pages/demo.html", opened)
    }

    @Test
    fun `the canvas screen loads an html string`() {
        compose.setContent {
            CanvasScreen(
                html = "<html><body><h1>canvas</h1></body></html>",
                colors = LumenColors.Dark,
                sourceName = "demo.html",
                onBack = {},
                modifier = viewport,
            )
        }
        compose.onNodeWithTag("canvas-web").assertIsDisplayed()
        compose.onNodeWithTag("canvas-title").assertIsDisplayed()
        compose.onNodeWithText("\u25b6 demo.html").assertIsDisplayed()
    }
}
