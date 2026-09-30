package dev.spindle.tool

import dev.spindle.core.tool.ShellExecutor
import dev.spindle.core.tool.ShellResult
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * [ShellExecutor] backed by the host `/bin/sh`. This is the dev/CI and JVM
 * implementation; a device build swaps in the Debian proot executor behind the
 * same SPI without the tool layer changing. There is no PTY here, so [openPty]
 * keeps the interface default of null.
 */
class HostShellExecutor : ShellExecutor {
    override val id = "host"

    override suspend fun run(
        command: String,
        cwd: Path,
        timeoutMs: Long,
        env: Map<String, String>,
    ): ShellResult {
        val process = ProcessBuilder(listOf("/bin/sh", "-c", command))
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
        val truncated = booleanArrayOf(false)
        val reader = Thread {
            try {
                process.inputStream.bufferedReader().use { stream ->
                    val buffer = CharArray(8192)
                    while (true) {
                        val read = stream.read(buffer)
                        if (read < 0) break
                        synchronized(collected) {
                            val room = Limits.BASH_MAX_OUTPUT_CHARS - collected.length
                            if (room <= 0) {
                                truncated[0] = true
                            } else {
                                val take = minOf(room, read)
                                collected.append(buffer, 0, take)
                                if (take < read) truncated[0] = true
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
        reader.isDaemon = true
        reader.start()

        val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            process.waitFor(1, TimeUnit.SECONDS)
            reader.join(500)
            return ShellResult(
                exitCode = -1,
                output = synchronized(collected) { collected.toString() },
                truncated = truncated[0],
                timedOut = true,
            )
        }

        process.waitFor()
        reader.join(2000)
        return ShellResult(
            exitCode = process.exitValue(),
            output = synchronized(collected) { collected.toString() },
            truncated = truncated[0],
        )
    }
}
