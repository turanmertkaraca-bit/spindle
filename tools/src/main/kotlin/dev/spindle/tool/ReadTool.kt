package dev.spindle.tool

import dev.spindle.core.provider.ToolSpec
import dev.spindle.core.tool.Tool
import dev.spindle.core.tool.ToolContext
import dev.spindle.core.tool.ToolOutcome
import kotlinx.serialization.json.JsonObject
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

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
        if (!Files.isRegularFile(path)) {
            return ToolOutcome("Path is not a regular file: $raw", isError = true)
        }

        // Guard against loading a huge file into memory. The byte cap bounds both
        // the initial read and the line scan below.
        val size = try {
            Files.size(path)
        } catch (e: Exception) {
            return ToolOutcome("Failed to read $raw: ${e.message}", isError = true)
        }
        if (size > Limits.READ_MAX_BYTES) {
            return ToolOutcome(
                "File is too large to read safely: $raw is $size bytes (limit ${Limits.READ_MAX_BYTES}). " +
                    "Use offset/limit via a narrower tool or grep for specific content.",
                isError = true,
                metadata = mapOf("path" to raw, "bytes" to size.toString(), "truncated" to "true"),
            )
        }
        if (looksBinary(path)) {
            return ToolOutcome("Refusing to read binary file: $raw", isError = true, metadata = mapOf("path" to raw))
        }

        val offset = (input.intOrNull("offset") ?: 1).coerceAtLeast(1)
        val requested = (input.intOrNull("limit") ?: Limits.READ_MAX_LINES).coerceAtLeast(1)
        val limit = minOf(requested, Limits.READ_MAX_LINES)

        val window = try {
            readWindow(path, offset, limit)
        } catch (e: Exception) {
            return ToolOutcome("Failed to read $raw: ${e.message}", isError = true)
        }

        if (offset > window.totalLines + 1) {
            return ToolOutcome("Offset $offset is past the end of $raw (${window.totalLines} lines)", isError = true)
        }

        val start = offset - 1
        val end = minOf(window.totalLines, start + limit)
        val builder = StringBuilder()
        for (i in window.lines.indices) {
            builder.append((start + i + 1).toString().padStart(6)).append('\t')
                .append(window.lines[i]).append('\n')
        }

        val shown = window.lines.size
        val truncated = end < window.totalLines || window.scanCapped
        val totalLabel = if (window.scanCapped) "≥${window.totalLines}" else window.totalLines.toString()
        val note = if (truncated) {
            "\n…[truncated: showing lines ${start + 1}-$end of $totalLabel; " +
                "use offset/limit to read more]"
        } else {
            ""
        }
        val header = "$raw (lines ${start + 1}-$end of $totalLabel)\n"
        return ToolOutcome(
            output = header + builder.toString() + note,
            metadata = mapOf(
                "path" to raw,
                "lines" to shown.toString(),
                "totalLines" to window.totalLines.toString(),
                "truncated" to truncated.toString(),
            ),
        )
    }

    /**
     * Stream the file once, keeping only the requested `[offset, offset+limit)`
     * window in memory while counting total lines. The 2 MB byte guard above
     * bounds both the scan and the line count.
     */
    private fun readWindow(path: Path, offset: Int, limit: Int): Window {
        val lines = ArrayList<String>(minOf(limit, 64))
        var total = 0
        var capped = false
        Files.newBufferedReader(path, StandardCharsets.UTF_8).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                total++
                if (total >= offset && lines.size < limit) lines.add(line)
                if (total >= Limits.READ_MAX_SCAN_LINES) {
                    capped = true
                    break
                }
            }
        }
        return Window(lines, total, capped)
    }

    private class Window(val lines: List<String>, val totalLines: Int, val scanCapped: Boolean)
}
