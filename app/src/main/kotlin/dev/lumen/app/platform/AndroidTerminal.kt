package dev.lumen.app.platform

import dev.spindle.core.tool.PtySession
import dev.spindle.core.tool.ShellExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * Owns at most one [PtySession] for the app's terminal screen. The shell is
 * started lazily on [open] with [workspace] as its working directory, and its
 * raw output chunks are streamed to the caller. Android has no true PTY without
 * native code, so this is deliberately a line-oriented pipe: the UI echoes the
 * line it sends, because the shell is not a tty and will not echo it back.
 *
 * Nothing here throws; a missing executor simply reports that it could not
 * start. [shutdown] exists for `ViewModel.onCleared`, when `viewModelScope` is
 * already cancelled.
 */
class AndroidTerminal(
    private val workspace: File,
    private val shell: ShellExecutor?,
) {

    /** Fire-and-forget teardown so a dying ViewModel still reaps the process. */
    private val teardown = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var session: PtySession? = null
    private var pump: Job? = null
    private var generation = 0

    /** The live process id, or 0 when nothing is running. */
    val pid: Int get() = session?.pid ?: 0

    /**
     * Start (or restart) the shell. [onChunk] receives raw UTF-8 chunks as they
     * arrive; [onExit] fires once the output flow ends, unless a newer session
     * has since replaced this one. Returns false when no executor is available
     * or the process cannot be started.
     */
    suspend fun open(
        scope: CoroutineScope,
        onChunk: (String) -> Unit,
        onExit: () -> Unit,
    ): Boolean {
        stop()
        val current = ++generation
        val started = shell?.openPty("", workspace.toPath()) ?: return false
        session = started
        pump = scope.launch {
            try {
                started.output.collect { chunk -> onChunk(chunk) }
            } finally {
                if (generation == current) onExit()
            }
        }
        return true
    }

    /** Write one command line; a trailing newline is appended. */
    suspend fun write(line: String) {
        session?.write(line + "\n")
    }

    /** Deliver the interrupt byte (ETX); only a real PTY would honour it. */
    suspend fun interrupt() {
        session?.write("\u0003")
    }

    /** Stop the shell, draining its output before releasing it. */
    suspend fun close() = stop()

    /** Background teardown for the ViewModel lifecycle. */
    fun shutdown() {
        teardown.launch { stop() }
    }

    private suspend fun stop() {
        generation++
        pump?.cancel()
        pump = null
        val live = session
        session = null
        runCatching { live?.close() }
    }
}
