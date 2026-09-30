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
            "standard output. The command is killed and reported as an error if it runs " +
            "longer than timeoutMs (default 120000ms, maximum 600000ms). Returns the " +
            "combined output prefixed with the exit code.",
        parametersJson = """
            {
              "type": "object",
              "properties": {
                "command": {"type": "string", "description": "Shell command to run"},
                "timeoutMs": {"type": "integer", "description": "Hard timeout in milliseconds (default 120000, max 600000)"},
                "cwd": {"type": "string", "description": "Directory to run in, relative to the working directory"}
              },
              "required": ["command"],
              "additionalProperties": false
            }
        """.trimIndent(),
    )

    override suspend fun run(input: JsonObject, ctx: ToolContext): ToolOutcome {
        val command = input.requireString("command")
        if (command.isBlank()) return ToolOutcome("command must not be blank", isError = true)

        val pattern = command.trim().split(Regex("\\s+")).firstOrNull() ?: command
        if (!ctx.requestPermission("bash", command, pattern)) {
            return ToolOutcome("denied by user", isError = true)
        }

        val directory = try {
            input.stringOrNull("cwd")?.let { resolveInsideCwd(ctx, it).toFile() } ?: ctx.cwd.toFile()
        } catch (e: IllegalArgumentException) {
            return ToolOutcome(e.message ?: "Invalid cwd", isError = true)
        }
        if (!directory.isDirectory) {
            return ToolOutcome("cwd is not a directory: ${directory.path}", isError = true)
        }

        val timeoutMs = (input.intOrNull("timeoutMs") ?: DEFAULT_TIMEOUT_MS)
            .coerceAtLeast(1)
            .coerceAtMost(MAX_TIMEOUT_MS)

        ctx.emit(ToolProgress("Running: $command"))

        val result = try {
            shell.run(command, directory.toPath(), timeoutMs.toLong())
        } catch (e: Exception) {
            return ToolOutcome("Failed to start command: ${e.message}", isError = true)
        }

        if (result.timedOut) {
            val message = "Command timed out after ${timeoutMs}ms"
            return ToolOutcome(
                output = "[timeout]\n$message" + if (result.output.isBlank()) "" else "\n${result.output}",
                isError = true,
                metadata = mapOf("timeout" to "true", "timeoutMs" to timeoutMs.toString()),
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
                "timeoutMs" to timeoutMs.toString(),
            ),
        )
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 120_000
        const val MAX_TIMEOUT_MS = 600_000
    }
}
