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

    /** A scripted shell that answers each git subcommand by substring. */
    private fun fakeGit(
        installed: Boolean = true,
        inside: ShellResult = ShellResult(0, "true\n"),
        branch: ShellResult = ShellResult(0, "main\n"),
        symbolic: ShellResult = ShellResult(0, "main\n"),
        remote: ShellResult = ShellResult(0, ""),
        status: ShellResult = ShellResult(0, ""),
        log: ShellResult = ShellResult(0, ""),
    ): FakeShell = FakeShell { cmd ->
        when {
            cmd.contains("--version") ->
                if (installed) ShellResult(0, "git version 2.43.0") else ShellResult(127, "git: not found")
            cmd.contains("--is-inside-work-tree") -> inside
            cmd.contains("--abbrev-ref") -> branch
            cmd.contains("symbolic-ref") -> symbolic
            cmd.contains("remote get-url") -> remote
            cmd.contains("status --porcelain") -> status
            cmd.contains("git log") -> log
            else -> ShellResult(0, "")
        }
    }

    @Test
    fun `activity reports missing git without running repo commands`() = runBlocking {
        val shell = fakeGit(installed = false)
        val activity = GitRunner(shell, temp()).activity()
        assertEquals("git is not installed", activity.error)
        assertFalse(activity.isRepo)
        assertTrue(shell.commands.none { it.contains("--is-inside-work-tree") })
    }

    @Test
    fun `activity reports a non-repository`() = runBlocking {
        val shell = fakeGit(inside = ShellResult(128, "fatal: not a git repository"))
        val activity = GitRunner(shell, temp()).activity()
        assertFalse(activity.isRepo)
        assertEquals("not a git repository", activity.error)
    }

    @Test
    fun `activity parses branch, remote, dirty state and commits`() = runBlocking {
        val log = ShellResult(
            0,
            "abc1234567890\u001fabc1234\u001fAda Lovelace\u001f2 hours ago\u001ffix the bug\n",
        )
        val shell = fakeGit(
            remote = ShellResult(0, "git@github.com:octocat/Hello-World.git\n"),
            status = ShellResult(0, " M a.kt\n?? b.kt\n"),
            log = log,
        )
        val activity = GitRunner(shell, temp()).activity()

        assertTrue(activity.isRepo)
        assertEquals("main", activity.branch)
        assertEquals("git@github.com:octocat/Hello-World.git", activity.remote)
        assertTrue(activity.dirty)
        assertEquals(2, activity.changedFiles)
        assertEquals(1, activity.commits.size)
        val commit = activity.commits.single()
        assertEquals("abc1234567890", commit.hash)
        assertEquals("abc1234", commit.shortHash)
        assertEquals("fix the bug", commit.subject)
        assertEquals("Ada Lovelace", commit.author)
        assertEquals("2 hours ago", commit.relativeTime)
    }

    @Test
    fun `activity falls back to symbolic-ref for a branch name`() = runBlocking {
        val shell = fakeGit(branch = ShellResult(128, ""), symbolic = ShellResult(0, "trunk\n"))
        assertEquals("trunk", GitRunner(shell, temp()).activity().branch)
    }

    @Test
    fun `activity on an empty repository has no commits and no error`() = runBlocking {
        val shell = fakeGit(
            branch = ShellResult(128, ""),
            symbolic = ShellResult(0, "main\n"),
            log = ShellResult(128, "fatal: your current branch 'main' does not have any commits yet"),
        )
        val activity = GitRunner(shell, temp()).activity()
        assertTrue(activity.isRepo)
        assertTrue(activity.commits.isEmpty())
        assertNull(activity.error)
        assertEquals("main", activity.branch)
    }

    @Test
    fun `activity caps the commit count it requests`() = runBlocking {
        val high = fakeGit()
        GitRunner(high, temp()).activity(limit = 1000)
        assertTrue(
            high.commands.any { it.contains("git log -n 100 ") },
            "the limit should be clamped to MAX_COMMITS: ${high.commands}",
        )

        val low = fakeGit()
        GitRunner(low, temp()).activity(limit = 0)
        assertTrue(low.commands.any { it.contains("git log -n 1 ") })
    }

    @Test
    fun `activity strips credentials from the origin remote`() = runBlocking {
        val shell = fakeGit(remote = ShellResult(0, "https://user:pass@github.com/octocat/Hello-World.git\n"))
        val remote = GitRunner(shell, temp()).activity().remote
        assertEquals("https://github.com/octocat/Hello-World.git", remote)
        assertTrue("pass" !in remote)
    }

    @Test
    fun `commitDiff rejects an unsafe hash without running a command`() = runBlocking {
        val shell = FakeShell { ShellResult(0, "") }
        val result = GitRunner(shell, temp()).commitDiff("abc; rm -rf /")
        assertTrue(result is GitResult.Failed)
        assertTrue(shell.commands.none { it.contains("git show") })
    }

    @Test
    fun `commitDiff caps its output and appends a truncation note`() = runBlocking {
        val shell = FakeShell { cmd ->
            if (cmd.contains("git show")) ShellResult(0, "x".repeat(2048)) else ShellResult(0, "")
        }
        val result = GitRunner(shell, temp()).commitDiff("abcdef1", maxBytes = 2048)
        val ok = result as GitResult.Ok
        assertTrue(ok.output.endsWith("\u2026 (truncated)"))
        assertTrue(shell.commands.any { it.contains("head -c 2048") })
    }

    @Test
    fun `commit rejects a blank message without running a command`() = runBlocking {
        val shell = FakeShell { ShellResult(0, "git version 2.43.0") }
        val result = GitRunner(shell, temp()).commit("   ")
        assertTrue(result is GitResult.Failed)
        assertEquals("commit message required", (result as GitResult.Failed).message)
        assertTrue(shell.commands.isEmpty(), "a blank message must not build a command")
    }

    @Test
    fun `commit stages every change then commits with the quoted message`() = runBlocking {
        val shell = FakeShell { cmd ->
            when {
                cmd.contains("--version") -> ShellResult(0, "git version 2.43.0")
                cmd == "git add -A" -> ShellResult(0, "")
                cmd.contains("git commit") -> ShellResult(0, "[main abc1234] fix the bug")
                else -> ShellResult(0, "")
            }
        }
        val result = GitRunner(shell, temp()).commit("fix the bug")
        assertTrue(result is GitResult.Ok)

        val addIndex = shell.commands.indexOf("git add -A")
        val commitIndex = shell.commands.indexOfFirst { it.contains("git commit -m") }
        assertTrue(addIndex >= 0, "add must be issued")
        assertTrue(commitIndex > addIndex, "add must precede commit: ${shell.commands}")
        assertEquals("git commit -m 'fix the bug'", shell.commands[commitIndex])
    }

    @Test
    fun `commit reports a clean tree as success`() = runBlocking {
        val shell = FakeShell { cmd ->
            when {
                cmd.contains("--version") -> ShellResult(0, "git version 2.43.0")
                cmd.contains("git commit") -> ShellResult(1, "nothing to commit, working tree clean")
                else -> ShellResult(0, "")
            }
        }
        assertEquals(GitResult.Ok("nothing to commit"), GitRunner(shell, temp()).commit("update"))
    }

    @Test
    fun `push authenticates via env and never forces`() = runBlocking {
        val shell = FakeShell { cmd ->
            when {
                cmd.contains("--version") -> ShellResult(0, "git version 2.43.0")
                cmd.contains("--abbrev-ref") -> ShellResult(0, "main\n")
                else -> ShellResult(0, "Everything up-to-date")
            }
        }
        val result = GitRunner(shell, temp()).push("s3cr3t")
        assertTrue(result is GitResult.Ok)

        // The push command is the version/rev-parse-free one.
        val pushIndex = shell.commands.indexOfFirst { it.contains("credential.helper") }
        assertTrue(pushIndex >= 0, "a push command should have been issued")
        val command = shell.commands[pushIndex]
        assertTrue(" push" in command, "it must be a push: $command")
        assertFalse(command.contains("--force"), "never force")
        assertFalse(Regex("(^|\\s)-f(\\s|$)").containsMatchIn(command), "never force")
        assertTrue("s3cr3t" !in command, "the token must never be in argv")
        assertTrue("credential.helper" in command, "auth rides on a credential helper")
        assertTrue("https://" !in command, "the token must never be embedded in a url")
        assertEquals("s3cr3t", shell.envs[pushIndex]["GITHUB_TOKEN"])
    }

    @Test
    fun `a failed push redacts the token from its output`() = runBlocking {
        val shell = FakeShell { cmd ->
            when {
                cmd.contains("--version") -> ShellResult(0, "git version 2.43.0")
                cmd.contains("credential.helper") ->
                    ShellResult(1, "fatal: Authentication failed for 'https://s3cr3t@github.com/o/r.git'")
                else -> ShellResult(0, "")
            }
        }
        val result = GitRunner(shell, temp()).push("s3cr3t")
        val failed = result as GitResult.Failed
        assertTrue("s3cr3t" !in failed.message, "the token must be redacted: ${failed.message}")
    }
}
