package dev.lumen.app.ui

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * The terminal screen renders the shell's chunks and routes the input row: Send
 * hands the typed line back to the caller and the Ctrl-C/stop affordance fires.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TerminalScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val viewport = Modifier.size(width = 360.dp, height = 640.dp)

    @Test
    fun `renders the provided output lines`() {
        compose.setContent {
            TerminalScreen(
                colors = LumenColors.Dark,
                lines = listOf("lumen ready\n", "echo lumen\n", "lumen\n"),
                running = true,
                error = null,
                onSend = {},
                onInterrupt = {},
                onClear = {},
                onBack = {},
                modifier = viewport,
            )
        }

        compose.onNodeWithTag("terminal-output").assertIsDisplayed()
        compose.onNodeWithText("lumen ready", substring = true).assertIsDisplayed()
    }

    @Test
    fun `send hands the typed line to the callback`() {
        var sent: String? = null
        compose.setContent {
            TerminalScreen(
                colors = LumenColors.Dark,
                lines = listOf("…"),
                running = true,
                error = null,
                onSend = { sent = it },
                onInterrupt = {},
                onClear = {},
                onBack = {},
                modifier = viewport,
            )
        }

        compose.onNodeWithTag("terminal-input").performClick()
        compose.onNodeWithTag("terminal-input").performTextInput("echo hi")
        compose.onNodeWithTag("terminal-send").performClick()
        compose.waitForIdle()

        assertEquals("echo hi", sent)
    }

    @Test
    fun `stop invokes the interrupt callback`() {
        var interrupted = false
        compose.setContent {
            TerminalScreen(
                colors = LumenColors.Dark,
                lines = emptyList(),
                running = true,
                error = null,
                onSend = {},
                onInterrupt = { interrupted = true },
                onClear = {},
                onBack = {},
                modifier = viewport,
            )
        }

        compose.onNodeWithTag("terminal-ctrl-c").performClick()
        compose.waitForIdle()

        assertEquals(true, interrupted)
    }
}
