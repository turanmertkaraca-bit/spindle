package dev.spindle.tool

import dev.spindle.core.provider.ToolSpec
import dev.spindle.core.tool.Tool
import dev.spindle.core.tool.ToolContext
import dev.spindle.core.tool.ToolOutcome
import dev.spindle.core.tool.ToolProgress
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.TimeUnit

/**
 * Run a shell command through `/bin/sh -c`. Output is merged, time-bounded and
 * capped, and every invocation is gated behind a permission request.
 */
class BashTool : Tool {
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

        val process = try {
            ProcessBuilder(listOf("/bin/sh", "-c", command))
                .directory(directory)
                .redirectErrorStream(true)
                .apply {
                    environment()["TERM"] = "dumb"
                    environment().remove("JAVA_TOOL_OPTIONS")
                    environment().remove("_JAVA_OPTIONS")
                }
                .start()
        } catch (e: Exception) {
            return ToolOutcome("Failed to start command: ${e.message}", isError = true)
        }

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

        val finished = process.waitFor(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            process.waitFor(1, TimeUnit.SECONDS)
            reader.join(500)
            val partial = synchronized(collected) { collected.toString() }
            val message = "Command timed out after ${timeoutMs}ms"
            return ToolOutcome(
                output = "[timeout]\n$message" + if (partial.isBlank()) "" else "\n$partial",
                isError = true,
                metadata = mapOf("timeout" to "true", "timeoutMs" to timeoutMs.toString()),
            )
        }

        process.waitFor()
        reader.join(2000)

        val exit = process.exitValue()
        val body = synchronized(collected) { collected.toString() }
        val output = buildString {
            append("[exit ").append(exit).append("]\n")
            append(body)
            if (truncated[0]) append("\n…[output truncated at ${Limits.BASH_MAX_OUTPUT_CHARS} chars]")
        }
        return ToolOutcome(
            output = output,
            isError = exit != 0,
            metadata = mapOf(
                "exitCode" to exit.toString(),
                "truncated" to truncated[0].toString(),
                "timeoutMs" to timeoutMs.toString(),
            ),
        )
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 120_000
        const val MAX_TIMEOUT_MS = 600_000
    }
}
