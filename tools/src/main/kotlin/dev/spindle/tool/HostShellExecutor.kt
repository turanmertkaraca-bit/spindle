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

        val headCap = Limits.BASH_MAX_OUTPUT_CHARS * 2 / 3
        val tailCap = Limits.BASH_MAX_OUTPUT_CHARS - headCap
        val collected = BoundedOutput(headCap, tailCap)
        val reader = Thread { drain(process.inputStream, collected, onChunk) }
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

        val (output, truncated) = synchronized(collected) {
            collected.render(::truncationMarker) to collected.truncated
        }
        return if (timedOut) {
            ShellResult(exitCode = -1, output = output, truncated = truncated, timedOut = true)
        } else {
            ShellResult(exitCode = process.exitValue(), output = output, truncated = truncated)
        }
    }

    private fun shellCommand(command: String): List<String> {
        val setsid = SETSID
        return if (setsid != null) listOf(setsid, "/bin/sh", "-c", command)
        else listOf("/bin/sh", "-c", command)
    }

    /**
     * Drain stdout/stderr into [output], which retains a bounded head and tail
     * up to [Limits.BASH_MAX_OUTPUT_CHARS] characters. Every chunk is streamed
     * to [onChunk] as it arrives; a throwing consumer must never abort draining
     * the process.
     */
    private fun drain(
        input: InputStream,
        output: BoundedOutput,
        onChunk: ((String) -> Unit)?,
    ) {
        try {
            input.bufferedReader().use { stream ->
                val buffer = CharArray(8192)
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    synchronized(output) { output.append(buffer, 0, read) }
                    if (onChunk != null) {
                        val chunk = String(buffer, 0, read)
                        runCatching { onChunk.invoke(chunk) }
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    /**
     * Head+tail bounded capture for shell output. The first [headCap] characters
     * and the last [tailCap] characters are retained; anything between is dropped
     * but its count is remembered so [render] can say how much was elided.
     * Retaining the tail matters because a build that dies prints its failure
     * last, and the old first-N cap threw that line away.
     */
    private class BoundedOutput(private val headCap: Int, private val tailCap: Int) {
        private val head = StringBuilder()
        private val tail = StringBuilder()
        private var total = 0L

        val truncated: Boolean get() = total > (head.length + tail.length).toLong()

        fun append(buffer: CharArray, offset: Int, length: Int) {
            if (length <= 0) return
            total += length
            var off = offset
            var len = length
            val headRoom = headCap - head.length
            if (headRoom > 0) {
                val take = minOf(headRoom, len)
                head.append(buffer, off, take)
                off += take
                len -= take
            }
            if (len > 0) {
                tail.append(buffer, off, len)
                val excess = tail.length - tailCap
                if (excess > 0) tail.delete(0, excess)
            }
        }

        fun render(markerFor: (Long) -> String): String {
            val dropped = total - (head.length + tail.length).toLong()
            return if (dropped <= 0) head.toString() + tail.toString()
            else head.toString() + markerFor(dropped) + tail.toString()
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

/** Elision notice inserted between the retained head and tail of shell output. */
private fun truncationMarker(dropped: Long): String = "\n…[output truncated $dropped chars]…\n"
