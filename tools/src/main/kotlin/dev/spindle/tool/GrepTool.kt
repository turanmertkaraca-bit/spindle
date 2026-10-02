package dev.spindle.tool

import dev.spindle.core.provider.ToolSpec
import dev.spindle.core.tool.Tool
import dev.spindle.core.tool.ToolContext
import dev.spindle.core.tool.ToolOutcome
import kotlinx.serialization.json.JsonObject
import java.nio.charset.StandardCharsets
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.PathMatcher
import java.nio.file.attribute.BasicFileAttributes

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
                    // NOFOLLOW + isRegularFile skips symlinks, FIFOs, sockets and devices.
                    val attrs = try {
                        Files.readAttributes(candidate, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                    } catch (e: Exception) {
                        continue
                    }
                    if (!attrs.isRegularFile) continue
                    if (attrs.size() > Limits.GREP_MAX_FILE_BYTES) continue

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

                    try {
                        Files.newBufferedReader(normalized, StandardCharsets.UTF_8).use { reader ->
                            var lineNumber = 0
                            while (true) {
                                val line = reader.readLine() ?: break
                                lineNumber++
                                if (containsMatch(regex, line)) {
                                    results.add("${normalized.displayPath(cwd)}:$lineNumber: ${capLine(line)}")
                                    if (results.size >= Limits.GREP_MAX_RESULTS) {
                                        truncated = true
                                        break
                                    }
                                }
                            }
                        }
                    } catch (e: RegexBudgetExceeded) {
                        throw e
                    } catch (e: Exception) {
                        // Unreadable or non-UTF-8 file: skip it, keep searching.
                    }
                    if (truncated) break
                }
            }
        } catch (e: RegexBudgetExceeded) {
            return ToolOutcome(
                "Grep aborted: regular expression '$patternText' exceeded its backtracking " +
                    "budget (possible catastrophic backtracking). Simplify the pattern.",
                isError = true,
                metadata = mapOf("truncated" to "true"),
            )
        } catch (e: Exception) {
            return ToolOutcome("Grep failed: ${e.message}", isError = true)
        }

        if (results.isEmpty()) return ToolOutcome("No matches for pattern '$patternText'")
        val note = if (truncated) "\n\n…[grep: capped at ${Limits.GREP_MAX_RESULTS} matches]" else ""
        return ToolOutcome(
            output = results.joinToString("\n") + note,
            metadata = mapOf("count" to results.size.toString(), "truncated" to truncated.toString()),
        )
    }

    private fun isSkipped(rel: Path): Boolean =
        rel.any { component ->
            val name = component.toString()
            name == ".git" || name == "build" || name == "node_modules" || name == ".gradle"
        }

    private fun capLine(line: String): String =
        if (line.length <= Limits.GREP_MAX_LINE_CHARS) line
        else line.substring(0, Limits.GREP_MAX_LINE_CHARS) + "…"

    /**
     * Run [regex] against [line] with a hard budget on the number of character
     * accesses. Catastrophic backtracking does exponential `charAt` work, so the
     * budget aborts it quickly while leaving linear/near-linear patterns
     * untouched. This is the pure-JDK equivalent of a regex timeout and cannot
     * hang the tool.
     */
    private fun containsMatch(regex: Regex, line: String): Boolean {
        val budget = Limits.GREP_MATCH_BUDGET_BASE + Limits.GREP_MATCH_BUDGET_PER_CHAR * line.length
        var accesses = 0L
        val bounded = object : CharSequence {
            override val length: Int get() = line.length
            override fun get(index: Int): Char {
                if (++accesses > budget) throw RegexBudgetExceeded()
                return line[index]
            }

            override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
                line.subSequence(startIndex, endIndex)
        }
        return regex.containsMatchIn(bounded)
    }

    private class RegexBudgetExceeded : RuntimeException()
}
