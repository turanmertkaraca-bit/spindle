package dev.spindle.core.tool

import kotlinx.coroutines.flow.Flow
import java.nio.file.Path

/** Result of a one-shot shell command. */
data class ShellResult(
    val exitCode: Int,
    val output: String,
    val truncated: Boolean = false,
    val timedOut: Boolean = false,
)

/**
 * Where `bash` actually runs. The dev/CI implementation uses the host `/bin/sh`;
 * on device it runs inside the project's Debian proot rootfs (so apt, git,
 * python, node and gcc are real). Keeping this an SPI means the tool layer is
 * identical on both, and the old app's sandbox can be ported without touching
 * any tool.
 */
interface ShellExecutor {
    /** Stable id for diagnostics, e.g. "host" or "proot". */
    val id: String

    /** Run a command, merging stderr into stdout, bounded by [timeoutMs]. */
    suspend fun run(
        command: String,
        cwd: Path,
        timeoutMs: Long,
        env: Map<String, String> = emptyMap(),
    ): ShellResult

    /**
     * Open an interactive terminal, or null when this executor has no PTY.
     * The default returns null so a missing PTY degrades to [run] instead of
     * failing.
     */
    suspend fun openPty(command: String, cwd: Path): PtySession? = null
}

/** An interactive terminal session (used by the on-device terminal panel). */
interface PtySession {
    val pid: Int
    val output: Flow<String>
    suspend fun write(text: String)
    suspend fun resize(cols: Int, rows: Int)
    suspend fun close()
}
