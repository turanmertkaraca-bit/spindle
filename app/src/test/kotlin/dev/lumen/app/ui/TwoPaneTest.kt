package dev.lumen.app.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The adaptive split: wide viewports host the list and detail side by side, a
 * phone-width viewport keeps only the list, and a null detail never splits.
 *
 * The class-level `w840dp` qualifier makes the Robolectric window wide enough
 * that a `size(800.dp, …)` child is actually measured at 800dp (otherwise a
 * small default window would clamp it and the wide case would silently narrow).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w840dp-h640dp")
class TwoPaneTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `wide viewport shows list and detail side by side`() {
        compose.setContent {
            AdaptiveTwoPane(
                list = { Text("list", Modifier.testTag("pane-list")) },
                detail = { Text("detail", Modifier.testTag("pane-detail")) },
                modifier = Modifier.size(800.dp, 600.dp),
            )
        }

        compose.onNodeWithTag("two-pane").assertIsDisplayed()
        compose.onNodeWithTag("pane-list").assertIsDisplayed()
        compose.onNodeWithTag("pane-detail").assertIsDisplayed()
    }

    @Test
    fun `narrow viewport shows only the list`() {
        compose.setContent {
            AdaptiveTwoPane(
                list = { Text("list", Modifier.testTag("pane-list")) },
                detail = { Text("detail", Modifier.testTag("pane-detail")) },
                modifier = Modifier.size(360.dp, 640.dp),
            )
        }

        compose.onNodeWithTag("pane-list").assertIsDisplayed()
        compose.onNodeWithTag("pane-detail").assertDoesNotExist()
        compose.onNodeWithTag("two-pane").assertDoesNotExist()
    }

    @Test
    fun `a null detail never splits even when wide`() {
        compose.setContent {
            AdaptiveTwoPane(
                list = { Text("list", Modifier.testTag("pane-list")) },
                detail = null,
                modifier = Modifier.size(800.dp, 600.dp),
            )
        }

        compose.onNodeWithTag("pane-list").assertIsDisplayed()
        compose.onNodeWithTag("two-pane").assertDoesNotExist()
    }
}
