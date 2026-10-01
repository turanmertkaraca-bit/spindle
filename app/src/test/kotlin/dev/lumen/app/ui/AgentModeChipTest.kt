package dev.lumen.app.ui

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/** The build/plan selector must toggle the hoisted mode state. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AgentModeChipTest {

    @get:Rule
    val compose = createComposeRule()

    private val viewport = Modifier.size(width = 360.dp, height = 480.dp)

    @Test
    fun `the mode chip toggles between build and plan`() {
        val mode = mutableStateOf("build")
        compose.setContent {
            LumenChatScreen(
                steps = emptyList(),
                input = "",
                busy = false,
                error = null,
                modifier = viewport,
                ambient = false,
                agentMode = mode.value,
                onAgentMode = { mode.value = it },
            )
        }
        compose.onNodeWithTag("agent-build").assertIsDisplayed()
        compose.onNodeWithTag("agent-plan").assertIsDisplayed().performClick()
        compose.waitForIdle()
        assertEquals("plan", mode.value)
        compose.onNodeWithTag("agent-build").performClick()
        compose.waitForIdle()
        assertEquals("build", mode.value)
    }
}
