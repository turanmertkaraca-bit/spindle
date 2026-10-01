package dev.lumen.app.ui

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import dev.lumen.app.DiagLine
import dev.lumen.app.LinuxEnvironmentState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

/**
 * The diagnostics screen is a pure render layer: the install button reflects the
 * hoisted installing flag and fires the callback only when idle, and the log
 * renders timestamped lines.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DiagnosticsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val viewport = Modifier.size(width = 360.dp, height = 640.dp)

    @Test
    fun `install button is disabled while installing and fires when idle`() {
        var fired = false
        val linux = mutableStateOf(LinuxEnvironmentState(installing = true, progress = "downloading…"))

        compose.setContent {
            DiagnosticsScreen(
                colors = LumenColors.Dark,
                diag = emptyList(),
                linux = linux.value,
                onInstallDebian = { fired = true },
                onRefreshLinux = {},
                onClear = {},
                onBack = {},
                modifier = viewport,
            )
        }

        compose.onNodeWithTag("install-debian").assertIsNotEnabled()

        compose.runOnIdle { linux.value = LinuxEnvironmentState(installing = false) }
        compose.waitForIdle()
        compose.onNodeWithTag("install-debian").performClick()
        compose.waitForIdle()

        assertTrue(fired, "the install callback should fire once enabled")
    }

    @Test
    fun `renders the diagnostic lines`() {
        compose.setContent {
            DiagnosticsScreen(
                colors = LumenColors.Light,
                diag = listOf(DiagLine(1_700_000_000_000, "run start: hello"), DiagLine(1_700_000_001_000, "tool: bash")),
                linux = LinuxEnvironmentState(),
                onInstallDebian = {},
                onRefreshLinux = {},
                onClear = {},
                onBack = {},
                modifier = viewport,
            )
        }

        compose.onNodeWithTag("diag-log").assertIsDisplayed()
        compose.onNodeWithText("run start: hello", substring = true).assertIsDisplayed()
    }
}
