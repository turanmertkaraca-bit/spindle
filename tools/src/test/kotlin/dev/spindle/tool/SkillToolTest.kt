package dev.spindle.tool

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val json = Json { ignoreUnknownKeys = true; isLenient = true }

private fun obj(text: String): JsonObject = json.parseToJsonElement(text) as JsonObject

private fun writeSkill(root: Path, id: String, name: String, description: String, body: String): Path {
    val dir = root.resolve(id)
    Files.createDirectories(dir)
    val file = dir.resolve("SKILL.md")
    Files.writeString(
        file,
        "---\nname: $name\ndescription: $description\n---\n$body",
    )
    return file
}

private suspend fun <T> withTempDir(block: suspend (Path) -> T): T {
    val dir = Files.createTempDirectory("spindle-skill-test")
    try {
        return block(dir)
    } finally {
        dir.toFile().deleteRecursively()
    }
}

class SkillToolTest {

    @Test
    fun listsSkillsWhenNameOmitted() = runTest {
        withTempDir { cwd ->
            val root = cwd.resolve(".opencode/skills")
            writeSkill(root, "alpha", "alpha", "First skill", "# Alpha\nDo alpha.")
            writeSkill(root, "nested/beta", "beta", "Second skill", "# Beta\nDo beta.")
            val ctx = ToolTestContext(cwd)

            val outcome = SkillTool().run(obj("{}"), ctx)
            assertFalse(outcome.isError, outcome.output)
            assertTrue(outcome.output.contains("alpha"), outcome.output)
            assertTrue(outcome.output.contains("First skill"), outcome.output)
            assertTrue(outcome.output.contains("beta"), outcome.output)
            assertTrue(outcome.output.contains("Second skill"), outcome.output)
            assertEquals("2", outcome.metadata["count"])
        }
    }

    @Test
    fun loadsBodyByIdOrFrontmatterName() = runTest {
        withTempDir { cwd ->
            val root = cwd.resolve(".opencode/skills")
            writeSkill(root, "commit-helper", "commit", "Writes commit messages", "# Commit\nUse conventional commits.")
            val ctx = ToolTestContext(cwd)

            val byId = SkillTool().run(obj("""{"name":"commit-helper"}"""), ctx)
            assertFalse(byId.isError, byId.output)
            assertTrue(byId.output.contains("Use conventional commits."), byId.output)
            assertFalse(byId.output.contains("description:"), byId.output)
            assertFalse(byId.output.contains("Writes commit messages"), byId.output)
            assertEquals("commit-helper", byId.metadata["id"])

            val byName = SkillTool().run(obj("""{"name":"commit"}"""), ctx)
            assertFalse(byName.isError, byName.output)
            assertTrue(byName.output.contains("Use conventional commits."), byName.output)
        }
    }

    @Test
    fun unknownSkillIsError() = runTest {
        withTempDir { cwd ->
            val root = cwd.resolve(".opencode/skills")
            writeSkill(root, "alpha", "alpha", "First skill", "# Alpha\nDo alpha.")
            val ctx = ToolTestContext(cwd)

            val outcome = SkillTool().run(obj("""{"name":"does-not-exist"}"""), ctx)
            assertTrue(outcome.isError, outcome.output)
            assertTrue(outcome.output.contains("Unknown skill"), outcome.output)
        }
    }

    @Test
    fun traversalNameCannotReadOutsideTheRoot() = runTest {
        withTempDir { cwd ->
            val outside = Files.createTempDirectory("spindle-skill-outside")
            try {
                Files.writeString(outside.resolve("secret.md"), "TOP SECRET")
                val ctx = ToolTestContext(cwd)

                for (name in listOf("../secret", "../../etc/passwd", "/etc/passwd", "beta/../../secret")) {
                    val outcome = SkillTool().run(obj("""{"name":"$name"}"""), ctx)
                    assertTrue(outcome.isError, "expected rejection for '$name': ${outcome.output}")
                    assertTrue(
                        outcome.output.contains("Unknown skill"),
                        "unexpected output for '$name': ${outcome.output}",
                    )
                    assertFalse(outcome.output.contains("TOP SECRET"), outcome.output)
                }
            } finally {
                outside.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun configuredRootOutsideCwdIsRejected() = runTest {
        withTempDir { cwd ->
            val outside = Files.createTempDirectory("spindle-skill-outside")
            try {
                writeSkill(outside, "alpha", "alpha", "Outside skill", "# Alpha\nOutside.")
                val ctx = ToolTestContext(cwd)

                val outcome = SkillTool(skillsRoot = outside).run(obj("{}"), ctx)
                assertTrue(outcome.isError, outcome.output)
                assertTrue(outcome.output.contains("escapes", ignoreCase = true), outcome.output)
                assertFalse(outcome.output.contains("Outside."), outcome.output)
            } finally {
                outside.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun symlinkedSkillFileIsSkipped() = runTest {
        withTempDir { cwd ->
            val outside = Files.createTempDirectory("spindle-skill-outside")
            try {
                val secret = outside.resolve("secret.md")
                Files.writeString(secret, "TOP SECRET")
                val root = cwd.resolve(".opencode/skills/evil")
                Files.createDirectories(root)
                try {
                    Files.createSymbolicLink(root.resolve("SKILL.md"), secret)
                } catch (_: Exception) {
                    return@withTempDir
                }
                val ctx = ToolTestContext(cwd)

                val outcome = SkillTool().run(obj("{}"), ctx)
                assertFalse(outcome.isError, outcome.output)
                assertTrue(outcome.output.contains("No skills found"), outcome.output)
                assertFalse(outcome.output.contains("TOP SECRET"), outcome.output)
            } finally {
                outside.toFile().deleteRecursively()
            }
        }
    }
}
