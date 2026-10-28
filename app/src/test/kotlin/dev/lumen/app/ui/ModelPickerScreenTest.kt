package dev.lumen.app.ui

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
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
import kotlin.test.assertNull
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
    fun `provider chips only change the local browse filter`() {
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
        assertNull(provider)
        compose.onNodeWithTag("model-option-openrouter/free").assertExists()
        compose.onNodeWithTag("model-option-alpha").assertDoesNotExist()
    }

    @Test
    fun `a refresh with no models shows loading, never an empty state`() {
        compose.setContent {
            ModelPickerScreen(
                colors = LumenColors.Dark,
                provider = "deepseek",
                models = emptyList(),
                selected = "deepseek/alpha",
                refreshing = true,
                error = null,
                onProvider = {},
                onSelect = {},
                onRefresh = {},
                onBack = {},
                modifier = viewport,
            )
        }
        compose.onNodeWithTag("models-loading").assertExists()
        check(compose.onAllNodesWithTag("models-empty").fetchSemanticsNodes().isEmpty()) {
            "an empty state must never render while a refresh is in flight"
        }
    }

    // ---- filter and sort ----

    private val catalogue = listOf(
        ModelInfo("p", "zeta-big", label = "Zeta", contextWindow = 400_000, inputCostPerM = 9.0),
        ModelInfo("p", "alpha-cheap", label = "Alpha", contextWindow = 8_000, inputCostPerM = 0.1),
        ModelInfo("p", "Mid-Model", label = "Mid", contextWindow = 128_000, inputCostPerM = 2.0),
    )

    @Test
    fun `search matches the id and the label case-insensitively`() {
        val byLabel = filterAndSortModels(catalogue, "p", "alpha", ModelSort.Name, false, emptySet())
        assertEquals(listOf("alpha-cheap"), byLabel.map { it.id })

        val byId = filterAndSortModels(catalogue, "p", "MID", ModelSort.Name, false, emptySet())
        assertEquals(listOf("Mid-Model"), byId.map { it.id }, "ids match case-insensitively")

        val none = filterAndSortModels(catalogue, "p", "nothing-here", ModelSort.Name, false, emptySet())
        assertTrue(none.isEmpty(), "an unmatched query returns an empty list, not everything")
    }

    @Test
    fun `an empty query returns every model`() {
        val all = filterAndSortModels(catalogue, "p", "   ", ModelSort.Name, false, emptySet())
        assertEquals(catalogue.size, all.size, "whitespace is not a filter")
    }

    @Test
    fun `sort orders by name, context and cost`() {
        val byName = filterAndSortModels(catalogue, "p", "", ModelSort.Name, false, emptySet())
        assertEquals(listOf("alpha-cheap", "Mid-Model", "zeta-big"), byName.map { it.id })

        val byContext = filterAndSortModels(catalogue, "p", "", ModelSort.Context, false, emptySet())
        assertEquals(listOf("zeta-big", "Mid-Model", "alpha-cheap"), byContext.map { it.id })

        // Deliberately NOT the same order as context, so this actually proves the
        // cost comparator runs rather than falling through to another sort.
        val priced = listOf(
            ModelInfo("p", "cheap-big-context", label = "Cheap", contextWindow = 400_000, inputCostPerM = 0.1),
            ModelInfo("p", "pricey-small-context", label = "Pricey", contextWindow = 8_000, inputCostPerM = 9.0),
        )
        val byCost = filterAndSortModels(priced, "p", "", ModelSort.Cost, false, emptySet())
        assertEquals(
            listOf("pricey-small-context", "cheap-big-context"),
            byCost.map { it.id },
            "cost sort is descending and independent of context size",
        )
    }

    @Test
    fun `favoritesOnly keeps just the starred refs`() {
        val starred = setOf("p/zeta-big", "p/Mid-Model")
        val only = filterAndSortModels(catalogue, "p", "", ModelSort.Name, true, starred)
        assertEquals(listOf("Mid-Model", "zeta-big"), only.map { it.id })

        // A starred ref from another provider must not leak into this one.
        val foreign = filterAndSortModels(catalogue, "p", "", ModelSort.Name, true, setOf("other/zeta-big"))
        assertTrue(foreign.isEmpty(), "favourites are scoped to the browsed provider")
    }

    @Test
    fun `search and favoritesOnly combine`() {
        val both = filterAndSortModels(catalogue, "p", "zeta", ModelSort.Name, true, setOf("p/zeta-big"))
        assertEquals(listOf("zeta-big"), both.map { it.id })
    }
}
