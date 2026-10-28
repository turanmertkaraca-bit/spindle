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
import dev.lumen.app.ProjectSessionSummary
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

    @Test
    fun `a project with sessions offers a one tap resume`() {
        var opened: String? = null
        val withSession = listOf(
            ProjectRow(
                id = "playground",
                name = "playground",
                active = true,
                sessionCount = 3,
                lastSession = ProjectSessionSummary(
                    id = "session-abc",
                    title = "refactor the parser",
                    updatedAt = 1_700_000_000_000,
                ),
            ),
        )
        compose.setContent {
            ProjectsScreen(
                colors = LumenColors.Dark,
                projects = withSession,
                onSwitch = {},
                onCreate = {},
                onDelete = {},
                onOpenSession = { opened = it },
                onBack = {},
                modifier = viewport,
            )
        }

        compose.onNodeWithText("active \u00b7 agent works here \u00b7 3 sessions").assertIsDisplayed()
        compose.onNodeWithText("resume \u00b7 refactor the parser").assertIsDisplayed()
        // Click the clickable container by its tag: the text leaf itself carries
        // no click action, so aiming at it would test nothing.
        compose.onNodeWithTag("project-resume-playground").performClick()
        compose.waitForIdle()

        assertEquals("session-abc", opened, "resume should open the newest session")
    }

    @Test
    fun `an empty project reports no sessions and offers no resume`() {
        compose.setContent {
            ProjectsScreen(
                colors = LumenColors.Dark,
                projects = listOf(ProjectRow("playground", "playground", active = true)),
                onSwitch = {},
                onCreate = {},
                onDelete = {},
                onOpenSession = {},
                onBack = {},
                modifier = viewport,
            )
        }

        compose.onNodeWithText("active \u00b7 agent works here \u00b7 no sessions").assertIsDisplayed()
        compose.onNodeWithTag("project-resume-playground").assertDoesNotExist()
    }

    @Test
    fun `an inactive project invites a switch and counts its sessions`() {
        compose.setContent {
            ProjectsScreen(
                colors = LumenColors.Dark,
                projects = listOf(
                    ProjectRow("playground", "playground", active = true),
                    ProjectRow("beta", "beta", active = false, sessionCount = 1),
                ),
                onSwitch = {},
                onCreate = {},
                onDelete = {},
                onBack = {},
                modifier = viewport,
            )
        }

        compose.onNodeWithText("1 session \u00b7 tap to switch").assertIsDisplayed()
    }
}
