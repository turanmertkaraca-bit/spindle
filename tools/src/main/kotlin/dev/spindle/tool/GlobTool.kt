package dev.spindle.tool

import dev.spindle.core.provider.ToolSpec
import dev.spindle.core.tool.Tool
import dev.spindle.core.tool.ToolContext
import dev.spindle.core.tool.ToolOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption
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

    override val timeoutMs = 45_000L

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

        ctx.checkAborted()

        // Breadth-first walk with an explicit depth cap and directory pruning.
        // The result cap is enforced *during* the walk: once [Limits.GLOB_MAX_RESULTS]
        // matches are found, traversal stops instead of collecting the whole tree.
        val matches = ArrayList<Path>()
        var visited = 0
        var capped = false
        val queue = ArrayDeque<Pair<Path, Int>>()
        queue.add(base.toAbsolutePath().normalize() to 0)
        try {
            while (queue.isNotEmpty()) {
                val (dir, depth) = queue.removeFirst()
                val entries = try {
                    Files.newDirectoryStream(dir)
                } catch (e: Exception) {
                    continue
                }
                var stop = false
                entries.use { stream ->
                    for (entry in stream) {
                        if (++visited % ABORT_CHECK_INTERVAL == 0) ctx.checkAborted()
                        if (Files.isSymbolicLink(entry)) continue
                        val normalized = entry.toAbsolutePath().normalize()
                        if (!normalized.startsWith(cwd)) continue
                        if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                            val name = entry.fileName?.toString() ?: continue
                            if (name in HEAVY_DIRS) continue
                            if (depth + 1 <= Limits.GLOB_MAX_DEPTH) queue.add(normalized to (depth + 1))
                            continue
                        }
                        if (!Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) continue
                        if (isSkipped(cwd.relativize(normalized))) continue
                        val rel = base.relativize(normalized)
                        if (matcher.matches(rel) || recursiveFix?.matches(rel) == true) {
                            matches.add(normalized)
                            if (matches.size >= Limits.GLOB_MAX_RESULTS) {
                                capped = true
                                stop = true
                                break
                            }
                        }
                    }
                }
                if (stop) break
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return ToolOutcome("Glob failed: ${e.message}", isError = true)
        }

        if (matches.isEmpty()) return ToolOutcome("No files matched pattern '$pattern'")

        val sorted = matches.map { it.displayPath(cwd) }.sorted()
        val shown = sorted.take(Limits.GLOB_MAX_RESULTS)
        val truncated = capped || sorted.size > shown.size
        val note = if (truncated) "\n\n…[glob: capped at ${Limits.GLOB_MAX_RESULTS} results]" else ""
        return ToolOutcome(
            output = shown.joinToString("\n") + note,
            metadata = mapOf(
                "count" to shown.size.toString(),
                "total" to sorted.size.toString(),
                "truncated" to truncated.toString(),
            ),
        )
    }

    /** Heavy directory names that are never descended, mirroring [GrepTool]. */
    private fun isSkipped(rel: Path): Boolean =
        rel.any { component ->
            val name = component.toString()
            name in HEAVY_DIRS
        }

    private companion object {
        val HEAVY_DIRS = setOf(".git", "build", "node_modules", ".gradle")
    }
}
