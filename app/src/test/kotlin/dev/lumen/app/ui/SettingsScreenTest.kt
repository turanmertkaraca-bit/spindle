package dev.lumen.app.ui

import androidx.compose.foundation.layout.size
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
                maxCostUsd = 0.0,
                onMaxCost = { picked = it },
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
}
