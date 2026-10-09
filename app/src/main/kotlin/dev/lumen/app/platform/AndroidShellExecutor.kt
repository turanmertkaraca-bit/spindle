package dev.lumen.app.platform

import android.content.Context
import dev.spindle.core.tool.PtySession
import dev.spindle.core.tool.ShellExecutor
import dev.spindle.core.tool.ShellResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
 * Output is merged, time-bounded and capped; this never throws (except
 * coroutine cancellation) — every other failure comes back as a [ShellResult].
 */
class AndroidShellExecutor(context: Context) : ShellExecutor {

    override val id = "alpine"

    private val environment = AndroidEnvironment(context)
    private val debian = DebianEnvironment(context)

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
    ): ShellResult = withContext(Dispatchers.IO) {
        try {
            // Debian wins whenever the user has installed it and its proot
            // probe passed. It is NEVER auto-downloaded here (default off), so
            // a fresh install keeps using Alpine until [DebianEnvironment.install]
            // is run explicitly.
            if (debian.active()) {
                return@withContext runInDebian(command, cwd, timeoutMs, env, onChunk)
            }

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
                scrubHostEnv(this)
                put("TERM", "dumb")
                if (ready) {
                    put("PATH", environment.shellPath())
                    put("HOME", File(environment.rootfs, "root").absolutePath)
                    put("TMPDIR", File(environment.rootfs, "tmp").absolutePath)
                    putAll(environment.proxyEnv())
                }
                putAll(env)
            }

            val process = startUnderSetsid(builder)
            collect(process, timeoutMs, onChunk)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            ShellResult(exitCode = -1, output = "failed to launch shell: ${t.message}")
        }
    }

    /** Run one agent command inside the Debian guest through proot. */
    private suspend fun runInDebian(
        command: String,
        cwd: Path,
        timeoutMs: Long,
        env: Map<String, String>,
        onChunk: ((String) -> Unit)?,
    ): ShellResult {
        val builder = debian.guestProcess(command, cwd.toFile().absolutePath)
        builder.environment().apply {
            remove("JAVA_TOOL_OPTIONS")
            remove("_JAVA_OPTIONS")
            putAll(env)
        }
        return collect(startUnderSetsid(builder), timeoutMs, onChunk)
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
            // Prefer a real Debian login shell once the layer is active.
            if (debian.active()) {
                val guest = command.takeIf { it.isNotBlank() } ?: "exec /bin/bash"
                return@withContext ProcessPtySession(
                    startUnderSetsid(debian.guestProcess(guest, cwd.toFile().absolutePath)),
                )
            }
            val ready = environment.ready()
            val shell = command.takeIf { it.isNotBlank() } ?: systemSh
            val builder = ProcessBuilder(listOf(shell))
                .directory(cwd.toFile())
                .redirectErrorStream(true)

            builder.environment().apply {
                scrubHostEnv(this)
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

            ProcessPtySession(startUnderSetsid(builder))
        } catch (t: Throwable) {
            null
        }
    }

    /** Drop Java/host secrets the app process may carry before spawning a shell. */
    private fun scrubHostEnv(env: MutableMap<String, String>) {
        val suffixes = listOf("_TOKEN", "_KEY", "_SECRET", "_PASSWORD", "_PASSWD", "_CREDENTIAL")
        env.keys.removeAll { key ->
            val upper = key.uppercase()
            key == "JAVA_TOOL_OPTIONS" || key == "_JAVA_OPTIONS" || key == "GITHUB_TOKEN" ||
                suffixes.any { upper.endsWith(it) }
        }
    }

    /**
     * Launch [builder] under `setsid` when available so the child becomes a
     * session/process-group leader (pgid == pid). A process-group TERM then
     * reaches the whole tree and proot's `--kill-on-exit` can reap its tracees.
     * If `setsid` is unavailable, or the command list cannot be rewritten, the
     * process is started unchanged. Argv order is otherwise preserved.
     */
    private fun startUnderSetsid(builder: ProcessBuilder): Process {
        if (SETSID != null) {
            runCatching { builder.command().add(0, SETSID) }
        }
        return builder.start()
    }

    private suspend fun collect(process: Process, timeoutMs: Long, onChunk: ((String) -> Unit)?): ShellResult {
        val collected = StringBuilder()
        val truncated = booleanArrayOf(false)

        val reader = Thread {
            try {
                process.inputStream.bufferedReader().use { stream ->
                    val buffer = CharArray(8192)
                    while (true) {
                        val read = stream.read(buffer)
                        if (read < 0) break
                        var emitted: String? = null
                        synchronized(collected) {
                            val room = MAX_OUTPUT_CHARS - collected.length
                            if (room <= 0) {
                                truncated[0] = true
                            } else {
                                val take = minOf(room, read)
                                collected.append(buffer, 0, take)
                                if (take < read) truncated[0] = true
                                if (onChunk != null && take > 0) emitted = String(buffer, 0, take)
                            }
                        }
                        // A throwing consumer must never abort draining.
                        emitted?.let { chunk -> runCatching { onChunk?.invoke(chunk) } }
                    }
                }
            } catch (ignored: Exception) {
            }
        }
        reader.isDaemon = true
        reader.start()

        // A non-positive timeout means "no deadline": run until the process exits
        // or the run is cancelled, so a long monitored script is never killed by
        // the harness before the agent stops.
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
                // Cooperative cancellation: press-Stop unwinds here, killing
                // the tree before the coroutine propagates.
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
            ShellResult(
                exitCode = -1,
                output = output,
                truncated = truncated[0],
                timedOut = true,
            )
        } else {
            ShellResult(
                exitCode = process.exitValue(),
                output = output,
                truncated = truncated[0],
            )
        }
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
        get() = processPid(process).takeIf { it > 0 }?.toInt() ?: 0

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
        if (!exited) killProcessTree(process)
        runCatching { reader.join(1000) }
        chunks.close()
    }
}

/** Best-effort pid from the (hidden) `Process.pid` field; -1 when unavailable. */
private fun processPid(process: Process): Long = runCatching {
    val field = process.javaClass.getDeclaredField("pid").apply { isAccessible = true }
    field.getInt(process).toLong()
}.getOrDefault(-1L)

/**
 * Terminate [process] and its descendants, mirroring the host executor.
 *
 * SIGTERM first — a best-effort process-group TERM (the group leader is the pid
 * when launched under `setsid`) so proot's `--kill-on-exit` can reap its
 * tracees — then a bounded grace, then SIGKILL. Best-effort throughout: a dead
 * or unrunnable process must never surface as an exception.
 */
private fun killProcessTree(process: Process) {
    val pid = processPid(process)
    if (pid > 0) {
        runCatching {
            Runtime.getRuntime().exec(arrayOf("kill", "-TERM", "-$pid"))
                .waitFor(TERM_GRACE_MS, TimeUnit.MILLISECONDS)
        }
    }
    runCatching { process.destroy() }
    runCatching { process.waitFor(TERM_GRACE_MS, TimeUnit.MILLISECONDS) }
    if (pid > 0) {
        runCatching {
            Runtime.getRuntime().exec(arrayOf("kill", "-KILL", "-$pid"))
                .waitFor(TERM_GRACE_MS, TimeUnit.MILLISECONDS)
        }
    }
    runCatching { process.destroyForcibly() }
    runCatching { process.waitFor(1, TimeUnit.SECONDS) }
}

private const val POLL_MS = 50L
private const val READER_JOIN_MS = 2000L
private const val TERM_GRACE_MS = 1500L

private val SETSID: String? = listOf("/system/bin/setsid", "/usr/bin/setsid")
    .firstOrNull { runCatching { File(it).canExecute() }.getOrDefault(false) }
