package dev.spindle.tool

import dev.spindle.core.model.SessionId
import dev.spindle.core.tool.ToolContext
import dev.spindle.core.tool.ToolProgress
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private val json = Json { ignoreUnknownKeys = true; isLenient = true }

private fun obj(text: String): JsonObject = json.parseToJsonElement(text) as JsonObject

/** Minimal ToolContext backed by a temp directory with a scripted answer. */
private class FakeToolContext(
    override val cwd: Path,
    private val scriptedAnswer: List<String> = emptyList(),
) : ToolContext {
    override val sessionId = SessionId("test-session")
    var lastQuestion: String? = null
    var lastOptions: List<String> = emptyList()
    var lastMultiple: Boolean = false
    var permissionRequests: Int = 0

    override suspend fun requestPermission(tool: String, detail: String, pattern: String?): Boolean {
        permissionRequests++
        return true
    }

    override suspend fun ask(question: String, options: List<String>, multiple: Boolean): List<String> {
        lastQuestion = question
        lastOptions = options
        lastMultiple = multiple
        return scriptedAnswer
    }

    override fun emit(event: ToolProgress) {}
    override fun checkAborted() {}
}

private suspend fun <T> withTempDir(block: suspend (Path) -> T): T {
    val dir = Files.createTempDirectory("spindle-tools-test")
    try {
        return block(dir)
    } finally {
        dir.toFile().deleteRecursively()
    }
}

class ToolsTest {

    @Test
    fun readAfterWriteRoundTrip() = runTest {
        withTempDir { dir ->
            val ctx = FakeToolContext(dir)
            val write = WriteTool().run(obj("""{"path":"notes/hello.txt","content":"hello\nworld\n"}"""), ctx)
            assertFalse(write.isError)
            assertTrue(write.output.contains("bytes"))
            assertTrue(Files.exists(dir.resolve("notes/hello.txt")))

            val read = ReadTool().run(obj("""{"path":"notes/hello.txt"}"""), ctx)
            assertFalse(read.isError)
            assertTrue(read.output.contains("hello"))
            assertTrue(read.output.contains("world"))
            assertTrue(read.output.contains("1\t"), "expected numbered lines, got: ${read.output}")
        }
    }

    @Test
    fun editAppliesAndReportsDiff() = runTest {
        withTempDir { dir ->
            val ctx = FakeToolContext(dir)
            WriteTool().run(obj("""{"path":"a.txt","content":"one\ntwo\nthree\n"}"""), ctx)

            val outcome = EditTool().run(obj("""{"path":"a.txt","oldString":"two","newString":"TWO"}"""), ctx)
            assertFalse(outcome.isError)
            assertNotNull(outcome.diff)
            assertTrue(outcome.diff!!.contains("-two"), outcome.diff!!)
            assertTrue(outcome.diff!!.contains("+TWO"), outcome.diff!!)
            assertEquals("one\nTWO\nthree\n", Files.readString(dir.resolve("a.txt")))
        }
    }

    @Test
    fun editMissingOldStringIsError() = runTest {
        withTempDir { dir ->
            val ctx = FakeToolContext(dir)
            WriteTool().run(obj("""{"path":"a.txt","content":"hello"}"""), ctx)

            val outcome = EditTool().run(obj("""{"path":"a.txt","oldString":"nope","newString":"x"}"""), ctx)
            assertTrue(outcome.isError)
            assertTrue(outcome.output.contains("not found", ignoreCase = true), outcome.output)
            assertEquals("hello", Files.readString(dir.resolve("a.txt")))
        }
    }

    @Test
    fun globFindsNestedFiles() = runTest {
        withTempDir { dir ->
            val ctx = FakeToolContext(dir)
            Files.createDirectories(dir.resolve("src/deep"))
            Files.writeString(dir.resolve("src/deep/Thing.kt"), "class Thing")
            Files.writeString(dir.resolve("src/Main.kt"), "fun main() {}")
            Files.writeString(dir.resolve("readme.md"), "# hi")

            val outcome = GlobTool().run(obj("""{"pattern":"**/*.kt"}"""), ctx)
            assertFalse(outcome.isError)
            assertTrue(outcome.output.contains("src/deep/Thing.kt"), outcome.output)
            assertTrue(outcome.output.contains("src/Main.kt"), outcome.output)
            assertFalse(outcome.output.contains("readme.md"), outcome.output)
        }
    }

    @Test
    fun grepMatchesWithIncludeFilter() = runTest {
        withTempDir { dir ->
            val ctx = FakeToolContext(dir)
            Files.writeString(dir.resolve("a.kt"), "val x = 1\nval needle = 2\n")
            Files.writeString(dir.resolve("b.txt"), "needle in text\n")

            val outcome = GrepTool().run(obj("""{"pattern":"needle","include":"*.kt"}"""), ctx)
            assertFalse(outcome.isError)
            assertTrue(outcome.output.contains("a.kt:2:"), outcome.output)
            assertFalse(outcome.output.contains("b.txt"), outcome.output)
        }
    }

    @Test
    fun pathEscapeIsRejected() = runTest {
        withTempDir { dir ->
            val ctx = FakeToolContext(dir)
            val read = ReadTool().run(obj("""{"path":"../secret.txt"}"""), ctx)
            assertTrue(read.isError, read.output)
            val write = WriteTool().run(obj("""{"path":"../secret.txt","content":"x"}"""), ctx)
            assertTrue(write.isError, write.output)
            assertFalse(Files.exists(dir.parent.resolve("secret.txt")))
        }
    }

    @Test
    fun questionReturnsScriptedAnswer() = runTest {
        withTempDir { dir ->
            val ctx = FakeToolContext(dir, scriptedAnswer = listOf("yes", "maybe"))
            val outcome = QuestionTool().run(
                obj("""{"question":"Proceed?","options":["yes","no","maybe"],"multiple":true}"""),
                ctx,
            )
            assertFalse(outcome.isError)
            assertEquals("yes, maybe", outcome.output)
            assertEquals("Proceed?", ctx.lastQuestion)
            assertEquals(listOf("yes", "no", "maybe"), ctx.lastOptions)
            assertTrue(ctx.lastMultiple)
        }
    }

    @Test
    fun questionWithoutOptionsIsFreeForm() = runTest {
        withTempDir { dir ->
            val ctx = FakeToolContext(dir, scriptedAnswer = listOf("typed answer"))
            val outcome = QuestionTool().run(obj("""{"question":"Name?"}"""), ctx)
            assertFalse(outcome.isError)
            assertEquals("typed answer", outcome.output)
            assertTrue(ctx.lastOptions.isEmpty())
            assertFalse(ctx.lastMultiple)
        }
    }

    @Test
    fun todoWriteRendersChecklist() = runTest {
        withTempDir { dir ->
            val ctx = FakeToolContext(dir)
            val outcome = TodoWriteTool().run(
                obj(
                    """
                    {"todos":[
                      {"content":"write tests","status":"done"},
                      {"content":"implement tools","status":"in_progress"},
                      {"content":"ship it","status":"pending"}
                    ]}
                    """.trimIndent(),
                ),
                ctx,
            )
            assertFalse(outcome.isError)
            assertTrue(outcome.output.contains("- [x] write tests"), outcome.output)
            assertTrue(outcome.output.contains("- [~] implement tools"), outcome.output)
            assertTrue(outcome.output.contains("- [ ] ship it"), outcome.output)
        }
    }

    @Test
    fun defaultRegistryHasAllSevenToolsWithSchemas() {
        val registry = DefaultTools.registry()
        val specs = registry.specs
        val names = specs.map { it.name }.toSet()
        assertEquals(
            setOf("read", "write", "edit", "glob", "grep", "todowrite", "question"),
            names,
        )
        assertEquals(7, specs.size)
        for (spec in specs) {
            assertTrue(spec.description.isNotBlank(), "blank description for ${spec.name}")
            assertTrue(spec.parametersJson.isNotBlank(), "blank schema for ${spec.name}")
            val parsed = json.parseToJsonElement(spec.parametersJson)
            assertTrue(parsed is JsonObject, "schema for ${spec.name} is not a JSON object")
            assertEquals(
                "object",
                (parsed as JsonObject)["type"]?.toString()?.trim('"'),
                "schema type for ${spec.name}",
            )
        }
    }
}
