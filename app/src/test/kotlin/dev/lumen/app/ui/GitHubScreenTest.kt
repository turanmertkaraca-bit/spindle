package dev.lumen.app.ui

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GitHubScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val viewport = Modifier.size(width = 360.dp, height = 900.dp)

    @Test
    fun `renders the token, repo and action controls`() {
        compose.setContent {
            GitHubScreen(colors = LumenColors.Dark, onBack = {}, modifier = viewport)
        }
        compose.onNodeWithTag("github-token").assertExists()
        compose.onNodeWithTag("github-repo").assertExists()
        compose.onNodeWithTag("github-save").assertExists()
        compose.onNodeWithTag("github-test").assertExists()
        compose.onNodeWithTag("github-clone").assertExists()
        compose.onNodeWithTag("github-status").assertExists()
        compose.onNodeWithTag("github-open").assertExists()
        compose.onNodeWithTag("github-disconnected").assertExists()
    }

    @Test
    fun `back invokes the callback`() {
        var back = false
        compose.setContent {
            GitHubScreen(colors = LumenColors.Dark, onBack = { back = true }, modifier = viewport)
        }
        compose.onNodeWithTag("github-back").performClick()
        compose.waitForIdle()
        assertTrue(back)
    }
}
