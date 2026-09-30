package dev.lumen.app.platform

import android.content.Context
import dev.spindle.core.tool.PtySession
import dev.spindle.core.tool.ShellExecutor
import dev.spindle.core.tool.ShellResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * [ShellExecutor] that runs the agent's shell inside the real Alpine userland
 * provided by [AndroidEnvironment]. When the rootfs is not installed it falls
 * back to a plain Android `/system/bin/sh`, exactly like the host executor, so
 * the app still works (and CI still passes on a bare JVM).
 *
 * Output is merged, time-bounded and capped; this never throws — every failure
 * comes back as a [ShellResult]. There is no PTY yet, so [openPty] returns null.
 */
class AndroidShellExecutor(context: Context) : ShellExecutor {

    override val id = "alpine"

    private val environment = AndroidEnvironment(context)

    /** Guards the one-time rootfs install on the first shell call. */
    private val installLock = Mutex()
    private var installAttempted = false

    /** On device `/system/bin/sh`; on the JVM (tests) the host `/bin/sh`. */
    private val systemSh: String =
        if (File("/system/bin/sh").exists()) "/system/bin/sh" else "/bin/sh"

    override suspend fun run(
        command: String,
        cwd: Path,
        timeoutMs: Long,
        env: Map<String, String>,
    ): ShellResult = withContext(Dispatchers.IO) {
        try {
            // Install the userland once, on first use. If it fails the run
            // still proceeds on the fallback shell.
            if (!environment.ready()) {
                installLock.withLock {
                    if (!installAttempted) {
                        installAttempted = true
                        runCatching { environment.ensureInstalled() }
                    }
                }
            }
            val ready = environment.ready()
            val builder = ProcessBuilder(listOf(systemSh, "-c", command))
                .directory(cwd.toFile())
                .redirectErrorStream(true)

            builder.environment().apply {
                remove("JAVA_TOOL_OPTIONS")
                remove("_JAVA_OPTIONS")
                put("TERM", "dumb")
                if (ready) {
                    put("PATH", environment.shellPath())
                    put("HOME", File(environment.rootfs, "root").absolutePath)
                    put("TMPDIR", File(environment.rootfs, "tmp").absolutePath)
                    putAll(environment.proxyEnv())
                }
                putAll(env)
            }

            val process = builder.start()
            collect(process, timeoutMs)
        } catch (t: Throwable) {
            ShellResult(exitCode = -1, output = "failed to launch shell: ${t.message}")
        }
    }

    /** Interactive terminal comes later; null degrades to [run]. */
    override suspend fun openPty(command: String, cwd: Path): PtySession? = null

    private fun collect(process: Process, timeoutMs: Long): ShellResult {
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
                            val room = MAX_OUTPUT_CHARS - collected.length
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
            } catch (ignored: Exception) {
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

    private companion object {
        const val MAX_OUTPUT_CHARS = 50_000
    }
}
