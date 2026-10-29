package dev.spindle.tool

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val readWindowJson = Json { ignoreUnknownKeys = true; isLenient = true }

private fun readWindowObj(text: String): JsonObject = readWindowJson.parseToJsonElement(text) as JsonObject

private suspend fun <T> withReadWindowDir(block: suspend (Path) -> T): T {
    val dir = Files.createTempDirectory("spindle-readtool-window-test")
    try {
        return block(dir)
    } finally {
        dir.toFile().deleteRecursively()
    }
}

/**
 * One-char line bodies keep the fixture under `Limits.READ_MAX_BYTES` (2 MB)
 * while still exceeding `Limits.READ_MAX_SCAN_LINES` (1_000_000).
 */
private fun oneCharLine(index: Int): String = ('a' + (index % 26)).toString()

/**
 * Builds [lineCount] lines of one character each. When [trailingNewline] is
 * false the final line has no terminator.
 */
private fun buildLines(lineCount: Int, trailingNewline: Boolean): String {
    val sb = StringBuilder(lineCount * 2)
    for (i in 0 until lineCount) {
        sb.append(oneCharLine(i))
        if (trailingNewline || i != lineCount - 1) sb.append('\n')
    }
    return sb.toString()
}

private fun writeLines(dir: Path, name: String, content: String): Path {
    val file = dir.resolve(name)
    Files.write(file, content.toByteArray(StandardCharsets.UTF_8))
    return file
}

class ReadToolWindowTest {

    @Test
    fun shortFileReportsRealTotalLines() = runTest {
        withReadWindowDir { dir ->
            val lineCount = 10
            writeLines(dir, "short.txt", buildLines(lineCount, trailingNewline = true))
            val ctx = ToolTestContext(dir)

            val outcome = ReadTool().run(readWindowObj("""{"path":"short.txt"}"""), ctx)

            assertFalse(outcome.isError, "unexpected error: ${outcome.output}")
            assertEquals(lineCount.toString(), outcome.metadata["totalLines"])
            assertTrue(outcome.output.contains("lines 1-$lineCount of $lineCount"), "got: ${outcome.output}")
            assertTrue(outcome.output.contains("1\t"), "expected numbered lines, got: ${outcome.output}")
        }
    }

    @Test
    fun longFileReportsTrueTotalLinesPastScanCap() = runTest {
        withReadWindowDir { dir ->
            val lineCount = Limits.READ_MAX_SCAN_LINES + 500
            writeLines(dir, "long.txt", buildLines(lineCount, trailingNewline = true))
            val ctx = ToolTestContext(dir)

            val outcome = ReadTool().run(readWindowObj("""{"path":"long.txt"}"""), ctx)

            assertFalse(outcome.isError, "unexpected error: ${outcome.output}")
            assertEquals(lineCount.toString(), outcome.metadata["totalLines"])
            assertTrue(
                outcome.metadata["totalLines"] != Limits.READ_MAX_SCAN_LINES.toString(),
                "total must not be the scan cap; got: ${outcome.metadata}",
            )
            assertTrue(outcome.output.contains("of $lineCount"), "got header: ${outcome.output.take(200)}")
            assertEquals(Limits.READ_MAX_LINES.toString(), outcome.metadata["lines"])
            assertEquals("true", outcome.metadata["truncated"])
        }
    }

    @Test
    fun longFileWithoutTrailingNewlineStillCountsLastLine() = runTest {
        withReadWindowDir { dir ->
            val lineCount = Limits.READ_MAX_SCAN_LINES + 500
            val file = writeLines(dir, "long-no-eol.txt", buildLines(lineCount, trailingNewline = false))
            val ctx = ToolTestContext(dir)

            val outcome = ReadTool().run(readWindowObj("""{"path":"long-no-eol.txt"}"""), ctx)

            val bytes = Files.readAllBytes(file)
            assertTrue(bytes.isNotEmpty() && bytes[bytes.size - 1] != '\n'.code.toByte(), "fixture must lack EOL")
            assertFalse(outcome.isError, "unexpected error: ${outcome.output}")
            assertEquals(lineCount.toString(), outcome.metadata["totalLines"])
            assertTrue(outcome.output.contains("of $lineCount"), "got header: ${outcome.output.take(200)}")
        }
    }

    @Test
    fun offsetPastScanCapReportsExactTotal() = runTest {
        withReadWindowDir { dir ->
            val lineCount = Limits.READ_MAX_SCAN_LINES + 500
            val offset = Limits.READ_MAX_SCAN_LINES + 250
            val limit = 10
            writeLines(dir, "long-offset.txt", buildLines(lineCount, trailingNewline = true))
            val ctx = ToolTestContext(dir)

            val outcome = ReadTool().run(
                readWindowObj("""{"path":"long-offset.txt","offset":$offset,"limit":$limit}"""),
                ctx,
            )

            // An offset past the scan cap must still return that window: the cap
            // bounds the scan, not correctness. Breaking at the cap while the
            // window was unfilled returned zero lines and made pagination into a
            // large file quietly yield nothing.
            assertFalse(outcome.isError, "unexpected error: ${outcome.output}")
            assertEquals(lineCount.toString(), outcome.metadata["totalLines"])
            assertTrue(outcome.output.contains("of $lineCount"), "got header: ${outcome.output.take(200)}")
            assertEquals(limit.toString(), outcome.metadata["lines"], "the requested window is returned")
            assertTrue(
                outcome.output.contains("$offset\t"),
                "expected the window to start at line $offset, got: ${outcome.output.take(300)}",
            )
        }
    }
}
