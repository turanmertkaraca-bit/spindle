package dev.lumen.app.ui

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import dev.lumen.app.ProjectRow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The picker must make the active project obvious and delete only after a confirm. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProjectsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val viewport = Modifier.size(width = 360.dp, height = 640.dp)

    private val two = listOf(
        ProjectRow("playground", "playground", active = true),
        ProjectRow("beta", "beta", active = false),
    )

    @Test
    fun `the active project is marked and an inactive row switches`() {
        var switched: String? = null
        compose.setContent {
            ProjectsScreen(
                colors = LumenColors.Dark,
                projects = two,
                onSwitch = { switched = it },
                onCreate = {},
                onDelete = {},
                onBack = {},
                modifier = viewport,
            )
        }

        compose.onNodeWithTag("project-active", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("project-row-beta").assertIsDisplayed().performClick()
        compose.waitForIdle()

        assertEquals("beta", switched)
    }

    @Test
    fun `delete asks for confirmation before removing a folder`() {
        var deleted: String? = null
        compose.setContent {
            ProjectsScreen(
                colors = LumenColors.Dark,
                projects = two,
                onSwitch = {},
                onCreate = {},
                onDelete = { deleted = it },
                onBack = {},
                modifier = viewport,
            )
        }

        compose.onNodeWithTag("project-delete-beta").performClick()
        compose.onNodeWithText("Delete project?").assertIsDisplayed()
        compose.onNodeWithTag("project-delete-confirm").performClick()
        compose.waitForIdle()

        assertEquals("beta", deleted)
    }

    @Test
    fun `the last project offers no delete action`() {
        compose.setContent {
            ProjectsScreen(
                colors = LumenColors.Dark,
                projects = listOf(ProjectRow("playground", "playground", active = true)),
                onSwitch = {},
                onCreate = {},
                onDelete = {},
                onBack = {},
                modifier = viewport,
            )
        }

        compose.onNodeWithTag("project-delete-playground").assertDoesNotExist()
        compose.onNodeWithText("One folder per project. The agent only sees the active folder.").assertIsDisplayed()
    }
}
