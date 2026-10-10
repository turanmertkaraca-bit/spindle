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
import java.util.Locale

/**
 * Discover and load local, file-based "skills" — directories that contain a
 * `SKILL.md`. This is a closed loader: the agent can only enumerate and read
 * skill files under the configured skills root, and that root is itself clamped
 * inside the session working directory. Nothing here reaches the network or any
 * path outside the sandbox.
 *
 * Omitting `name` lists the available skills; providing one loads its
 * `SKILL.md` body (the instructions).
 */
class SkillTool(
    private val skillsRoot: Path? = null,
) : Tool {
    override val spec = ToolSpec(
        name = "skill",
        description = "List or load local skills. A skill is a directory under the skills " +
            "root (default .opencode/skills) containing a SKILL.md whose body holds the " +
            "instructions. Omit name to list available skills; pass a skill id or name to " +
            "load its instructions.",
        parametersJson = """
            {
              "type": "object",
              "properties": {
                "name": {"type": "string", "description": "Skill id or name to load; omit to list all available skills"}
              },
              "additionalProperties": false
            }
        """.trimIndent(),
    )

    override val timeoutMs = 45_000L

    override suspend fun run(input: JsonObject, ctx: ToolContext): ToolOutcome {
        ctx.checkAborted()

        val root = try {
            resolveRoot(ctx)
        } catch (e: IllegalArgumentException) {
            return ToolOutcome(e.message ?: "Invalid skills root", isError = true)
        }

        val skills = try {
            discover(root)
        } catch (e: Exception) {
            return ToolOutcome("Failed to scan skills: ${e.message}", isError = true)
        }

        val query = input.stringOrNull("name")?.trim()
        if (query.isNullOrEmpty()) return listOutcome(root, ctx, skills)

        val matches = match(skills, query)
        if (matches.isEmpty()) {
            val available = skills.map { it.id }.sorted()
            val hint = if (available.isEmpty()) "none found under ${display(root, ctx)}" else available.joinToString(", ")
            return ToolOutcome("Unknown skill: $query. Available: $hint", isError = true)
        }
        if (matches.size > 1) {
            return ToolOutcome(
                "Ambiguous skill '$query' matches: ${matches.joinToString(", ") { it.id }}. " +
                    "Use the full id.",
                isError = true,
            )
        }

        ctx.checkAborted()
        val skill = matches.single()
        val output = clip(skill.body.ifBlank { "(skill \"${skill.id}\" has no instructions)" })
        return ToolOutcome(
            output = output,
            metadata = mapOf(
                "id" to skill.id,
                "name" to skill.name,
                "path" to skill.file.toString(),
                "chars" to output.length.toString(),
            ),
        )
    }

    /** Effective skills root, normalized and verified to live inside [ToolContext.cwd]. */
    private fun resolveRoot(ctx: ToolContext): Path {
        val cwd = ctx.cwd.toAbsolutePath().normalize()
        val raw = skillsRoot?.let { if (it.isAbsolute) it else cwd.resolve(it) } ?: cwd.resolve(DEFAULT_RELATIVE)
        val root = raw.toAbsolutePath().normalize()
        try {
            requireRealInside(cwd, root, root.toString())
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Skills root escapes the working directory: $root")
        }
        return root
    }

    /**
     * Walk the root once. Directories are not followed through symlinks (the
     * default `Files.walk` behavior) and symlinked `SKILL.md` files are skipped,
     * so a link planted inside the root cannot escape it.
     */
    private fun discover(root: Path): List<DiscoveredSkill> {
        if (!Files.isDirectory(root)) return emptyList()
        val found = ArrayList<DiscoveredSkill>()
        Files.walk(root).use { stream ->
            val iterator = stream.iterator()
            while (iterator.hasNext()) {
                if (found.size >= Limits.SKILL_MAX_SKILLS) break
                val candidate = iterator.next()
                if (candidate.fileName?.toString() != SKILL_FILE) continue
                if (!candidate.toAbsolutePath().normalize().startsWith(root)) continue
                if (Files.isSymbolicLink(candidate) || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
                    continue
                }
                val loaded = try {
                    readSkill(candidate, root)
                } catch (e: Exception) {
                    null
                } ?: continue
                found.add(loaded)
            }
        }
        return found.sortedBy { it.id }
    }

    private fun readSkill(file: Path, root: Path): DiscoveredSkill? {
        val size = Files.size(file)
        if (size > Limits.SKILL_MAX_BYTES) return null
        val content = Files.readString(file, StandardCharsets.UTF_8)
        if (content.isEmpty()) return null
        val parsed = parseFrontmatter(content)
        val parent = file.parent ?: root
        val id = root.relativize(parent).toString().replace('\\', '/').ifEmpty {
            root.fileName?.toString() ?: SKILL_FILE
        }
        val name = parsed["name"]?.takeIf { it.isNotBlank() } ?: id
        return DiscoveredSkill(
            id = id,
            name = name,
            description = parsed["description"]?.takeIf { it.isNotBlank() },
            body = stripFrontmatter(content).trim(),
            file = file,
        )
    }

    private fun listOutcome(root: Path, ctx: ToolContext, skills: List<DiscoveredSkill>): ToolOutcome {
        if (skills.isEmpty()) {
            return ToolOutcome(
                output = "No skills found under ${display(root, ctx)}.",
                metadata = mapOf("root" to root.toString(), "count" to "0"),
            )
        }
        val builder = StringBuilder()
        builder.append("Available skills (").append(skills.size).append(") under ")
            .append(display(root, ctx)).append(":\n")
        for (skill in skills) {
            builder.append("- ").append(skill.name)
            skill.description?.let { builder.append(": ").append(it) }
            if (skill.name != skill.id) builder.append(" [id: ").append(skill.id).append(']')
            builder.append('\n')
        }
        val output = clip(builder.toString())
        return ToolOutcome(
            output = output,
            metadata = mapOf(
                "root" to root.toString(),
                "count" to skills.size.toString(),
                "truncated" to (output.length < builder.length).toString(),
            ),
        )
    }

    /** Prefer an exact (case-sensitive) id/name match, then a case-insensitive one. */
    private fun match(skills: List<DiscoveredSkill>, query: String): List<DiscoveredSkill> {
        val exact = skills.filter { it.id == query || it.name == query }
        if (exact.isNotEmpty()) return exact
        val folded = query.lowercase(Locale.ROOT)
        return skills.filter { it.id.lowercase(Locale.ROOT) == folded || it.name.lowercase(Locale.ROOT) == folded }
    }

    private fun display(root: Path, ctx: ToolContext): String {
        val rel = root.displayPath(ctx.cwd)
        return if (rel.isEmpty()) "." else rel
    }

    private fun clip(text: String): String {
        if (text.length <= Limits.SKILL_MAX_OUTPUT_CHARS) return text
        return text.substring(0, Limits.SKILL_MAX_OUTPUT_CHARS) +
            charCapNote("skill", Limits.SKILL_MAX_OUTPUT_CHARS)
    }

    private data class DiscoveredSkill(
        val id: String,
        val name: String,
        val description: String?,
        val body: String,
        val file: Path,
    )

    private companion object {
        const val DEFAULT_RELATIVE = ".opencode/skills"
        const val SKILL_FILE = "SKILL.md"
    }
}

/**
 * Strip a leading YAML frontmatter block and return the body. A document without
 * frontmatter is returned unchanged.
 */
internal fun stripFrontmatter(content: String): String {
    val lines = content.replace("\r\n", "\n").split("\n")
    if (lines.isEmpty() || lines[0].trim() != "---") return content
    val end = (1 until lines.size).firstOrNull { lines[it].trim() == "---" } ?: return content
    return lines.subList(end + 1, lines.size).joinToString("\n")
}

/**
 * Minimal, dependency-free frontmatter reader: top-level `key: value` pairs
 * between the first two `---` fences. Unknown or malformed lines are ignored;
 * quoted values are unquoted. This is deliberately not a YAML parser.
 */
internal fun parseFrontmatter(content: String): Map<String, String> {
    val lines = content.replace("\r\n", "\n").split("\n")
    if (lines.isEmpty() || lines[0].trim() != "---") return emptyMap()
    val end = (1 until lines.size).firstOrNull { lines[it].trim() == "---" } ?: return emptyMap()
    val result = LinkedHashMap<String, String>()
    for (i in 1 until end) {
        val line = lines[i].trim()
        if (line.isEmpty() || line.startsWith("#")) continue
        val colon = line.indexOf(':')
        if (colon <= 0) continue
        val key = line.substring(0, colon).trim()
        val value = line.substring(colon + 1).trim().trim('"', '\'')
        if (key.isNotEmpty()) result[key] = value
    }
    return result
}
