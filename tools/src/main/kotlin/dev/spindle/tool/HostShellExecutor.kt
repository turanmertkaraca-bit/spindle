package dev.spindle.tool

import dev.spindle.core.tool.ShellExecutor
import dev.spindle.core.tool.ShellResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.InputStream
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [ShellExecutor] backed by the host `/bin/sh`. This is the dev/CI and JVM
 * implementation; a device build swaps in the Debian proot executor behind the
 * same SPI without the tool layer changing. There is no PTY here, so [openPty]
 * keeps the interface default of null.
 *
 * The shell is launched in its own session/process group via `setsid` when
 * available so a timeout or cancellation can tear down the whole descendant
 * tree, not just the direct child.
 */
class HostShellExecutor : ShellExecutor {
    override val id = "host"

    override suspend fun run(
        command: String,
        cwd: Path,
        timeoutMs: Long,
        env: Map<String, String>,
    ): ShellResult = execute(command, cwd, timeoutMs, env, null)

    override suspend fun runStreaming(
        command: String,
        cwd: Path,
        timeoutMs: Long,
        env: Map<String, String>,
        onChunk: (String) -> Unit,
    ): ShellResult = execute(command, cwd, timeoutMs, env, onChunk)

    private suspend fun execute(
        command: String,
        cwd: Path,
        timeoutMs: Long,
        env: Map<String, String>,
        onChunk: ((String) -> Unit)?,
    ): ShellResult {
        val process = ProcessBuilder(shellCommand(command))
            .directory(cwd.toFile())
            .redirectErrorStream(true)
            .apply {
                environment().apply {
                    remove("JAVA_TOOL_OPTIONS")
                    remove("_JAVA_OPTIONS")
                    put("TERM", "dumb")
                    putAll(env)
                }
            }
            .start()

        val collected = StringBuilder()
        val truncated = AtomicBoolean(false)
        val reader = Thread { drain(process.inputStream, collected, truncated, onChunk) }
        reader.isDaemon = true
        reader.start()

        // A non-positive timeout means "no deadline": run until the process exits
        // or the caller cancels. This is what lets the agent monitor a long
        // build/script without the harness killing it out from under the run.
        val hasDeadline = timeoutMs > 0
        var finished = false
        var timedOut = false
        try {
            val deadline = if (hasDeadline) System.nanoTime() + timeoutMs * 1_000_000L else Long.MAX_VALUE
            while (true) {
                if (process.waitFor(POLL_MS, TimeUnit.MILLISECONDS)) {
                    finished = true
                    break
                }
                if (hasDeadline && System.nanoTime() >= deadline) {
                    timedOut = true
                    break
                }
                // Cooperative cancellation: deregister the process before the
                // coroutine unwinds.
                currentCoroutineContext().ensureActive()
            }
        } catch (e: CancellationException) {
            killProcessTree(process)
            reader.join(READER_JOIN_MS)
            throw e
        }

        if (!finished) killProcessTree(process)
        reader.join(READER_JOIN_MS)

        val output = synchronized(collected) { collected.toString() }
        return if (timedOut) {
            ShellResult(exitCode = -1, output = output, truncated = truncated.get(), timedOut = true)
        } else {
            ShellResult(exitCode = process.exitValue(), output = output, truncated = truncated.get())
        }
    }

    private fun shellCommand(command: String): List<String> {
        val setsid = SETSID
        return if (setsid != null) listOf(setsid, "/bin/sh", "-c", command)
        else listOf("/bin/sh", "-c", command)
    }

    /** Drain stdout/stderr into [collected], capping at [Limits.BASH_MAX_OUTPUT_CHARS]. */
    private fun drain(
        input: InputStream,
        collected: StringBuilder,
        truncated: AtomicBoolean,
        onChunk: ((String) -> Unit)?,
    ) {
        try {
            input.bufferedReader().use { stream ->
                val buffer = CharArray(8192)
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    var emitted: String? = null
                    synchronized(collected) {
                        val room = Limits.BASH_MAX_OUTPUT_CHARS - collected.length
                        if (room <= 0) {
                            truncated.set(true)
                        } else {
                            val take = minOf(room, read)
                            collected.append(buffer, 0, take)
                            if (take < read) truncated.set(true)
                            if (onChunk != null && take > 0) emitted = String(buffer, 0, take)
                        }
                    }
                    // A throwing consumer must never abort draining the process.
                    emitted?.let { chunk -> runCatching { onChunk?.invoke(chunk) } }
                }
            }
        } catch (_: Exception) {
        }
    }

    /**
     * Terminate [process] and its descendants. Best-effort: try a process-group
     * TERM first (the group leader is the pid when launched under `setsid`),
     * then forcibly destroy every known descendant and the process itself.
     */
    private fun killProcessTree(process: Process) {
        val pid = try {
            process.pid()
        } catch (_: Exception) {
            -1L
        }
        if (pid > 0) {
            runCatching {
                ProcessBuilder("kill", "-TERM", "-$pid")
                    .start()
                    .waitFor(200, TimeUnit.MILLISECONDS)
            }
        }
        val descendants = try {
            process.toHandle().descendants().toList()
        } catch (_: Exception) {
            emptyList()
        }
        descendants.forEach { runCatching { it.destroyForcibly() } }
        runCatching { process.destroyForcibly() }
        runCatching { process.waitFor(1, TimeUnit.SECONDS) }
    }

    private companion object {
        const val POLL_MS = 50L
        const val READER_JOIN_MS = 2000L
        val SETSID: String? = listOf("/usr/bin/setsid", "/bin/setsid", "/system/bin/setsid")
            .firstOrNull { File(it).canExecute() }
    }
}
