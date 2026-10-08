package dev.lumen.app.ui

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import dev.lumen.app.platform.GitActivity
import dev.lumen.app.platform.GitCommit
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

    @Test
    fun `activity section is idle before any read`() {
        compose.setContent {
            GitHubScreen(colors = LumenColors.Dark, onBack = {}, modifier = viewport)
        }
        compose.onNodeWithTag("github-activity-idle").assertExists()
        compose.onNodeWithTag("github-activity-refresh").assertExists()
    }

    @Test
    fun `activity section renders branch, remote, dirty state and commits`() {
        val activity = GitActivity(
            isRepo = true,
            repo = "Hello-World",
            branch = "main",
            remote = "git@github.com:octocat/Hello-World.git",
            dirty = true,
            changedFiles = 2,
            commits = listOf(
                GitCommit("abc1234567890", "abc1234", "fix the bug", "Ada", "1 hour ago"),
            ),
        )
        compose.setContent {
            GitHubScreen(
                colors = LumenColors.Dark,
                onBack = {},
                modifier = viewport,
                activity = activity,
            )
        }
        compose.onNodeWithTag("github-activity").assertExists()
        compose.onNodeWithTag("github-commit").assertExists()
    }

    @Test
    fun `activity section renders an empty state for a non-repo`() {
        compose.setContent {
            GitHubScreen(
                colors = LumenColors.Dark,
                onBack = {},
                modifier = viewport,
                activity = GitActivity(isRepo = false, error = "not a git repository"),
            )
        }
        compose.onNodeWithTag("github-activity-empty").assertExists()
    }

    @Test
    fun `activity refresh pill invokes the callback`() {
        var refreshed = false
        compose.setContent {
            GitHubScreen(
                colors = LumenColors.Dark,
                onBack = {},
                modifier = viewport,
                activity = GitActivity(isRepo = true, branch = "main"),
                onRefreshActivity = { refreshed = true },
            )
        }
        compose.onNodeWithTag("github-activity-refresh").performScrollTo().performClick()
        compose.waitForIdle()
        assertTrue(refreshed)
    }

    @Test
    fun `tapping a commit loads and shows its diff`() {
        val activity = GitActivity(
            isRepo = true,
            branch = "main",
            commits = listOf(GitCommit("abc1234567890", "abc1234", "fix the bug", "Ada", "now")),
        )
        compose.setContent {
            GitHubScreen(
                colors = LumenColors.Dark,
                onBack = {},
                modifier = viewport,
                activity = activity,
                loadDiff = { "diff --git a/x b/x" },
            )
        }
        compose.onNodeWithContentDescription("abc1234").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("github-diff").assertExists()
    }
}
