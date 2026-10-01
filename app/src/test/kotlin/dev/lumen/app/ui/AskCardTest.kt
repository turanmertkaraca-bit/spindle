package dev.lumen.app.ui

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import dev.lumen.app.PendingAsk
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/** The pinned ask card: permission buttons and question chips wire to callbacks. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AskCardTest {

    @get:Rule
    val compose = createComposeRule()

    private val viewport = Modifier.size(width = 360.dp, height = 480.dp)

    @Test
    fun `permission card shows the actions and Always allow reports always`() {
        var allow: Boolean? = null
        var always: Boolean? = null
        compose.setContent {
            LumenChatScreen(
                emptyList(), input = "", busy = true, error = null,
                modifier = viewport, ambient = false,
                ask = PendingAsk.Permission("a1", "bash", "rm -rf build", "rm"),
                onAnswerPermission = { a, w -> allow = a; always = w },
            )
        }
        compose.onNodeWithTag("ask-card").assertIsDisplayed()
        compose.onNodeWithTag("ask-allow").assertIsDisplayed()
        compose.onNodeWithTag("ask-always").assertIsDisplayed()
        compose.onNodeWithTag("ask-deny").assertIsDisplayed()

        compose.onNodeWithTag("ask-always").performClick()
        compose.waitForIdle()
        assertEquals(true, allow, "always allow permits the call")
        assertEquals(true, always, "always allow asks to be remembered")
    }

    @Test
    fun `question card shows option chips and a tap answers`() {
        var answered: List<String>? = null
        compose.setContent {
            LumenChatScreen(
                emptyList(), input = "", busy = true, error = null,
                modifier = viewport, ambient = false,
                ask = PendingAsk.Question("q1", "which target?", listOf("debug", "release"), multiple = false),
                onAnswerQuestion = { answered = it },
            )
        }
        compose.onNodeWithTag("ask-option-0").assertIsDisplayed()
        compose.onNodeWithTag("ask-option-1").assertIsDisplayed()
        compose.onNodeWithTag("ask-option-0").performClick()
        compose.waitForIdle()
        assertEquals(listOf("debug"), answered)
    }

    @Test
    fun `multi question collects chips until answer`() {
        var answered: List<String>? = null
        compose.setContent {
            LumenChatScreen(
                emptyList(), input = "", busy = true, error = null,
                modifier = viewport, ambient = false,
                ask = PendingAsk.Question("q2", "pick any", listOf("a", "b"), multiple = true),
                onAnswerQuestion = { answered = it },
            )
        }
        compose.onNodeWithTag("ask-option-0").performClick()
        compose.onNodeWithTag("ask-option-1").performClick()
        compose.onNodeWithTag("ask-answer").performClick()
        compose.waitForIdle()
        assertEquals(setOf("a", "b"), answered?.toSet())
    }
}
