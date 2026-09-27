package dev.spindle.tool

import dev.spindle.core.provider.ToolSpec
import dev.spindle.core.tool.Tool
import dev.spindle.core.tool.ToolContext
import dev.spindle.core.tool.ToolOutcome
import kotlinx.serialization.json.JsonObject
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.PathMatcher

/** Regex content search across files under the working directory. */
class GrepTool : Tool {
    override val spec = ToolSpec(
        name = "grep",
        description = "Search file contents with a regular expression under the working " +
            "directory. Returns matching lines as path:line: text. Skips .git and build " +
            "directories and binary files.",
        parametersJson = """
            {
              "type": "object",
              "properties": {
                "pattern": {"type": "string", "description": "Regular expression to search for"},
                "include": {"type": "string", "description": "Only search files matching this glob, e.g. *.kt"},
                "path": {"type": "string", "description": "Directory to search, relative to the working directory (defaults to the working directory)"}
              },
              "required": ["pattern"],
              "additionalProperties": false
            }
        """.trimIndent(),
    )

    override suspend fun run(input: JsonObject, ctx: ToolContext): ToolOutcome {
        val patternText = input.requireString("pattern")
        val include = input.stringOrNull("include")
        val cwd = ctx.cwd.toAbsolutePath().normalize()
        val base = try {
            input.stringOrNull("path")?.let { resolveInsideCwd(ctx, it) } ?: cwd
        } catch (e: IllegalArgumentException) {
            return ToolOutcome(e.message ?: "Invalid path", isError = true)
        }

        val regex = try {
            Regex(patternText)
        } catch (e: Exception) {
            return ToolOutcome("Invalid regular expression '$patternText': ${e.message}", isError = true)
        }
        if (!Files.exists(base)) return ToolOutcome("Directory not found: ${base.displayPath(cwd)}", isError = true)
        if (!Files.isDirectory(base)) return ToolOutcome("Not a directory: ${base.displayPath(cwd)}", isError = true)

        val includeMatcher: PathMatcher? = include?.let {
            try {
                FileSystems.getDefault().getPathMatcher("glob:$it")
            } catch (e: Exception) {
                return ToolOutcome("Invalid include glob '$it': ${e.message}", isError = true)
            }
        }

        val results = ArrayList<String>()
        var truncated = false
        try {
            Files.walk(base).use { stream ->
                val iterator = stream.iterator()
                while (iterator.hasNext()) {
                    val candidate = iterator.next()
                    if (!Files.isRegularFile(candidate)) continue
                    val normalized = candidate.toAbsolutePath().normalize()
                    if (!normalized.startsWith(cwd)) continue

                    val rel = cwd.relativize(normalized)
                    if (isSkipped(rel)) continue
                    if (includeMatcher != null &&
                        !includeMatcher.matches(rel) &&
                        !includeMatcher.matches(normalized.fileName)
                    ) {
                        continue
                    }
                    if (looksBinary(normalized)) continue

                    val lines = try {
                        Files.readAllLines(normalized)
                    } catch (e: Exception) {
                        continue
                    }
                    for ((index, line) in lines.withIndex()) {
                        if (regex.containsMatchIn(line)) {
                            results.add("${normalized.displayPath(cwd)}:${index + 1}: $line")
                            if (results.size >= Limits.GREP_MAX_RESULTS) {
                                truncated = true
                                break
                            }
                        }
                    }
                    if (truncated) break
                }
            }
        } catch (e: Exception) {
            return ToolOutcome("Grep failed: ${e.message}", isError = true)
        }

        if (results.isEmpty()) return ToolOutcome("No matches for pattern '$patternText'")
        val note = if (truncated) "\n\n…[grep: capped at ${Limits.GREP_MAX_RESULTS} matches]" else ""
        return ToolOutcome(
            output = results.joinToString("\n") + note,
            metadata = mapOf("count" to results.size.toString()),
        )
    }

    private fun isSkipped(rel: Path): Boolean =
        rel.any { component ->
            val name = component.toString()
            name == ".git" || name == "build" || name == "node_modules" || name == ".gradle"
        }

    private fun looksBinary(path: Path): Boolean {
        val name = path.fileName.toString().lowercase()
        if (BINARY_EXTENSIONS.any { name.endsWith(it) }) return true
        return try {
            val bytes = Files.newInputStream(path).use { it.readNBytes(Limits.BINARY_SNIFF_BYTES) }
            bytes.any { it == 0.toByte() }
        } catch (e: Exception) {
            true
        }
    }

    private companion object {
        val BINARY_EXTENSIONS = listOf(
            ".png", ".jpg", ".jpeg", ".gif", ".webp", ".ico", ".pdf", ".zip", ".gz", ".tar",
            ".jar", ".class", ".so", ".dylib", ".dll", ".exe", ".bin", ".woff", ".woff2",
            ".ttf", ".otf", ".mp3", ".mp4", ".mov", ".avi", ".wasm",
        )
    }
}
