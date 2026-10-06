package dev.lumen.app.platform

import dev.spindle.core.tool.ShellExecutor
import dev.spindle.core.tool.ShellResult
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** GitRunner is exercised against a scripted [ShellExecutor]; no real git runs. */
class GitRunnerTest {

    private class FakeShell(private val respond: (String) -> ShellResult) : ShellExecutor {
        override val id = "fake"
        val commands = mutableListOf<String>()
        val envs = mutableListOf<Map<String, String>>()

        override suspend fun run(
            command: String,
            cwd: Path,
            timeoutMs: Long,
            env: Map<String, String>,
        ): ShellResult {
            commands += command
            envs += env
            return respond(command)
        }
    }

    private fun temp(): Path = Files.createTempDirectory("lumen-git")

    @Test
    fun `probe reports the installed version`() = runBlocking {
        val shell = FakeShell { ShellResult(0, "git version 2.43.0\n") }
        val probe = GitRunner(shell, temp()).probe()
        assertTrue(probe.installed)
        assertEquals("git version 2.43.0", probe.version)
    }

    @Test
    fun `probe reports missing git`() = runBlocking {
        val shell = FakeShell { ShellResult(127, "sh: git: not found") }
        assertFalse(GitRunner(shell, temp()).probe().installed)
    }

    @Test
    fun `clone refuses when git is missing`() = runBlocking {
        val shell = FakeShell { ShellResult(127, "git: not found") }
        assertEquals(
            GitResult.NotInstalled,
            GitRunner(shell, temp()).clone("octocat/Hello-World", "Hello-World", "token"),
        )
    }

    @Test
    fun `clone passes the token only via env and uses a clean remote url`() = runBlocking {
        val shell = FakeShell { cmd ->
            if (cmd.contains("--version")) ShellResult(0, "git version 2.43.0") else ShellResult(0, "ok")
        }
        val result = GitRunner(shell, temp()).clone("octocat/Hello-World", "Hello-World", "s3cr3t")
        assertTrue(result is GitResult.Ok)

        val cloneIndex = shell.commands.indexOfFirst { it.contains(" clone ") }
        assertTrue(cloneIndex >= 0, "a clone command should have been issued")
        val command = shell.commands[cloneIndex]
        assertTrue("s3cr3t" !in command, "the token must never be in argv")
        assertTrue("credential.helper" in command, "auth rides on a credential helper")
        assertTrue(
            "https://github.com/octocat/Hello-World.git" in command,
            "the remote url is credential-free",
        )
        assertEquals("s3cr3t", shell.envs[cloneIndex]["GITHUB_TOKEN"])
    }

    @Test
    fun `a failed clone redacts the token from its output`() = runBlocking {
        val shell = FakeShell { cmd ->
            when {
                cmd.contains("--version") -> ShellResult(0, "git version 2.43.0")
                cmd.contains(" clone ") -> ShellResult(1, "fatal: could not read Password for s3cr3t")
                else -> ShellResult(0, "")
            }
        }
        val result = GitRunner(shell, temp()).clone("octocat/Hello-World", "Hello-World", "s3cr3t")
        val failed = result as GitResult.Failed
        assertTrue("s3cr3t" !in failed.message, "the token must be redacted: ${failed.message}")
    }

    @Test
    fun `clone rejects a shell-unsafe slug`() = runBlocking {
        val shell = FakeShell { ShellResult(0, "git version 2.43.0") }
        val result = GitRunner(shell, temp()).clone("octocat/Hello-World; rm -rf /", "dest", "token")
        assertTrue(result is GitResult.Failed)
        assertTrue(shell.commands.none { it.contains("clone") }, "no command should be built from an unsafe slug")
    }

    @Test
    fun `open url is built from a valid slug only`() {
        val shell = FakeShell { ShellResult(0, "") }
        val runner = GitRunner(shell, temp())
        assertEquals("https://github.com/octocat/Hello-World", runner.openUrl("octocat/Hello-World"))
        assertEquals("https://github.com/octocat/Hello-World", runner.openUrl("octocat/Hello-World.git"))
        assertNull(runner.openUrl("bad slug"))
    }
}
