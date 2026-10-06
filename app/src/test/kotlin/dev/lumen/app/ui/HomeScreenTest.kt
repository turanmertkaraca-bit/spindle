package dev.lumen.app.ui

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * The home status strip reports the current runtime quietly, and the quick
 * controls surface the ask-before-tools toggle plus the two cockpits without
 * opening the overflow menu.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HomeScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val viewport = Modifier.size(width = 360.dp, height = 640.dp)

    @Composable
    private fun screen(
        provider: String = "openrouter",
        model: String = "free",
        maxCostUsd: Double = 0.0,
        sandboxLabel: String = "",
        githubLogin: String = "",
        askBeforeTools: Boolean = false,
        onAskBeforeTools: (Boolean) -> Unit = {},
        onFiles: (() -> Unit)? = null,
        onTerminal: (() -> Unit)? = null,
    ) {
        HomeScreen(
            colors = LumenColors.Dark,
            sessions = emptyList(),
            onNewChat = {},
            onOpen = {},
            onDelete = {},
            onSettings = {},
            modifier = viewport,
            provider = provider,
            model = model,
            maxCostUsd = maxCostUsd,
            sandboxLabel = sandboxLabel,
            githubLogin = githubLogin,
            askBeforeTools = askBeforeTools,
            onAskBeforeTools = onAskBeforeTools,
            onFiles = onFiles,
            onTerminal = onTerminal,
        )
    }

    @Test
    fun `status strip reports provider model budget sandbox and github`() {
        compose.setContent {
            screen(
                provider = "openrouter",
                model = "free",
                maxCostUsd = 5.0,
                sandboxLabel = "linux",
                githubLogin = "octo",
            )
        }

        compose.onNodeWithTag("status-provider").assertExists()
        compose.onNodeWithText("openrouter \u00b7 free").assertExists()
        compose.onNodeWithTag("status-sandbox").assertExists()
        compose.onNodeWithText("linux").assertExists()
        compose.onNodeWithTag("status-budget").assertExists()
        compose.onNodeWithText("\$5 cap").assertExists()
        compose.onNodeWithTag("status-github").assertExists()
        compose.onNodeWithText("@octo").assertExists()
    }

    @Test
    fun `budget pill reads off when no cap is set`() {
        compose.setContent { screen(maxCostUsd = 0.0) }

        compose.onNodeWithText("budget off").assertExists()
    }

    @Test
    fun `ask before tools switch toggles on the home tag`() {
        var toggled: Boolean? = null
        compose.setContent { screen(askBeforeTools = false, onAskBeforeTools = { toggled = it }) }

        compose.onNodeWithTag("home-ask-before-tools").performClick()
        compose.waitForIdle()

        assertEquals(true, toggled)
    }

    @Test
    fun `files and terminal are promoted into the quick controls`() {
        var files = false
        var terminal = false
        compose.setContent {
            screen(onFiles = { files = true }, onTerminal = { terminal = true })
        }

        compose.onNodeWithTag("files").assertIsDisplayed().performClick()
        compose.onNodeWithTag("terminal").assertIsDisplayed().performClick()
        compose.waitForIdle()

        assertEquals(true, files)
        assertEquals(true, terminal)
    }
}
