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

/**
 * Exact-string replacement in a file. Fails loudly when the target is missing
 * or (without `replaceAll`) ambiguous, and reports a unified-diff-ish summary.
 *
 * Match semantics: occurrences are found left-to-right and do **not** overlap,
 * so a self-overlapping needle (e.g. `"aa"` in `"aaa"`) counts once and is
 * replaced once. The replacement is rebuilt from the same occurrence list used
 * for the uniqueness check, so a reported match can never silently turn into a
 * different number of applied edits.
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

        // Matches are non-overlapping and applied left-to-right. Rebuild from the
        // same occurrence list used for counting so the applied replacements can
        // never disagree with the reported match count (a self-overlapping
        // oldString such as "aa" in "aaa" counts once and replaces once).
        val selected = if (replaceAll) occurrences else occurrences.take(1)
        val updated = applyReplacements(original, selected, oldString, newString)

        val rel = path.displayPath(ctx.cwd)
        val snapshotId = recordSnapshot(ctx, path, rel)

        return try {
            Files.writeString(path, updated, StandardCharsets.UTF_8)
            val applied = selected.size
            val diff = buildDiff(
                relPath = rel,
                original = original,
                oldString = oldString,
                newString = newString,
                occurrences = occurrences,
                replaceAll = replaceAll,
            )
            // Count only lines that actually changed. Counting the whole
            // old/new blocks over-reports a multi-line edit whose middle line
            // changed (e.g. "a\nb\nc" -> "a\nB\nc" is +1/-1, not +3/-3).
            val stats = lineStats(oldString, newString)
            val added = stats.added * applied
            val removed = stats.removed * applied
            val matchLine = lineNumberAt(original, selected.first())
            val firstChanged = matchLine + stats.prefix
            val edit = FileEdit(
                id = Ids.new("edit"),
                sessionId = ctx.sessionId,
                path = rel,
                startLine = firstChanged,
                endLine = if (stats.added == 0) null else firstChanged + stats.added - 1,
                added = added,
                removed = removed,
                unifiedDiff = diff,
                created = false,
                at = System.currentTimeMillis(),
            )
            ToolOutcome(
                output = "Edited $raw ($applied replacement${if (applied == 1) "" else "s"})",
                diff = diff,
                metadata = mapOf("path" to raw, "replacements" to applied.toString()),
                edit = edit,
                snapshotId = snapshotId,
            )
        } catch (e: Exception) {
            ToolOutcome("Failed to write $raw: ${e.message}", isError = true)
        }
    }

    /** Apply [occurrences] (ascending) in one pass, splicing [newString] over each. */
    private fun applyReplacements(
        original: String,
        occurrences: List<Int>,
        oldString: String,
        newString: String,
    ): String {
        if (occurrences.isEmpty()) return original
        val removedChars = occurrences.size.toLong() * oldString.length
        val capacity = (original.length - removedChars + occurrences.size.toLong() * newString.length)
            .coerceAtLeast(0L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        val builder = StringBuilder(capacity)
        var cursor = 0
        for (index in occurrences) {
            builder.append(original, cursor, index)
            builder.append(newString)
            cursor = index + oldString.length
        }
        builder.append(original, cursor, original.length)
        return builder.toString()
    }

    /**
     * Line-level diff stats for a single replacement: trims the common line
     * prefix/suffix from the old and new blocks so unchanged surrounding lines
     * are not counted as added/removed.
     */
    private fun lineStats(oldString: String, newString: String): LineStats {
        val oldLines = oldString.split('\n')
        val newLines = newString.split('\n')
        var prefix = 0
        while (prefix < oldLines.size && prefix < newLines.size && oldLines[prefix] == newLines[prefix]) {
            prefix++
        }
        var suffix = 0
        while (suffix < oldLines.size - prefix && suffix < newLines.size - prefix &&
            oldLines[oldLines.size - 1 - suffix] == newLines[newLines.size - 1 - suffix]
        ) {
            suffix++
        }
        return LineStats(
            added = newLines.size - prefix - suffix,
            removed = oldLines.size - prefix - suffix,
            prefix = prefix,
        )
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

    private data class LineStats(val added: Int, val removed: Int, val prefix: Int)
}
