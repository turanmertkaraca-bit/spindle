package dev.spindle.tool

import dev.spindle.core.model.FileEdit
import dev.spindle.core.model.Ids
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
        ctx.checkAborted()
        val path = try {
            resolveInsideCwd(ctx, raw)
        } catch (e: IllegalArgumentException) {
            return ToolOutcome(e.message ?: "Invalid path: $raw", isError = true)
        }

        // The whole read-modify-write (including the pre-edit snapshot) runs
        // under the per-path lock so two writes of the same file serialize.
        return withPathLock(path.toString()) {
            if (Files.isDirectory(path)) {
                return@withPathLock ToolOutcome("Path is a directory: $raw", isError = true)
            }

            val existed = Files.isRegularFile(path)
            val original = if (existed) {
                runCatching { Files.readString(path, StandardCharsets.UTF_8) }.getOrDefault("")
            } else {
                ""
            }
            val rel = path.displayPath(ctx.cwd)
            val snapshotId = if (existed) recordSnapshot(ctx, path, rel) else null

            try {
                path.parent?.let { Files.createDirectories(it) }
                val bytes = content.toByteArray(StandardCharsets.UTF_8)
                Files.write(path, bytes)
                ctx.checkAborted()
                val added = splitLines(content).size
                val removed = splitLines(original).size
                val edit = FileEdit(
                    id = Ids.new("edit"),
                    sessionId = ctx.sessionId,
                    path = rel,
                    startLine = if (added == 0) null else 1,
                    endLine = if (added == 0) null else added,
                    added = added,
                    removed = removed,
                    unifiedDiff = wholeFileDiff(rel, original, content),
                    created = !existed,
                    at = System.currentTimeMillis(),
                )
                ToolOutcome(
                    output = "Wrote $raw (${bytes.size} bytes, ${content.count { it == '\n' } + if (content.isEmpty()) 0 else 1} lines)",
                    metadata = mapOf(
                        "path" to raw,
                        "bytes" to bytes.size.toString(),
                    ),
                    edit = edit,
                    snapshotId = snapshotId,
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                ToolOutcome("Failed to write $raw: ${e.message}", isError = true)
            }
        }
    }
}
