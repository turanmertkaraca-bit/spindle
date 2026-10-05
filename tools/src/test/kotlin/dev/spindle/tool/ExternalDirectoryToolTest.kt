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

private suspend fun <T> withTempDir(block: suspend (Path) -> T): T {
    val dir = Files.createTempDirectory("spindle-external-test")
    try {
        return block(dir)
    } finally {
        dir.toFile().deleteRecursively()
    }
}

class ExternalDirectoryToolTest {

    @Test
    fun emptyAllowListDeniesEverything() = runTest {
        withTempDir { cwd ->
            val ctx = ToolTestContext(cwd)
            val outcome = ExternalDirectoryTool().run(obj("""{"op":"list","path":"/"}"""), ctx)
            assertTrue(outcome.isError, outcome.output)
            assertTrue(outcome.output.contains("No external directories", ignoreCase = true), outcome.output)
            assertEquals(0, ctx.permissionRequests)
        }
    }

    @Test
    fun listsAndReadsUnderGrantedRoot() = runTest {
        withTempDir { granted ->
            withTempDir { cwd ->
                Files.writeString(granted.resolve("hello.txt"), "line one\nline two\n")
                Files.createDirectories(granted.resolve("sub"))
                val ctx = ToolTestContext(cwd)
                val tool = ExternalDirectoryTool(listOf(granted))

                val listed = tool.run(obj("""{"op":"list","path":"${granted}"}"""), ctx)
                assertFalse(listed.isError, listed.output)
                assertTrue(listed.output.contains("hello.txt"), listed.output)
                assertTrue(listed.output.contains("sub"), listed.output)

                val read = tool.run(obj("""{"op":"read","path":"${granted.resolve("hello.txt")}"}"""), ctx)
                assertFalse(read.isError, read.output)
                assertTrue(read.output.contains("line one"), read.output)
                assertTrue(read.output.contains("line two"), read.output)
                assertEquals("external-directory", ctx.lastPermissionTool)
            }
        }
    }

    @Test
    fun relativePathResolvesUnderRootAndStatWorks() = runTest {
        withTempDir { granted ->
            withTempDir { cwd ->
                Files.createDirectories(granted.resolve("sub"))
                Files.writeString(granted.resolve("sub/data.txt"), "payload")
                val ctx = ToolTestContext(cwd)
                val tool = ExternalDirectoryTool(listOf(granted))

                val read = tool.run(obj("""{"op":"read","path":"sub/data.txt"}"""), ctx)
                assertFalse(read.isError, read.output)
                assertTrue(read.output.contains("payload"), read.output)

                val stat = tool.run(obj("""{"op":"stat","path":"sub/data.txt"}"""), ctx)
                assertFalse(stat.isError, stat.output)
                assertEquals("file", stat.metadata["type"])
                assertEquals("7", stat.metadata["size"])
                assertTrue(stat.output.contains("modified:"), stat.output)
            }
        }
    }

    @Test
    fun deniesAbsolutePathOutsideGrantedRoots() = runTest {
        withTempDir { granted ->
            withTempDir { cwd ->
                val outside = Files.createTempFile("spindle-outside", ".txt")
                try {
                    Files.writeString(outside, "TOP SECRET")
                    val ctx = ToolTestContext(cwd)
                    val tool = ExternalDirectoryTool(listOf(granted))

                    val read = tool.run(obj("""{"op":"read","path":"$outside"}"""), ctx)
                    assertTrue(read.isError, read.output)
                    assertTrue(read.output.contains("outside", ignoreCase = true), read.output)
                    assertFalse(read.output.contains("TOP SECRET"), read.output)
                    assertEquals(0, ctx.permissionRequests, "rejected paths must not ask for permission")
                } finally {
                    Files.deleteIfExists(outside)
                }
            }
        }
    }

    @Test
    fun dotDotEscapeIsRejected() = runTest {
        withTempDir { granted ->
            withTempDir { cwd ->
                val sibling = granted.parent.resolve("spindle-sibling-secret.txt")
                Files.writeString(sibling, "TOP SECRET")
                try {
                    val ctx = ToolTestContext(cwd)
                    val tool = ExternalDirectoryTool(listOf(granted))

                    val outcome = tool.run(
                        obj("""{"op":"read","path":"${granted}/../spindle-sibling-secret.txt"}"""),
                        ctx,
                    )
                    assertTrue(outcome.isError, outcome.output)
                    assertFalse(outcome.output.contains("TOP SECRET"), outcome.output)
                } finally {
                    Files.deleteIfExists(sibling)
                }
            }
        }
    }

    @Test
    fun symlinkEscapeIsRejected() = runTest {
        withTempDir { granted ->
            withTempDir { cwd ->
                val outside = Files.createTempFile("spindle-outside-target", ".txt")
                try {
                    Files.writeString(outside, "TOP SECRET")
                    val link = granted.resolve("escape.txt")
                    try {
                        Files.createSymbolicLink(link, outside)
                    } catch (_: Exception) {
                        return@withTempDir
                    }
                    val ctx = ToolTestContext(cwd)
                    val tool = ExternalDirectoryTool(listOf(granted))

                    val read = tool.run(obj("""{"op":"read","path":"$link"}"""), ctx)
                    assertTrue(read.isError, read.output)
                    assertTrue(read.output.contains("outside", ignoreCase = true), read.output)
                    assertFalse(read.output.contains("TOP SECRET"), read.output)
                } finally {
                    Files.deleteIfExists(outside)
                }
            }
        }
    }

    @Test
    fun permissionDenialBlocksAccess() = runTest {
        withTempDir { granted ->
            withTempDir { cwd ->
                Files.writeString(granted.resolve("hello.txt"), "hi")
                val ctx = ToolTestContext(cwd)
                ctx.permissionAllowed = false
                val tool = ExternalDirectoryTool(listOf(granted))

                val outcome = tool.run(obj("""{"op":"read","path":"${granted.resolve("hello.txt")}"}"""), ctx)
                assertTrue(outcome.isError, outcome.output)
                assertTrue(outcome.output.contains("denied", ignoreCase = true), outcome.output)
                assertTrue(ctx.permissionRequests >= 1)
            }
        }
    }

    @Test
    fun unsupportedOpIsRejected() = runTest {
        withTempDir { granted ->
            withTempDir { cwd ->
                val ctx = ToolTestContext(cwd)
                val tool = ExternalDirectoryTool(listOf(granted))
                val outcome = tool.run(obj("""{"op":"write","path":"$granted"}"""), ctx)
                assertTrue(outcome.isError, outcome.output)
                assertTrue(outcome.output.contains("Unsupported op"), outcome.output)
                assertEquals(0, ctx.permissionRequests)
            }
        }
    }
}
