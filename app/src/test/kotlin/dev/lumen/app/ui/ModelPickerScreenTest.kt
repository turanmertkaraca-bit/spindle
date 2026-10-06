package dev.lumen.app.ui

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import dev.spindle.core.provider.ModelInfo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The live picker is a pure hoisted list: every tag and tap reports up. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ModelPickerScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val viewport = Modifier.size(width = 360.dp, height = 640.dp)

    private val models = listOf(
        ModelInfo(
            "deepseek", "alpha", label = "Alpha", contextWindow = 128_000,
            inputCostPerM = 1.0, outputCostPerM = 2.0,
        ),
        ModelInfo("deepseek", "beta", label = "Beta", contextWindow = 64_000),
    )

    @Test
    fun `renders a row per model and selects on tap`() {
        var picked: String? = null
        compose.setContent {
            ModelPickerScreen(
                colors = LumenColors.Dark,
                provider = "deepseek",
                models = models,
                selected = "deepseek/other",
                refreshing = false,
                error = null,
                onProvider = {},
                onSelect = { picked = it },
                onRefresh = {},
                onBack = {},
                modifier = viewport,
            )
        }
        compose.onNodeWithTag("models-screen").assertExists()
        compose.onNodeWithTag("model-option-alpha").assertExists()
        compose.onNodeWithTag("model-option-beta").assertExists()
        compose.onNodeWithTag("model-option-alpha").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals("deepseek/alpha", picked)
    }

    @Test
    fun `the selected model gets the selected tag`() {
        compose.setContent {
            ModelPickerScreen(
                colors = LumenColors.Dark,
                provider = "deepseek",
                models = models,
                selected = "deepseek/beta",
                refreshing = false,
                error = null,
                onProvider = {},
                onSelect = {},
                onRefresh = {},
                onBack = {},
                modifier = viewport,
            )
        }
        compose.onNodeWithTag("model-option-selected-beta").assertExists()
        compose.onNodeWithTag("model-option-alpha").assertExists()
    }

    @Test
    fun `refresh and back report their taps`() {
        var refreshed = false
        var back = false
        compose.setContent {
            ModelPickerScreen(
                colors = LumenColors.Dark,
                provider = "deepseek",
                models = models,
                selected = "deepseek/alpha",
                refreshing = false,
                error = null,
                onProvider = {},
                onSelect = {},
                onRefresh = { refreshed = true },
                onBack = { back = true },
                modifier = viewport,
            )
        }
        compose.onNodeWithTag("models-refresh").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertTrue(refreshed)
        compose.onNodeWithTag("models-back").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertTrue(back)
    }

    @Test
    fun `provider chips report the chosen provider`() {
        var provider: String? = null
        compose.setContent {
            ModelPickerScreen(
                colors = LumenColors.Dark,
                provider = "deepseek",
                models = models,
                selected = "deepseek/alpha",
                refreshing = false,
                error = null,
                onProvider = { provider = it },
                onSelect = {},
                onRefresh = {},
                onBack = {},
                modifier = viewport,
            )
        }
        compose.onNodeWithTag("models-provider-openrouter").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals("openrouter", provider)
    }
}
