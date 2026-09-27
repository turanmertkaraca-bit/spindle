package dev.spindle.tool

import dev.spindle.core.provider.ToolSpec
import dev.spindle.core.tool.Tool
import dev.spindle.core.tool.ToolContext
import dev.spindle.core.tool.ToolOutcome
import kotlinx.serialization.json.JsonObject
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path

/** Find files by glob pattern anywhere under the working directory. */
class GlobTool : Tool {
    override val spec = ToolSpec(
        name = "glob",
        description = "Find files matching a glob pattern (for example **/*.kt) under the " +
            "working directory. Returns matching paths relative to the working directory.",
        parametersJson = """
            {
              "type": "object",
              "properties": {
                "pattern": {"type": "string", "description": "Glob pattern, e.g. **/*.kt"},
                "path": {"type": "string", "description": "Directory to search, relative to the working directory (defaults to the working directory)"}
              },
              "required": ["pattern"],
              "additionalProperties": false
            }
        """.trimIndent(),
    )

    override suspend fun run(input: JsonObject, ctx: ToolContext): ToolOutcome {
        val pattern = input.requireString("pattern")
        if (pattern.isBlank()) return ToolOutcome("pattern must not be blank", isError = true)
        val cwd = ctx.cwd.toAbsolutePath().normalize()
        val base = try {
            input.stringOrNull("path")?.let { resolveInsideCwd(ctx, it) } ?: cwd
        } catch (e: IllegalArgumentException) {
            return ToolOutcome(e.message ?: "Invalid path", isError = true)
        }

        if (!Files.exists(base)) return ToolOutcome("Directory not found: ${base.displayPath(cwd)}", isError = true)
        if (!Files.isDirectory(base)) return ToolOutcome("Not a directory: ${base.displayPath(cwd)}", isError = true)

        val matcher = try {
            FileSystems.getDefault().getPathMatcher("glob:$pattern")
        } catch (e: Exception) {
            return ToolOutcome("Invalid glob pattern '$pattern': ${e.message}", isError = true)
        }
        // Java's glob matcher does not let `**/` match zero directories, so
        // `**/*.kt` misses top-level files. Add a fallback matcher for that case.
        val recursiveFix = if (pattern.startsWith("**/")) {
            runCatching { FileSystems.getDefault().getPathMatcher("glob:${pattern.removePrefix("**/")}") }
                .getOrNull()
        } else {
            null
        }

        val matches = ArrayList<Path>()
        try {
            Files.walk(base).use { stream ->
                val iterator = stream.iterator()
                while (iterator.hasNext()) {
                    val candidate = iterator.next()
                    if (!Files.isRegularFile(candidate)) continue
                    val normalized = candidate.toAbsolutePath().normalize()
                    if (!normalized.startsWith(cwd)) continue
                    val rel = base.relativize(normalized)
                    if (matcher.matches(rel) || recursiveFix?.matches(rel) == true) {
                        matches.add(normalized)
                    }
                }
            }
        } catch (e: Exception) {
            return ToolOutcome("Glob failed: ${e.message}", isError = true)
        }

        if (matches.isEmpty()) return ToolOutcome("No files matched pattern '$pattern'")

        val sorted = matches.map { it.displayPath(cwd) }.sorted()
        val shown = sorted.take(Limits.GLOB_MAX_RESULTS)
        val note = capNote(shown.size, sorted.size, "glob")
        return ToolOutcome(
            output = shown.joinToString("\n") + note,
            metadata = mapOf("count" to shown.size.toString(), "total" to sorted.size.toString()),
        )
    }
}
