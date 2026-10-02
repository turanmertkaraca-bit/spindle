package dev.spindle.tool

import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HostShellExecutorTest {
    private val shell = HostShellExecutor()

    @Test
    fun idIsHost() {
        assertEquals("host", shell.id)
    }

    @Test
    fun echoReturnsExitZeroAndOutput() = runTest {
        withShellDir { dir ->
            val result = shell.run("echo hello", dir, 10_000)
            assertFalse(result.timedOut)
            assertEquals(0, result.exitCode)
            assertTrue(result.output.contains("hello"), result.output)
            assertFalse(result.truncated)
        }
    }

    @Test
    fun nonzeroExitIsReported() = runTest {
        withShellDir { dir ->
            val result = shell.run("exit 7", dir, 10_000)
            assertFalse(result.timedOut)
            assertEquals(7, result.exitCode)
        }
    }

    @Test
    fun stderrIsMergedIntoOutput() = runTest {
        withShellDir { dir ->
            val result = shell.run("echo out; echo err 1>&2", dir, 10_000)
            assertEquals(0, result.exitCode)
            assertTrue(result.output.contains("out"), result.output)
            assertTrue(result.output.contains("err"), result.output)
        }
    }

    @Test
    fun timeoutSetsFlagAndKillsProcess() = runTest {
        withShellDir { dir ->
            val started = System.nanoTime()
            val result = shell.run("sleep 5", dir, 200)
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            assertTrue(result.timedOut, "expected timedOut=true")
            assertTrue(elapsedMs < 3_000, "timeout must kill promptly, took ${elapsedMs}ms")
        }
    }

    @Test
    fun largeOutputIsTruncated() = runTest {
        withShellDir { dir ->
            val result = shell.run("yes a | head -n 60000", dir, 10_000)
            assertTrue(result.truncated, "expected truncation")
            assertEquals(Limits.BASH_MAX_OUTPUT_CHARS, result.output.length)
        }
    }

    @Test
    fun backslashesAndQuotingSurviveShC() = runTest {
        withShellDir { dir ->
            val result = shell.run("""printf '%s|%s' 'a\b' 'two words'""", dir, 10_000)
            assertEquals(0, result.exitCode)
            assertEquals("""a\b|two words""", result.output)
        }
    }

    @Test
    fun timeoutKillsDescendants() = runTest {
        withShellDir { dir ->
            val result = shell.run("sleep 30 & echo \$! > child.pid; wait", dir, 300)
            assertTrue(result.timedOut, "expected timeout, output=${result.output}")

            val pidFile = dir.resolve("child.pid")
            var childPid = -1L
            val fileDeadline = System.currentTimeMillis() + 3_000
            while (System.currentTimeMillis() < fileDeadline && childPid < 0) {
                if (Files.exists(pidFile)) {
                    childPid = Files.readString(pidFile).trim().toLongOrNull() ?: -1L
                }
                if (childPid < 0) Thread.sleep(20)
            }
            assertTrue(childPid > 0, "child pid was not written; output=${result.output}")

            var alive = true
            val deadDeadline = System.currentTimeMillis() + 3_000
            while (System.currentTimeMillis() < deadDeadline) {
                alive = ProcessHandle.of(childPid).map { it.isAlive }.orElse(false)
                if (!alive) break
                Thread.sleep(50)
            }
            assertFalse(alive, "descendant $childPid survived the timeout")
        }
    }

    @Test
    fun openPtyIsNullOnHost() = runTest {
        withShellDir { dir ->
            assertNull(shell.openPty("echo hi", dir))
        }
    }
}

private suspend fun <T> withShellDir(block: suspend (Path) -> T): T {
    val dir = Files.createTempDirectory("spindle-shell-test")
    try {
        return block(dir)
    } finally {
        dir.toFile().deleteRecursively()
    }
}
