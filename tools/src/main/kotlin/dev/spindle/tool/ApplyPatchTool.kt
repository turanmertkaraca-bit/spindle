package dev.spindle.tool

import dev.spindle.core.model.FileEdit
import dev.spindle.core.model.Ids
import dev.spindle.core.provider.ToolSpec
import dev.spindle.core.tool.Tool
import dev.spindle.core.tool.ToolContext
import dev.spindle.core.tool.ToolEdit
import dev.spindle.core.tool.ToolOutcome
import kotlinx.serialization.json.JsonObject
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Apply a multi-file patch in the opencode patch format:
 *
 * ```
 * *** Begin Patch
 * *** Add File: path
 * +content
 * *** Update File: path
 * @@ context
 *  unchanged
 * -removed
 * +added
 * *** Move to: new/path
 * *** Delete File: path
 * *** End Patch
 * ```
 *
 * Every path is resolved against [ToolContext.cwd] and may not escape it. The
 * patch is computed in full before anything is written, so a failed context
 * match leaves the working tree untouched.
 */
class ApplyPatchTool : Tool {
    override val spec = ToolSpec(
        name = "apply_patch",
        description = "Apply a multi-file patch in the opencode patch format. Supports " +
            "adding, updating, moving and deleting files; update hunks match on their " +
            "context lines. Returns a summary and a unified diff.",
        parametersJson = """
            {
              "type": "object",
              "properties": {
                "patchText": {
                  "type": "string",
                  "description": "Patch body starting with *** Begin Patch and ending with *** End Patch"
                }
              },
              "required": ["patchText"],
              "additionalProperties": false
            }
        """.trimIndent(),
    )

    override suspend fun run(input: JsonObject, ctx: ToolContext): ToolOutcome {
        val patchText = input.requireString("patchText")
        val segments = try {
            parse(patchText)
        } catch (e: PatchException) {
            return ToolOutcome(e.message ?: "Invalid patch", isError = true)
        }
        if (segments.isEmpty()) {
            return ToolOutcome("Patch contains no file changes", isError = true)
        }

        val pending = LinkedHashMap<Path, String?>()
        val diff = StringBuilder()
        val summary = ArrayList<String>()
        val specs = ArrayList<EditSpec>()

        for (segment in segments) {
            val rawPath = segment.path
            val path = try {
                resolveInsideCwd(ctx, rawPath)
            } catch (e: IllegalArgumentException) {
                return ToolOutcome(e.message ?: "Invalid path: $rawPath", isError = true)
            }
            val rel = path.displayPath(ctx.cwd)

            when (segment) {
                is AddFile -> {
                    if (currentContent(pending, path) != null) {
                        return ToolOutcome("Cannot add file that already exists: $rawPath", isError = true)
                    }
                    val content = render(segment.lines)
                    pending[path] = content
                    summary.add("A $rel")
                    val segmentDiff = StringBuilder()
                    appendDiff(segmentDiff, "/dev/null", "b/$rel", segment.lines.map { "+$it" })
                    diff.append(segmentDiff)
                    specs.add(
                        EditSpec(
                            editPath = rel,
                            sourcePath = null,
                            created = true,
                            added = segment.lines.size,
                            removed = 0,
                            startLine = if (segment.lines.isEmpty()) null else 1,
                            endLine = if (segment.lines.isEmpty()) null else segment.lines.size,
                            diff = segmentDiff.toString().trimEnd('\n'),
                        ),
                    )
                }

                is DeleteFile -> {
                    val existing = currentContent(pending, path)
                        ?: return ToolOutcome("Cannot delete missing file: $rawPath", isError = true)
                    pending[path] = null
                    summary.add("D $rel")
                    val segmentDiff = StringBuilder()
                    appendDiff(
                        segmentDiff, "a/$rel", "/dev/null",
                        existing.split("\n").map { "-$it" },
                    )
                    diff.append(segmentDiff)
                    specs.add(
                        EditSpec(
                            editPath = rel,
                            sourcePath = path,
                            created = false,
                            added = 0,
                            removed = splitLines(existing).size,
                            startLine = null,
                            endLine = null,
                            diff = segmentDiff.toString().trimEnd('\n'),
                        ),
                    )
                }

                is UpdateFile -> {
                    var content = currentContent(pending, path)
                        ?: return ToolOutcome("Cannot update missing file: $rawPath", isError = true)
                    val hadTrailingNewline = content.endsWith("\n")
                    val lines = content.removeSuffix("\n").split("\n").toMutableList()
                    val diffBody = StringBuilder()
                    var added = 0
                    var removed = 0
                    var startLine: Int? = null
                    var endLine: Int? = null

                    for (hunk in segment.hunks) {
                        val oldBlock = ArrayList<String>()
                        val newBlock = ArrayList<String>()
                        for (raw in hunk.lines) {
                            when {
                                raw.startsWith("\\") -> Unit // "\ No newline at end of file"
                                raw.isEmpty() -> {
                                    oldBlock.add("")
                                    newBlock.add("")
                                }
                                raw.startsWith(" ") -> {
                                    oldBlock.add(raw.substring(1))
                                    newBlock.add(raw.substring(1))
                                }
                                raw.startsWith("-") -> oldBlock.add(raw.substring(1))
                                raw.startsWith("+") -> newBlock.add(raw.substring(1))
                                else -> return ToolOutcome(
                                    "Patch failed: malformed hunk line '$raw'",
                                    isError = true,
                                )
                            }
                        }

                        val index = findBlock(lines, oldBlock)
                        if (index < 0) {
                            val context = hunk.lines.joinToString("\n")
                            return ToolOutcome(
                                "Patch failed: context not found for hunk:\n$context",
                                isError = true,
                            )
                        }
                        repeat(oldBlock.size) { lines.removeAt(index) }
                        lines.addAll(index, newBlock)

                        for (raw in hunk.lines) {
                            when {
                                raw.startsWith("+") -> added++
                                raw.startsWith("-") -> removed++
                                else -> Unit
                            }
                        }
                        parseHunkStart(hunk.header)?.let { (newStart, newCount) ->
                            if (startLine == null) startLine = newStart
                            endLine = if (newCount <= 0) newStart else newStart + newCount - 1
                        }

                        diffBody.append(hunk.header).append('\n')
                        oldBlock.forEach { diffBody.append('-').append(it).append('\n') }
                        newBlock.forEach { diffBody.append('+').append(it).append('\n') }
                    }

                    content = lines.joinToString("\n")
                    if (hadTrailingNewline || lines.isNotEmpty()) content += "\n"

                    val target = segment.moveTo
                    if (target != null) {
                        val targetPath = try {
                            resolveInsideCwd(ctx, target)
                        } catch (e: IllegalArgumentException) {
                            return ToolOutcome(e.message ?: "Invalid path: $target", isError = true)
                        }
                        val targetRel = targetPath.displayPath(ctx.cwd)
                        val targetExisted = currentContent(pending, targetPath) != null
                        pending[path] = null
                        pending[targetPath] = content
                        summary.add("M $rel -> $targetRel")
                        val segmentDiff = StringBuilder()
                        appendDiff(
                            segmentDiff, "a/$rel", "b/$targetRel",
                            diffBody.toString().trimEnd('\n').split("\n"),
                        )
                        diff.append(segmentDiff)
                        specs.add(
                            EditSpec(
                                editPath = targetRel,
                                sourcePath = path,
                                created = !targetExisted,
                                added = added,
                                removed = removed,
                                startLine = startLine,
                                endLine = endLine,
                                diff = segmentDiff.toString().trimEnd('\n'),
                            ),
                        )
                    } else {
                        pending[path] = content
                        summary.add("M $rel")
                        val segmentDiff = StringBuilder()
                        appendDiff(
                            segmentDiff, "a/$rel", "b/$rel",
                            diffBody.toString().trimEnd('\n').split("\n"),
                        )
                        diff.append(segmentDiff)
                        specs.add(
                            EditSpec(
                                editPath = rel,
                                sourcePath = path,
                                created = false,
                                added = added,
                                removed = removed,
                                startLine = startLine,
                                endLine = endLine,
                                diff = segmentDiff.toString().trimEnd('\n'),
                            ),
                        )
                    }
                }
            }
        }

        val snapshotIds = ArrayList<String?>(specs.size)
        for (spec in specs) {
            snapshotIds.add(
                if (spec.created || spec.sourcePath == null) {
                    null
                } else {
                    recordSnapshot(ctx, spec.sourcePath, spec.sourcePath.displayPath(ctx.cwd))
                },
            )
        }

        // Capture the pre-patch state of every path this call may touch so a
        // mid-loop failure can be fully reverted. Context matching happened
        // above, so the tree is still untouched at this point.
        val originals = LinkedHashMap<Path, String>()
        val absent = ArrayList<Path>()
        for (path in pending.keys) {
            val original = if (Files.isRegularFile(path) && !Files.isSymbolicLink(path)) {
                runCatching { Files.readString(path, StandardCharsets.UTF_8) }.getOrNull()
            } else {
                null
            }
            if (original != null) originals[path] = original else absent.add(path)
        }

        try {
            for ((path, content) in pending) {
                if (content == null) {
                    Files.deleteIfExists(path)
                } else {
                    path.parent?.let { Files.createDirectories(it) }
                    writeAtomically(path, content)
                }
            }
        } catch (e: Exception) {
            revert(originals, absent)
            return ToolOutcome(
                "Patch failed while writing: ${e.message}; all changes were reverted",
                isError = true,
            )
        }

        val now = System.currentTimeMillis()
        val toolEdits = specs.mapIndexed { index, spec ->
            ToolEdit(
                edit = FileEdit(
                    id = Ids.new("edit"),
                    sessionId = ctx.sessionId,
                    path = spec.editPath,
                    startLine = spec.startLine,
                    endLine = spec.endLine,
                    added = spec.added,
                    removed = spec.removed,
                    unifiedDiff = spec.diff,
                    created = spec.created,
                    at = now,
                ),
                snapshotId = snapshotIds[index],
                snapshotPath = spec.sourcePath?.displayPath(ctx.cwd),
            )
        }

        val output = buildString {
            append("Applied patch:\n")
            summary.forEach { append("  ").append(it).append('\n') }
        }.trimEnd('\n')

        return ToolOutcome(
            output = output,
            diff = diff.toString().trimEnd('\n'),
            metadata = mapOf("files" to summary.size.toString()),
            edit = if (toolEdits.size == 1) toolEdits.single().edit else null,
            snapshotId = if (toolEdits.size == 1) toolEdits.single().snapshotId else null,
            edits = toolEdits,
        )
    }

    /** Write [content] to a sibling temp file and atomically move it into place. */
    private fun writeAtomically(path: Path, content: String) {
        val parent = path.parent
        val tmp = if (parent != null) {
            Files.createTempFile(parent, ".spindle-patch-", ".tmp")
        } else {
            Files.createTempFile(".spindle-patch-", ".tmp")
        }
        try {
            Files.write(tmp, content.toByteArray(StandardCharsets.UTF_8))
            try {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            runCatching { Files.deleteIfExists(tmp) }
        }
    }

    /** Restore every touched path to its pre-patch state, best effort. */
    private fun revert(originals: Map<Path, String>, absent: List<Path>) {
        for ((path, content) in originals) {
            runCatching {
                path.parent?.let { Files.createDirectories(it) }
                Files.writeString(path, content, StandardCharsets.UTF_8)
            }
        }
        for (path in absent) {
            runCatching { if (!Files.isDirectory(path)) Files.deleteIfExists(path) }
        }
    }

    private fun currentContent(pending: Map<Path, String?>, path: Path): String? {
        if (pending.containsKey(path)) return pending[path]
        if (!Files.isRegularFile(path)) return null
        return Files.readString(path, StandardCharsets.UTF_8)
    }

    private fun render(lines: List<String>): String =
        if (lines.isEmpty()) "" else lines.joinToString("\n") + "\n"

    private fun appendDiff(
        builder: StringBuilder,
        from: String,
        to: String,
        body: List<String>,
    ) {
        builder.append("--- ").append(from).append('\n')
        builder.append("+++ ").append(to).append('\n')
        body.forEach { builder.append(it).append('\n') }
    }

    /** New-file start line and count from an `@@ -a,b +c,d @@` header, when present. */
    private fun parseHunkStart(header: String): Pair<Int, Int>? {
        val match = HUNK_START.find(header) ?: return null
        val start = match.groupValues[1].toIntOrNull() ?: return null
        val count = match.groupValues[2].toIntOrNull() ?: 1
        return start to count
    }

    private fun findBlock(haystack: List<String>, needle: List<String>): Int {
        if (needle.isEmpty()) return haystack.size
        if (needle.size > haystack.size) return -1
        outer@ for (i in 0..(haystack.size - needle.size)) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    private fun parse(patchText: String): List<Segment> {
        val normalized = patchText.replace("\r\n", "\n").replace('\r', '\n')
        val lines = normalized.split("\n")
        var index = lines.indexOfFirst { it.trim() == BEGIN }
        if (index < 0) throw PatchException("Patch must start with '$BEGIN'")
        index++

        val segments = ArrayList<Segment>()
        while (index < lines.size) {
            val line = lines[index]
            val trimmed = line.trim()
            if (trimmed == END) return segments
            if (trimmed.isEmpty()) {
                index++
                continue
            }
            when {
                trimmed.startsWith("$ADD:") -> {
                    val path = trimmed.removePrefix("$ADD:").trim()
                    index++
                    val body = ArrayList<String>()
                    while (index < lines.size && !isSectionHeader(lines[index])) {
                        val raw = lines[index]
                        body.add(if (raw.startsWith("+")) raw.substring(1) else raw)
                        index++
                    }
                    segments.add(AddFile(path, body))
                }

                trimmed.startsWith("$DELETE:") -> {
                    segments.add(DeleteFile(trimmed.removePrefix("$DELETE:").trim()))
                    index++
                }

                trimmed.startsWith("$UPDATE:") -> {
                    val path = trimmed.removePrefix("$UPDATE:").trim()
                    index++
                    var moveTo: String? = null
                    if (index < lines.size && lines[index].trim().startsWith("$MOVE:")) {
                        moveTo = lines[index].trim().removePrefix("$MOVE:").trim()
                        index++
                    }
                    val hunks = ArrayList<Hunk>()
                    var header = ""
                    var body: MutableList<String>? = null
                    while (index < lines.size && !isSectionHeader(lines[index])) {
                        val raw = lines[index]
                        if (raw.startsWith("@@")) {
                            body?.let { hunks.add(Hunk(header, it)) }
                            header = raw
                            body = ArrayList()
                        } else if (body != null) {
                            body.add(raw)
                        } else if (raw.isNotBlank()) {
                            throw PatchException("Unexpected line before hunk: $raw")
                        }
                        index++
                    }
                    body?.let { hunks.add(Hunk(header, it)) }
                    segments.add(UpdateFile(path, moveTo, hunks))
                }

                else -> throw PatchException("Unexpected patch line: $line")
            }
        }
        throw PatchException("Patch is missing '$END'")
    }

    private fun isSectionHeader(line: String): Boolean {
        val trimmed = line.trim()
        return trimmed == END ||
            trimmed.startsWith("$ADD:") ||
            trimmed.startsWith("$DELETE:") ||
            trimmed.startsWith("$UPDATE:")
    }

    private class PatchException(message: String) : Exception(message)

    /** A validated change, resolved into a [ToolEdit] once snapshots are recorded. */
    private class EditSpec(
        val editPath: String,
        val sourcePath: Path?,
        val created: Boolean,
        val added: Int,
        val removed: Int,
        val startLine: Int?,
        val endLine: Int?,
        val diff: String,
    )

    private sealed interface Segment {
        val path: String
    }

    private class AddFile(override val path: String, val lines: List<String>) : Segment

    private class DeleteFile(override val path: String) : Segment

    private class UpdateFile(
        override val path: String,
        val moveTo: String?,
        val hunks: List<Hunk>,
    ) : Segment

    private class Hunk(val header: String, val lines: List<String>)

    private companion object {
        const val BEGIN = "*** Begin Patch"
        const val END = "*** End Patch"
        const val ADD = "*** Add File"
        const val DELETE = "*** Delete File"
        const val UPDATE = "*** Update File"
        const val MOVE = "*** Move to"
        val HUNK_START = Regex("""@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@""")
    }
}
