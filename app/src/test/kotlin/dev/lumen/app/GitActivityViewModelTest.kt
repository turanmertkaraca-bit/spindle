package dev.lumen.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.lumen.app.data.KeyStore
import dev.spindle.core.store.InMemorySessionStore
import dev.spindle.core.tool.ShellExecutor
import dev.spindle.core.tool.ShellResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [ChatViewModel.refreshGitActivity] against a scripted shell, on an unconfined
 * main so its launch settles inline. No real git runs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GitActivityViewModelTest {

    private class FakeShell(
        private val respond: (String) -> ShellResult,
    ) : ShellExecutor {
        override val id = "fake"

        override suspend fun run(
            command: String,
            cwd: Path,
            timeoutMs: Long,
            env: Map<String, String>,
        ): ShellResult = respond(command)
    }

    private lateinit var workspace: Path

    private fun keyStore(): KeyStore =
        KeyStore(ApplicationProvider.getApplicationContext<Context>())

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        workspace = Files.createTempDirectory("lumen-activity")
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `refreshGitActivity reads the workspace repo into state`() {
        val shell = FakeShell { cmd ->
            when {
                cmd.contains("--version") -> ShellResult(0, "git version 2.43.0")
                cmd.contains("--is-inside-work-tree") -> ShellResult(0, "true\n")
                cmd.contains("--abbrev-ref") -> ShellResult(0, "main\n")
                cmd.contains("remote get-url") -> ShellResult(0, "git@github.com:octocat/Hello-World.git\n")
                cmd.contains("status --porcelain") -> ShellResult(0, " M a.kt\n")
                cmd.contains("git log") -> ShellResult(
                    0,
                    "abc1234567890\u001fabc1234\u001fAda\u001f1 hour ago\u001finit\n",
                )
                else -> ShellResult(0, "")
            }
        }
        val vm = ChatViewModel(
            workspace = workspace,
            keys = keyStore(),
            store = InMemorySessionStore(),
            shell = shell,
            io = Dispatchers.Unconfined,
        )

        vm.refreshGitActivity()

        val activity = assertNotNull(vm.state.value.gitActivity)
        assertTrue(activity.isRepo)
        assertEquals("main", activity.branch)
        assertEquals("git@github.com:octocat/Hello-World.git", activity.remote)
        assertTrue(activity.dirty)
        assertEquals(1, activity.changedFiles)
        assertEquals(1, activity.commits.size)
        assertFalse(vm.state.value.gitActivityRefreshing)
    }

    @Test
    fun `refreshGitActivity surfaces a thrown failure as an error`() {
        val vm = ChatViewModel(
            workspace = workspace,
            keys = keyStore(),
            store = InMemorySessionStore(),
            shell = FakeShell { throw IllegalStateException("boom") },
            io = Dispatchers.Unconfined,
        )

        vm.refreshGitActivity()

        assertEquals("boom", assertNotNull(vm.state.value.gitActivity).error)
        assertFalse(vm.state.value.gitActivityRefreshing)
    }
}
