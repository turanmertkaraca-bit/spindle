package dev.spindle.tool

import dev.spindle.core.tool.ShellExecutor
import dev.spindle.core.tool.ShellResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val gitJson = Json { ignoreUnknownKeys = true; isLenient = true }

private fun gitObj(text: String): JsonObject = gitJson.parseToJsonElement(text) as JsonObject

/** Records every command and its env, replying with a scripted result. */
private class RecordingShell(
    private val reply: (String) -> ShellResult = { ShellResult(0, "ok") },
) : ShellExecutor {
    override val id = "recording"
    val commands = mutableListOf<String>()
    val envs = mutableListOf<Map<String, String>>()

    override suspend fun run(command: String, cwd: Path, timeoutMs: Long, env: Map<String, String>): ShellResult {
        commands += command
        envs += env
        return reply(command)
    }
}

private suspend fun <T> withGitTempDir(block: suspend (Path) -> T): T {
    val dir = Files.createTempDirectory("spindle-git-test")
    try {
        return block(dir)
    } finally {
        dir.toFile().deleteRecursively()
    }
}

class GitToolTest {

    @Test
    fun pushAuthenticatesViaEnvAndNeverForces() = runTest {
        withGitTempDir { cwd ->
            val shell = RecordingShell()
            val tool = GitTool(shell) { "s3cr3t" }
            val ctx = ToolTestContext(cwd)

            val outcome = tool.run(gitObj("""{"op":"push"}"""), ctx)

            assertFalse(outcome.isError, outcome.output)
            val command = shell.commands.single()
            assertTrue(command.startsWith("git -c credential.helper="), command)
            assertTrue(command.contains(" push"), command)
            assertFalse(command.contains("--force"), command)
            assertFalse(command.contains("-f "), command)
            assertFalse(command.contains("s3cr3t"), "token must never reach argv: $command")
            assertFalse(command.contains("https://"), command)
            assertEquals("s3cr3t", shell.envs.single()["GITHUB_TOKEN"])
        }
    }

    @Test
    fun cloneUsesCredentialFreeUrlAndEnvToken() = runTest {
        withGitTempDir { cwd ->
            val shell = RecordingShell()
            val tool = GitTool(shell) { "s3cr3t" }
            val ctx = ToolTestContext(cwd)

            val outcome = tool.run(gitObj("""{"op":"clone","repo":"owner/name","dest":"name"}"""), ctx)

            assertFalse(outcome.isError, outcome.output)
            val command = shell.commands.single()
            assertTrue(command.contains("clone"), command)
            assertTrue(command.contains("https://github.com/owner/name.git"), command)
            assertFalse(command.contains("s3cr3t"), command)
            assertFalse(command.contains("@github"), command)
            assertEquals("s3cr3t", shell.envs.single()["GITHUB_TOKEN"])
        }
    }

    @Test
    fun networkOpWithoutTokenErrorsWithoutRunning() = runTest {
        withGitTempDir { cwd ->
            val shell = RecordingShell()
            val tool = GitTool(shell) { null }
            val ctx = ToolTestContext(cwd)

            val outcome = tool.run(gitObj("""{"op":"pull"}"""), ctx)

            assertTrue(outcome.isError, outcome.output)
            assertTrue(outcome.output.contains("No GitHub token"), outcome.output)
            assertEquals(0, shell.commands.size)
        }
    }

    @Test
    fun localOpRunsWithoutToken() = runTest {
        withGitTempDir { cwd ->
            val shell = RecordingShell { ShellResult(0, "## main") }
            val tool = GitTool(shell) { null }
            val ctx = ToolTestContext(cwd)

            val outcome = tool.run(gitObj("""{"op":"status"}"""), ctx)

            assertFalse(outcome.isError, outcome.output)
            assertEquals("git status --short --branch", shell.commands.single())
            assertTrue(shell.envs.single().isEmpty(), "a local op must not carry a token")
        }
    }

    @Test
    fun commitRequiresMessage() = runTest {
        withGitTempDir { cwd ->
            val shell = RecordingShell()
            val tool = GitTool(shell) { "s3cr3t" }
            val ctx = ToolTestContext(cwd)

            val outcome = tool.run(gitObj("""{"op":"commit"}"""), ctx)

            assertTrue(outcome.isError)
            assertTrue(outcome.output.contains("message"), outcome.output)
            assertEquals(0, shell.commands.size)
        }
    }

    @Test
    fun commitStagesThenCommits() = runTest {
        withGitTempDir { cwd ->
            val shell = RecordingShell()
            val tool = GitTool(shell) { "s3cr3t" }
            val ctx = ToolTestContext(cwd)

            tool.run(gitObj("""{"op":"commit","message":"hello world"}"""), ctx)

            val command = shell.commands.single()
            assertTrue(command.contains("git add -A"), command)
            assertTrue(command.contains("git commit -m 'hello world'"), command)
        }
    }

    @Test
    fun detectsGitNotInstalled() = runTest {
        withGitTempDir { cwd ->
            val shell = RecordingShell { ShellResult(127, "sh: git: not found") }
            val tool = GitTool(shell) { "s3cr3t" }
            val ctx = ToolTestContext(cwd)

            val outcome = tool.run(gitObj("""{"op":"status"}"""), ctx)

            assertTrue(outcome.isError)
            assertTrue(outcome.output.contains("not installed", ignoreCase = true), outcome.output)
        }
    }

    @Test
    fun pathEscapingWorkingDirectoryIsRefused() = runTest {
        withGitTempDir { cwd ->
            val shell = RecordingShell()
            val tool = GitTool(shell) { "s3cr3t" }
            val ctx = ToolTestContext(cwd)

            val outcome = tool.run(gitObj("""{"op":"add","path":"../escape"}"""), ctx)

            assertTrue(outcome.isError, outcome.output)
            assertTrue(outcome.output.contains("escapes the working directory"), outcome.output)
            assertEquals(0, shell.commands.size)
        }
    }

    @Test
    fun optionInjectionInRefIsRejected() = runTest {
        withGitTempDir { cwd ->
            val shell = RecordingShell()
            val tool = GitTool(shell) { "s3cr3t" }
            val ctx = ToolTestContext(cwd)

            val outcome = tool.run(gitObj("""{"op":"checkout","ref":"--upload-pack=evil"}"""), ctx)

            assertTrue(outcome.isError, outcome.output)
            assertEquals(0, shell.commands.size)
        }
    }

    @Test
    fun permissionDenialSkipsTheCommand() = runTest {
        withGitTempDir { cwd ->
            val shell = RecordingShell()
            val tool = GitTool(shell) { "s3cr3t" }
            val ctx = ToolTestContext(cwd).apply { permissionAllowed = false }

            val outcome = tool.run(gitObj("""{"op":"status"}"""), ctx)

            assertTrue(outcome.isError)
            assertEquals("denied by user", outcome.output)
            assertEquals(0, shell.commands.size)
        }
    }

    @Test
    fun tokenIsRedactedFromOutput() = runTest {
        withGitTempDir { cwd ->
            val shell = RecordingShell { ShellResult(1, "fatal: auth failed for s3cr3t") }
            val tool = GitTool(shell) { "s3cr3t" }
            val ctx = ToolTestContext(cwd)

            val outcome = tool.run(gitObj("""{"op":"push"}"""), ctx)

            assertFalse(outcome.output.contains("s3cr3t"), outcome.output)
            assertTrue(outcome.output.contains("***"), outcome.output)
        }
    }
    /**
     * The ops a real workflow needs that `bash` cannot provide.
     *
     * The sandbox shell is deliberately stripped of `GITHUB_TOKEN`, so a manual
     * `git pull --rebase` after a rejected push is a dead end. These are the
     * credentialed/local equivalents that close that gap. Nothing here may force or
     * destroy history: a missing `reset --hard` is intentional.
     */
    @Test
    fun newOpsBuildTheExpectedCommands() = runTest {
        withGitTempDir { cwd ->
            val shell = RecordingShell()
            val tool = GitTool(shell) { "s3cr3t" }
            val ctx = ToolTestContext(cwd)
            val cases = listOf(
                """{"op":"merge","onto":"feature"}""" to "git merge --no-edit feature",
                """{"op":"rebase","onto":"main"}""" to "git rebase main",
                """{"op":"stash"}""" to "git stash push --include-untracked",
                """{"op":"stash","action":"pop"}""" to "git stash pop",
                """{"op":"stash","action":"list"}""" to "git stash list",
                """{"op":"tag","name":"v1.0.0"}""" to "git tag -a v1.0.0 -m v1.0.0",
                """{"op":"tag","list":true}""" to "git tag --list",
                """{"op":"blame","path":"a.kt"}""" to "git blame --no-color a.kt",
                """{"op":"cherry-pick","onto":"abc123"}""" to "git cherry-pick abc123",
                """{"op":"config","name":"user.email"}""" to "git config --get user.email",
            )
            for ((json, expected) in cases) {
                val outcome = tool.run(gitObj(json), ctx)
                assertFalse(outcome.isError, "$json -> ${outcome.output}")
                assertTrue(
                    shell.commands.last().endsWith(expected) || shell.commands.last().contains(expected),
                    "$json should build [$expected], got [${shell.commands.last()}]",
                )
                assertFalse(shell.commands.last().contains("s3cr3t"), "token in argv: ${shell.commands.last()}")
            }
        }
    }

    @Test
    fun pullRebaseAndPushTagsAreAvailable() = runTest {
        withGitTempDir { cwd ->
            val shell = RecordingShell()
            val tool = GitTool(shell) { "s3cr3t" }
            val ctx = ToolTestContext(cwd)

            tool.run(gitObj("""{"op":"pull","rebase":true}"""), ctx)
            assertTrue(
                shell.commands.last().contains("pull --rebase"),
                "recovering from a rejected push needs --rebase: ${shell.commands.last()}",
            )

            tool.run(gitObj("""{"op":"push","tags":true}"""), ctx)
            assertTrue(shell.commands.last().contains("--tags"), shell.commands.last())
            assertFalse(shell.commands.last().contains("--force"), shell.commands.last())
        }
    }

    @Test
    fun theNewOpsStillValidateTheirInput() = runTest {
        withGitTempDir { cwd ->
            val shell = RecordingShell()
            val tool = GitTool(shell) { "s3cr3t" }
            val ctx = ToolTestContext(cwd)
            // A ref starting with '-' could be read as an option.
            for (json in listOf(
                """{"op":"merge"}""",
                """{"op":"merge","onto":"--upload-pack=evil"}""",
                """{"op":"rebase"}""",
                """{"op":"tag"}""",
                """{"op":"blame"}""",
                """{"op":"cherry-pick","onto":"-x"}""",
                """{"op":"stash","action":"nonsense"}""",
                """{"op":"config"}""",
            )) {
                val outcome = tool.run(gitObj(json), ctx)
                assertTrue(outcome.isError, "$json must be refused, got: ${outcome.output}")
            }
            assertTrue(shell.commands.isEmpty(), "nothing may run for an invalid request")
        }
    }

}