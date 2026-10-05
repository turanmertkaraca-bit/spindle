package dev.spindle.tool

import dev.spindle.core.provider.ToolSpec
import dev.spindle.core.tool.Tool
import dev.spindle.core.tool.ToolContext
import dev.spindle.core.tool.ToolOutcome
import kotlinx.serialization.json.JsonObject
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant
import java.util.stream.Collectors

/**
 * Read-only access to directories the user has explicitly granted, outside the
 * session working directory. Constructed with an allow-list of absolute roots;
 * every request is canonicalized (`normalize()` + `toRealPath()`) and refused
 * unless it lands under one of those roots. It can list, stat and read — never
 * write — so it cannot become a general sandbox escape.
 *
 * With an empty allow-list every request is denied.
 */
class ExternalDirectoryTool(
    grantedRoots: List<Path> = emptyList(),
) : Tool {
    private val roots: List<Path> = grantedRoots.map { it.toAbsolutePath().normalize() }

    override val spec = ToolSpec(
        name = "external-directory",
        description = "Access files under directories the user has explicitly granted outside " +
            "the working directory. Read-only: list a directory, stat a path, or read a text " +
            "file. Paths may be absolute or relative to a granted root. Anything outside the " +
            "granted roots is refused.",
        parametersJson = """
            {
              "type": "object",
              "properties": {
                "op": {
                  "type": "string",
                  "enum": ["list", "stat", "read"],
                  "description": "Operation: list a directory, stat a path, or read a text file"
                },
                "path": {
                  "type": "string",
                  "description": "Absolute path under a granted root, or a path relative to one"
                },
                "offset": {"type": "integer", "description": "For read: 1-based line to start at"},
                "limit": {"type": "integer", "description": "For read: maximum number of lines"}
              },
              "required": ["op", "path"],
              "additionalProperties": false
            }
        """.trimIndent(),
    )

    override suspend fun run(input: JsonObject, ctx: ToolContext): ToolOutcome {
        val op = (input.stringOrNull("op") ?: "").lowercase()
        if (op !in OPS) {
            return ToolOutcome("Unsupported op '$op' (expected list, stat or read)", isError = true)
        }
        if (roots.isEmpty()) {
            return ToolOutcome("No external directories have been granted.", isError = true)
        }
        val raw = input.requireString("path")

        ctx.checkAborted()

        val resolved = when (val result = resolve(raw)) {
            is Resolution.Failure -> return ToolOutcome(result.message, isError = true)
            is Resolution.Success -> result
        }

        if (!ctx.requestPermission("external-directory", "$op ${resolved.path}", resolved.root.toString())) {
            return ToolOutcome("denied by user", isError = true)
        }

        ctx.checkAborted()

        return when (op) {
            "list" -> list(resolved, ctx)
            "stat" -> stat(resolved)
            else -> read(resolved, input)
        }
    }

    private fun list(resolved: Resolved, ctx: ToolContext): ToolOutcome {
        val dir = resolved.path
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            return ToolOutcome("Path not found: ${resolved.raw}", isError = true)
        }
        if (!Files.isDirectory(dir)) {
            return ToolOutcome("Not a directory: ${resolved.raw}", isError = true)
        }

        val entries = try {
            Files.list(dir).use { it.sorted().collect(Collectors.toList()) }
        } catch (e: Exception) {
            return ToolOutcome("Failed to list ${resolved.raw}: ${e.message}", isError = true)
        }

        val shown = entries.take(Limits.EXTERNAL_MAX_ENTRIES)
        val builder = StringBuilder("${dir}:\n")
        for (entry in shown) {
            val attrs = try {
                Files.readAttributes(entry, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            } catch (e: Exception) {
                continue
            }
            val type = when {
                attrs.isSymbolicLink -> "l"
                attrs.isDirectory -> "d"
                attrs.isRegularFile -> "f"
                else -> "?"
            }
            val size = if (attrs.isRegularFile) attrs.size().toString() else "-"
            builder.append(type).append(' ')
                .append(size.padStart(10)).append(' ')
                .append(entry.fileName)
                .append('\n')
        }
        if (entries.size > shown.size) {
            builder.append(capNote(shown.size, entries.size, "external-directory"))
        }

        val output = clip(builder.toString())
        return ToolOutcome(
            output = output,
            metadata = mapOf(
                "path" to dir.toString(),
                "root" to resolved.root.toString(),
                "count" to shown.size.toString(),
                "total" to entries.size.toString(),
                "truncated" to (entries.size > shown.size).toString(),
            ),
        )
    }

    private fun stat(resolved: Resolved): ToolOutcome {
        val path = resolved.path
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return ToolOutcome("Path not found: ${resolved.raw}", isError = true)
        }
        val attrs = try {
            Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (e: Exception) {
            return ToolOutcome("Failed to stat ${resolved.raw}: ${e.message}", isError = true)
        }
        val type = when {
            attrs.isSymbolicLink -> "symlink"
            attrs.isDirectory -> "directory"
            attrs.isRegularFile -> "file"
            else -> "other"
        }
        val output = buildString {
            append("path: ").append(path).append('\n')
            append("root: ").append(resolved.root).append('\n')
            append("type: ").append(type).append('\n')
            append("size: ").append(attrs.size()).append('\n')
            append("modified: ").append(Instant.ofEpochMilli(attrs.lastModifiedTime().toMillis())).append('\n')
        }
        return ToolOutcome(
            output = output,
            metadata = mapOf(
                "path" to path.toString(),
                "root" to resolved.root.toString(),
                "type" to type,
                "size" to attrs.size().toString(),
            ),
        )
    }

    private fun read(resolved: Resolved, input: JsonObject): ToolOutcome {
        val path = resolved.path
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return ToolOutcome("File not found: ${resolved.raw}", isError = true)
        }
        if (Files.isDirectory(path)) {
            return ToolOutcome("Path is a directory, not a file: ${resolved.raw}", isError = true)
        }
        if (!Files.isRegularFile(path)) {
            return ToolOutcome("Path is not a regular file: ${resolved.raw}", isError = true)
        }

        val size = try {
            Files.size(path)
        } catch (e: Exception) {
            return ToolOutcome("Failed to read ${resolved.raw}: ${e.message}", isError = true)
        }
        if (size > Limits.READ_MAX_BYTES) {
            return ToolOutcome(
                "File is too large to read safely: ${resolved.raw} is $size bytes " +
                    "(limit ${Limits.READ_MAX_BYTES}).",
                isError = true,
                metadata = mapOf("path" to path.toString(), "bytes" to size.toString(), "truncated" to "true"),
            )
        }
        if (looksBinary(path)) {
            return ToolOutcome("Refusing to read binary file: ${resolved.raw}", isError = true)
        }

        val offset = (input.intOrNull("offset") ?: 1).coerceAtLeast(1)
        val limit = (input.intOrNull("limit") ?: Limits.READ_MAX_LINES)
            .coerceAtLeast(1)
            .coerceAtMost(Limits.READ_MAX_LINES)

        val window = try {
            readWindow(path, offset, limit)
        } catch (e: Exception) {
            return ToolOutcome("Failed to read ${resolved.raw}: ${e.message}", isError = true)
        }
        if (offset > window.totalLines + 1) {
            return ToolOutcome("Offset $offset is past the end of ${resolved.raw} (${window.totalLines} lines)", isError = true)
        }

        val start = offset - 1
        val end = minOf(window.totalLines, start + limit)
        val builder = StringBuilder("${resolved.raw} (lines ${start + 1}-$end of ${window.totalLines})\n")
        for (i in window.lines.indices) {
            builder.append((start + i + 1).toString().padStart(6)).append('\t')
                .append(window.lines[i]).append('\n')
        }
        val truncated = end < window.totalLines
        if (truncated) {
            builder.append("\n…[truncated: showing lines ${start + 1}-$end of ${window.totalLines}; " +
                "use offset/limit to read more]")
        }
        val output = clip(builder.toString())
        return ToolOutcome(
            output = output,
            metadata = mapOf(
                "path" to path.toString(),
                "root" to resolved.root.toString(),
                "lines" to window.lines.size.toString(),
                "totalLines" to window.totalLines.toString(),
                "truncated" to truncated.toString(),
            ),
        )
    }

    private fun readWindow(path: Path, offset: Int, limit: Int): Window {
        val lines = ArrayList<String>(minOf(limit, 64))
        var total = 0
        Files.newBufferedReader(path, StandardCharsets.UTF_8).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                total++
                if (total >= offset && lines.size < limit) lines.add(line)
                if (total >= Limits.READ_MAX_SCAN_LINES) break
            }
        }
        return Window(lines, total)
    }

    /**
     * Resolve [raw] to a canonical path under one of the granted roots. Relative
     * paths are tried against every root; absolute paths and `..` are normalized
     * and then rejected unless the canonical target (or its deepest existing
     * ancestor) is inside a granted root. This closes both the lexical
     * (`..`/absolute) and the symlink holes.
     */
    private fun resolve(raw: String): Resolution {
        val parsed = try {
            Path.of(raw)
        } catch (e: Exception) {
            return Resolution.Failure("Invalid path: $raw")
        }
        val candidates = if (parsed.isAbsolute) {
            listOf(parsed.normalize())
        } else {
            roots.map { it.resolve(parsed).normalize() }
        }

        var contained: Pair<Path, Path>? = null
        for (candidate in candidates) {
            val match = containedRoot(candidate) ?: continue
            if (contained == null) contained = match
            if (Files.exists(match.first, LinkOption.NOFOLLOW_LINKS)) {
                return Resolution.Success(raw, match.first, match.second)
            }
        }
        return contained?.let { Resolution.Success(raw, it.first, it.second) }
            ?: Resolution.Failure("Path is outside all granted external roots: $raw")
    }

    /** Canonical path plus the granted root it falls under, or null when it escapes. */
    private fun containedRoot(candidate: Path): Pair<Path, Path>? {
        val anchor = if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
            candidate
        } else {
            deepestExistingAncestor(candidate) ?: return null
        }
        val realAnchor = try {
            anchor.toRealPath()
        } catch (e: Exception) {
            return null
        }
        for (root in roots) {
            val realRoot = try {
                root.toRealPath()
            } catch (e: Exception) {
                continue
            }
            if (realAnchor == realRoot || realAnchor.startsWith(realRoot)) return realAnchor to realRoot
        }
        return null
    }

    private fun deepestExistingAncestor(path: Path): Path? {
        var current: Path? = path
        while (current != null && !Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
            current = current.parent
        }
        return current
    }

    private fun clip(text: String): String {
        if (text.length <= Limits.EXTERNAL_MAX_OUTPUT_CHARS) return text
        return text.substring(0, Limits.EXTERNAL_MAX_OUTPUT_CHARS) +
            charCapNote("external-directory", Limits.EXTERNAL_MAX_OUTPUT_CHARS)
    }

    private interface Resolved {
        val raw: String
        val path: Path
        val root: Path
    }

    private sealed interface Resolution {
        data class Success(
            override val raw: String,
            override val path: Path,
            override val root: Path,
        ) : Resolution, Resolved

        data class Failure(val message: String) : Resolution
    }

    private class Window(val lines: List<String>, val totalLines: Int)

    private companion object {
        val OPS = setOf("list", "stat", "read")
    }
}
