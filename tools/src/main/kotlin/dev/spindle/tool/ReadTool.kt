package dev.spindle.tool

import dev.spindle.core.provider.ToolSpec
import dev.spindle.core.tool.Tool
import dev.spindle.core.tool.ToolContext
import dev.spindle.core.tool.ToolOutcome
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files

/** Read a text file and return numbered lines, capped at [Limits.READ_MAX_LINES]. */
class ReadTool : Tool {
    override val spec = ToolSpec(
        name = "read",
        description = "Read a text file from the working directory. Returns the content " +
            "with 1-based line numbers, optionally starting at an offset and limited to a " +
            "number of lines.",
        parametersJson = """
            {
              "type": "object",
              "properties": {
                "path": {"type": "string", "description": "Path to the file, relative to the working directory"},
                "offset": {"type": "integer", "description": "1-based line number to start reading from"},
                "limit": {"type": "integer", "description": "Maximum number of lines to read"}
              },
              "required": ["path"],
              "additionalProperties": false
            }
        """.trimIndent(),
    )

    override suspend fun run(input: JsonObject, ctx: ToolContext): ToolOutcome {
        val raw = input.requireString("path")
        val path = try {
            resolveInsideCwd(ctx, raw)
        } catch (e: IllegalArgumentException) {
            return ToolOutcome(e.message ?: "Invalid path: $raw", isError = true)
        }
        if (!Files.exists(path)) return ToolOutcome("File not found: $raw", isError = true)
        if (Files.isDirectory(path)) return ToolOutcome("Path is a directory, not a file: $raw", isError = true)

        val offset = (input.intOrNull("offset") ?: 1).coerceAtLeast(1)
        val requested = (input.intOrNull("limit") ?: Limits.READ_MAX_LINES).coerceAtLeast(1)
        val limit = minOf(requested, Limits.READ_MAX_LINES)

        val lines = try {
            Files.readAllLines(path)
        } catch (e: Exception) {
            return ToolOutcome("Failed to read $raw: ${e.message}", isError = true)
        }

        if (offset > lines.size + 1) {
            return ToolOutcome("Offset $offset is past the end of $raw (${lines.size} lines)", isError = true)
        }

        val start = offset - 1
        val end = minOf(lines.size, start + limit)
        val builder = StringBuilder()
        for (i in start until end) {
            builder.append((i + 1).toString().padStart(6)).append('\t').append(lines[i]).append('\n')
        }

        val shown = end - start
        val truncated = end < lines.size
        val note = if (truncated) {
            "\n…[truncated: showing lines ${start + 1}-$end of ${lines.size}; " +
                "use offset/limit to read more]"
        } else {
            ""
        }
        val header = "$raw (lines ${start + 1}-$end of ${lines.size})\n"
        return ToolOutcome(
            output = header + builder.toString() + note,
            metadata = mapOf(
                "path" to raw,
                "lines" to shown.toString(),
                "totalLines" to lines.size.toString(),
                "truncated" to truncated.toString(),
            ),
        )
    }
}
