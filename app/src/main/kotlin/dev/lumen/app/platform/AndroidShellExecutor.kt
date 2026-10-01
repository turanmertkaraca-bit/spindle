package dev.lumen.app.platform

import android.content.Context
import dev.spindle.core.tool.PtySession
import dev.spindle.core.tool.ShellExecutor
import dev.spindle.core.tool.ShellResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * [ShellExecutor] that runs the agent's shell inside the real Alpine userland
 * provided by [AndroidEnvironment]. When the rootfs is not installed it falls
 * back to a plain Android `/system/bin/sh`, exactly like the host executor, so
 * the app still works (and CI still passes on a bare JVM).
 *
 * Output is merged, time-bounded and capped; this never throws — every failure
 * comes back as a [ShellResult].
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

    /**
     * Start a shell for the interactive terminal panel. Android has no PTY
     * without native code, so this is a pragmatic, line-oriented approximation:
     * a [ProcessBuilder] pipe with merged stderr. The caller writes whole lines
     * and reads raw UTF-8 chunks. Returns null (never throws) if the process
     * cannot be started; the panel then shows "terminal unavailable".
     */
    override suspend fun openPty(command: String, cwd: Path): PtySession? = withContext(Dispatchers.IO) {
        try {
            val ready = environment.ready()
            val shell = command.takeIf { it.isNotBlank() } ?: systemSh
            val builder = ProcessBuilder(listOf(shell))
                .directory(cwd.toFile())
                .redirectErrorStream(true)

            builder.environment().apply {
                remove("JAVA_TOOL_OPTIONS")
                remove("_JAVA_OPTIONS")
                put("TERM", "xterm-256color")
                if (ready) {
                    put("PATH", environment.shellPath())
                    put("HOME", File(environment.rootfs, "root").absolutePath)
                    put("TMPDIR", File(environment.rootfs, "tmp").absolutePath)
                    putAll(environment.proxyEnv())
                } else {
                    put("PATH", System.getenv("PATH") ?: "/system/bin:/usr/bin:/bin")
                }
            }

            ProcessPtySession(builder.start())
        } catch (t: Throwable) {
            null
        }
    }

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

/**
 * A pipe-backed "terminal". It is not a PTY: there is no raw mode, no window
 * size, no job control and no line discipline, so `resize` is a no-op and
 * Ctrl-C is just the ETX byte. What it does give the UI is exactly what the
 * panel needs: a live shell process whose merged output streams as raw UTF-8
 * chunks and whose stdin accepts whole lines.
 *
 * The reader runs on a daemon thread and publishes to an unbounded channel;
 * [output] completes when the process closes or [close] is called. Every entry
 * point is defensive — a dead process ends the flow, it never throws.
 */
private class ProcessPtySession(private val process: Process) : PtySession {

    private val chunks = Channel<String>(Channel.UNLIMITED)
    private val reader = Thread(::pump)

    @Volatile
    private var closed = false

    override val output: Flow<String> = chunks.receiveAsFlow()

    /** Best-effort pid via the (hidden) `Process.pid` field; 0 when unavailable. */
    override val pid: Int
        get() = runCatching {
            val field = process.javaClass.getDeclaredField("pid").apply { isAccessible = true }
            field.getInt(process)
        }.getOrDefault(0)

    init {
        reader.isDaemon = true
        reader.start()
    }

    private fun pump() {
        try {
            InputStreamReader(process.inputStream, StandardCharsets.UTF_8).use { input ->
                val buffer = CharArray(8192)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    chunks.trySend(String(buffer, 0, read))
                }
            }
        } catch (ignored: Throwable) {
            // EOF, a destroyed process or a closed channel: all normal teardown.
        } finally {
            chunks.close()
        }
    }

    override suspend fun write(text: String) {
        if (closed) return
        try {
            val out = process.outputStream
            out.write(text.toByteArray(StandardCharsets.UTF_8))
            out.flush()
        } catch (ignored: Throwable) {
            // Writing to a dead process is a no-op, never an error.
        }
    }

    override suspend fun resize(cols: Int, rows: Int) = Unit

    override suspend fun close() {
        closed = true
        runCatching { process.outputStream.close() }
        val exited = runCatching { process.waitFor(500, TimeUnit.MILLISECONDS) }.getOrDefault(false)
        if (!exited) {
            runCatching { process.destroy() }
            val gone = runCatching { process.waitFor(300, TimeUnit.MILLISECONDS) }.getOrDefault(false)
            if (!gone) runCatching { process.destroyForcibly() }
        }
        runCatching { reader.join(1000) }
        chunks.close()
    }
}
