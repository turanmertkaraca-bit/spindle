package dev.lumen.app.ui

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KeyScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val viewport = Modifier.size(width = 360.dp, height = 800.dp)

    @Test
    fun `renders provider cards, field and actions`() {
        compose.setContent {
            KeyScreen(colors = LumenColors.Dark, onSubmit = { _, _ -> }, modifier = viewport)
        }
        compose.onNodeWithTag("continue").assertExists()
        compose.onNodeWithTag("keyfield").assertExists()
        compose.onNodeWithTag("key-visibility").assertExists()
        compose.onNodeWithTag("key-paste").assertExists()
        compose.onNodeWithTag("test-key").assertExists()
        compose.onNodeWithTag("provider-opencode-go").assertExists()
        compose.onNodeWithTag("provider-deepseek").assertExists()
        compose.onNodeWithTag("provider-openrouter").assertExists()
    }

    @Test
    fun `tapping show toggles the key field`() {
        compose.setContent {
            KeyScreen(colors = LumenColors.Dark, onSubmit = { _, _ -> }, modifier = viewport)
        }
        compose.onNodeWithText("Show").assertExists()
        compose.onNodeWithTag("key-visibility").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Hide").assertExists()
    }

    @Test
    fun `a passed error is shown inline`() {
        compose.setContent {
            KeyScreen(colors = LumenColors.Dark, onSubmit = { _, _ -> }, modifier = viewport, error = "bad key")
        }
        compose.onNodeWithText("bad key").assertExists()
    }

    @Test
    fun `the picked provider and key are submitted`() {
        var submitted: Pair<String, String>? = null
        compose.setContent {
            KeyScreen(
                colors = LumenColors.Dark,
                onSubmit = { provider, key -> submitted = provider to key },
                modifier = viewport,
                initialKey = "abc",
            )
        }
        compose.onNodeWithTag("provider-deepseek").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("continue").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals("deepseek" to "abc", submitted)
    }
}
