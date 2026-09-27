package dev.spindle.tool

import dev.spindle.core.provider.ToolSpec
import dev.spindle.core.tool.Tool
import dev.spindle.core.tool.ToolContext
import dev.spindle.core.tool.ToolOutcome
import kotlinx.serialization.json.JsonObject
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/** Create (with parent dirs) and write a text file. */
class WriteTool : Tool {
    override val spec = ToolSpec(
        name = "write",
        description = "Write text content to a file in the working directory, creating any " +
            "missing parent directories. Overwrites the file if it already exists.",
        parametersJson = """
            {
              "type": "object",
              "properties": {
                "path": {"type": "string", "description": "Path to the file, relative to the working directory"},
                "content": {"type": "string", "description": "Full text content to write"}
              },
              "required": ["path", "content"],
              "additionalProperties": false
            }
        """.trimIndent(),
    )

    override suspend fun run(input: JsonObject, ctx: ToolContext): ToolOutcome {
        val raw = input.requireString("path")
        val content = input.requireString("content")
        val path = try {
            resolveInsideCwd(ctx, raw)
        } catch (e: IllegalArgumentException) {
            return ToolOutcome(e.message ?: "Invalid path: $raw", isError = true)
        }

        if (Files.isDirectory(path)) {
            return ToolOutcome("Path is a directory: $raw", isError = true)
        }

        return try {
            path.parent?.let { Files.createDirectories(it) }
            val bytes = content.toByteArray(StandardCharsets.UTF_8)
            Files.write(path, bytes)
            ToolOutcome(
                output = "Wrote $raw (${bytes.size} bytes, ${content.count { it == '\n' } + if (content.isEmpty()) 0 else 1} lines)",
                metadata = mapOf(
                    "path" to raw,
                    "bytes" to bytes.size.toString(),
                ),
            )
        } catch (e: Exception) {
            ToolOutcome("Failed to write $raw: ${e.message}", isError = true)
        }
    }
}
