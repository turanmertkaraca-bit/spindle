package dev.spindle.tool

import dev.spindle.core.provider.ToolSpec
import dev.spindle.core.tool.Tool
import dev.spindle.core.tool.ToolContext
import dev.spindle.core.tool.ToolOutcome
import kotlinx.serialization.json.JsonObject
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/**
 * Exact-string replacement in a file. Fails loudly when the target is missing
 * or (without `replaceAll`) ambiguous, and reports a unified-diff-ish summary.
 */
class EditTool : Tool {
    override val spec = ToolSpec(
        name = "edit",
        description = "Replace an exact string in a file. By default the old string must " +
            "appear exactly once; set replaceAll to replace every occurrence. Returns the " +
            "resulting diff.",
        parametersJson = """
            {
              "type": "object",
              "properties": {
                "path": {"type": "string", "description": "Path to the file, relative to the working directory"},
                "oldString": {"type": "string", "description": "Exact text to find"},
                "newString": {"type": "string", "description": "Replacement text"},
                "replaceAll": {"type": "boolean", "description": "Replace every occurrence instead of requiring a unique match"}
              },
              "required": ["path", "oldString", "newString"],
              "additionalProperties": false
            }
        """.trimIndent(),
    )

    override suspend fun run(input: JsonObject, ctx: ToolContext): ToolOutcome {
        val raw = input.requireString("path")
        val oldString = input.requireString("oldString")
        val newString = input.requireString("newString")
        val replaceAll = input.boolOrNull("replaceAll") ?: false
        val path = try {
            resolveInsideCwd(ctx, raw)
        } catch (e: IllegalArgumentException) {
            return ToolOutcome(e.message ?: "Invalid path: $raw", isError = true)
        }

        if (oldString.isEmpty()) {
            return ToolOutcome("oldString must not be empty", isError = true)
        }
        if (!Files.exists(path)) return ToolOutcome("File not found: $raw", isError = true)
        if (Files.isDirectory(path)) return ToolOutcome("Path is a directory: $raw", isError = true)

        val original = try {
            Files.readString(path, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            return ToolOutcome("Failed to read $raw: ${e.message}", isError = true)
        }

        val occurrences = findOccurrences(original, oldString)
        if (occurrences.isEmpty()) {
            return ToolOutcome(
                "oldString not found in $raw. It must match the file content exactly, " +
                    "including whitespace and indentation.",
                isError = true,
            )
        }
        if (occurrences.size > 1 && !replaceAll) {
            return ToolOutcome(
                "oldString appears ${occurrences.size} times in $raw; provide more surrounding " +
                    "context or set replaceAll=true.",
                isError = true,
            )
        }

        val updated = if (replaceAll) {
            original.replace(oldString, newString)
        } else {
            original.replaceFirst(oldString, newString)
        }

        return try {
            Files.writeString(path, updated, StandardCharsets.UTF_8)
            val applied = if (replaceAll) occurrences.size else 1
            val diff = buildDiff(
                relPath = path.displayPath(ctx.cwd),
                original = original,
                oldString = oldString,
                newString = newString,
                occurrences = occurrences,
                replaceAll = replaceAll,
            )
            ToolOutcome(
                output = "Edited $raw ($applied replacement${if (applied == 1) "" else "s"})",
                diff = diff,
                metadata = mapOf("path" to raw, "replacements" to applied.toString()),
            )
        } catch (e: Exception) {
            ToolOutcome("Failed to write $raw: ${e.message}", isError = true)
        }
    }

    private fun findOccurrences(haystack: String, needle: String): List<Int> {
        val result = ArrayList<Int>()
        var index = haystack.indexOf(needle)
        while (index >= 0) {
            result.add(index)
            index = haystack.indexOf(needle, index + needle.length)
        }
        return result
    }

    private fun buildDiff(
        relPath: String,
        original: String,
        oldString: String,
        newString: String,
        occurrences: List<Int>,
        replaceAll: Boolean,
    ): String {
        val removed = oldString.split('\n')
        val added = newString.split('\n')
        val builder = StringBuilder()
        builder.append("--- a/").append(relPath).append('\n')
        builder.append("+++ b/").append(relPath).append('\n')
        val indices = if (replaceAll) occurrences else occurrences.take(1)
        for (index in indices) {
            val line = lineNumberAt(original, index)
            builder.append("@@ -").append(line).append(',').append(removed.size)
                .append(" +").append(line).append(',').append(added.size).append(" @@\n")
            removed.forEach { builder.append('-').append(it).append('\n') }
            added.forEach { builder.append('+').append(it).append('\n') }
        }
        return builder.toString()
    }

    private fun lineNumberAt(text: String, offset: Int): Int {
        var line = 1
        for (i in 0 until minOf(offset, text.length)) {
            if (text[i] == '\n') line++
        }
        return line
    }
}
