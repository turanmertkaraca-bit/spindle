package dev.lumen.app.ui

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The budget control is a pure hoisted setting: tapping a chip reports the
 * chosen USD ceiling (0 = off) and nothing else changes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val viewport = Modifier.size(width = 360.dp, height = 640.dp)

    @Test
    fun `picking a budget chip reports its ceiling`() {
        var picked: Double? = null
        var cost by mutableStateOf(0.0)
        compose.setContent {
            SettingsScreen(
                colors = LumenColors.Dark,
                provider = "opencode-go",
                model = "opencode-go/deepseek-v4.1-flash",
                theme = "system",
                onProvider = {},
                onModel = {},
                onTheme = {},
                onEditKey = {},
                onBack = {},
                modifier = viewport,
                maxCostUsd = cost,
                onMaxCost = {
                    picked = it
                    cost = it
                },
            )
        }

        compose.onNodeWithTag("budget-2").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals(2.0, picked)

        compose.onNodeWithTag("budget-off").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals(0.0, picked)

        compose.onNodeWithText("budget").assertExists()
    }

    @Test
    fun `terse replies and reduce motion toggles report changes`() {
        var terse: Boolean? = null
        var motion: Boolean? = null
        compose.setContent {
            SettingsScreen(
                colors = LumenColors.Dark,
                provider = "opencode-go",
                model = "opencode-go/deepseek-v4.1-flash",
                theme = "system",
                onProvider = {},
                onModel = {},
                onTheme = {},
                onEditKey = {},
                onBack = {},
                modifier = viewport,
                terseReplies = false,
                onTerseReplies = { terse = it },
                reduceMotion = false,
                onReduceMotion = { motion = it },
            )
        }

        compose.onNodeWithTag("terse-replies").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals(true, terse)

        compose.onNodeWithTag("reduce-motion").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals(true, motion)
    }

    @Test
    fun `a remembered permission can be revoked`() {
        var forgotten: String? = null
        var cleared = false
        compose.setContent {
            SettingsScreen(
                colors = LumenColors.Dark,
                provider = "opencode-go",
                model = "opencode-go/deepseek-v4.1-flash",
                theme = "system",
                onProvider = {},
                onModel = {},
                onTheme = {},
                onEditKey = {},
                onBack = {},
                modifier = viewport,
                askBeforeTools = true,
                allowedPatterns = listOf("git push", "bash"),
                onForgetPattern = { forgotten = it },
                onClearPatterns = { cleared = true },
            )
        }

        compose.onNodeWithTag("permissions-row").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNodeWithTag("permission-clear-0").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals("git push", forgotten)

        compose.onNodeWithTag("permissions-clear-all").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertTrue(cleared)
    }

    @Test
    fun `the github link reports when tapped`() {
        var tapped = false
        compose.setContent {
            SettingsScreen(
                colors = LumenColors.Dark,
                provider = "opencode-go",
                model = "opencode-go/deepseek-v4.1-flash",
                theme = "system",
                onProvider = {},
                onModel = {},
                onTheme = {},
                onEditKey = {},
                onBack = {},
                modifier = viewport,
                onGitHub = { tapped = true },
                githubLogin = "octocat",
            )
        }

        compose.onNodeWithText("octocat").assertExists()
        compose.onNodeWithTag("settings-github").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertTrue(tapped)
    }
}
