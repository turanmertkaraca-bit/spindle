package dev.spindle.tool

import dev.spindle.core.provider.ToolSpec
import dev.spindle.core.tool.ShellExecutor
import dev.spindle.core.tool.Tool
import dev.spindle.core.tool.ToolContext
import dev.spindle.core.tool.ToolOutcome
import dev.spindle.core.tool.ToolProgress
import kotlinx.serialization.json.JsonObject

/**
 * Run a shell command through the [ShellExecutor] SPI. Output is merged,
 * time-bounded and capped, and every invocation is gated behind a permission
 * request. The default executor is the host `/bin/sh`; device builds inject the
 * Debian proot sandbox instead.
 */
class BashTool(
    private val shell: ShellExecutor = HostShellExecutor(),
) : Tool {
    override val spec = ToolSpec(
        name = "bash",
        description = "Run a shell command with /bin/sh. Standard error is merged into " +
            "standard output. Long-running commands stream output as it is produced. " +
            "timeoutMs is an optional hard timeout (default 120000ms, max 600000ms); " +
            "pass 0 or a negative value to run with no timeout until the run is stopped, " +
            "which is the right choice for builds, servers and scripts you intend to monitor.",
        parametersJson = """
            {
              "type": "object",
              "properties": {
                "command": {"type": "string", "description": "Shell command to run"},
                "timeoutMs": {"type": "integer", "description": "Optional hard timeout in milliseconds (default 120000, max 600000). <= 0 means no timeout: run until the run is stopped."},
                "cwd": {"type": "string", "description": "Directory to run in, relative to the working directory"}
              },
              "required": ["command"],
              "additionalProperties": false
            }
        """.trimIndent(),
    )

    // The per-call `timeoutMs` argument owns the deadline; the loop must not
    // wrap this tool or it would cancel a deliberate no-timeout run.
    override val timeoutMs = 0L

    override suspend fun run(input: JsonObject, ctx: ToolContext): ToolOutcome {
        val command = input.requireString("command")
        if (command.isBlank()) return ToolOutcome("command must not be blank", isError = true)

        ctx.checkAborted()

        val pattern = command.trim().split(Regex("\\s+")).firstOrNull() ?: command
        if (!ctx.requestPermission("bash", command, pattern)) {
            return ToolOutcome("denied by user", isError = true)
        }

        ctx.checkAborted()

        val directory = try {
            input.stringOrNull("cwd")?.let { resolveInsideCwd(ctx, it).toFile() } ?: ctx.cwd.toFile()
        } catch (e: IllegalArgumentException) {
            return ToolOutcome(e.message ?: "Invalid cwd", isError = true)
        }
        if (!directory.isDirectory) {
            return ToolOutcome("cwd is not a directory: ${directory.path}", isError = true)
        }

        // <= 0 means no deadline (cancel-only): the command runs until it exits or
        // the run is stopped. Absent keeps the historical 120s default.
        val requested = input.intOrNull("timeoutMs") ?: DEFAULT_TIMEOUT_MS
        val deadlineMs = if (requested <= 0) 0L else requested.toLong().coerceIn(1, MAX_TIMEOUT_MS)

        ctx.emit(ToolProgress("Running: $command"))

        // Throttled live progress from the reader thread (non-suspend emit).
        var lastProgressAt = 0L
        val onChunk: (String) -> Unit = { chunk ->
            val now = System.currentTimeMillis()
            if (now - lastProgressAt >= PROGRESS_INTERVAL_MS) {
                lastProgressAt = now
                val lastLine = chunk.trimEnd().lineSequence().lastOrNull()?.take(200).orEmpty()
                if (lastLine.isNotEmpty()) ctx.emit(ToolProgress(lastLine))
            }
        }

        val result = try {
            shell.runStreaming(command, directory.toPath(), deadlineMs, onChunk = onChunk)
        } catch (e: Exception) {
            return ToolOutcome("Failed to start command: ${e.message}", isError = true)
        }

        if (result.timedOut) {
            val message = "Command timed out after ${deadlineMs}ms"
            return ToolOutcome(
                output = "[timeout]\n$message" + if (result.output.isBlank()) "" else "\n${result.output}",
                isError = true,
                metadata = mapOf("timeout" to "true", "timeoutMs" to deadlineMs.toString()),
            )
        }

        val output = buildString {
            append("[exit ").append(result.exitCode).append("]\n")
            append(result.output)
            if (result.truncated) append("\n…[output truncated at ${Limits.BASH_MAX_OUTPUT_CHARS} chars]")
        }
        return ToolOutcome(
            output = output,
            isError = result.exitCode != 0,
            metadata = mapOf(
                "exitCode" to result.exitCode.toString(),
                "truncated" to result.truncated.toString(),
                "timeoutMs" to deadlineMs.toString(),
            ),
        )
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 120_000
        const val MAX_TIMEOUT_MS = 600_000L
        const val PROGRESS_INTERVAL_MS = 300L
    }
}
