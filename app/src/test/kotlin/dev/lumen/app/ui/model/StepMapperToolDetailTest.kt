package dev.lumen.app.ui.model

import dev.spindle.core.model.Message
import dev.spindle.core.model.MessageId
import dev.spindle.core.model.Part
import dev.spindle.core.model.PartId
import dev.spindle.core.model.Role
import dev.spindle.core.model.SessionId
import dev.spindle.core.model.ToolCall
import dev.spindle.core.model.ToolResult
import dev.spindle.core.model.ToolState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The calmer tool presentation reads a call's most human argument for the header
 * and turns the raw transport into a plain-language failure line. All pure, so no
 * Android is needed.
 */
class StepMapperToolDetailTest {

    private fun tool(
        id: String,
        name: String,
        arguments: String,
        output: String,
        state: ToolState = ToolState.DONE,
        metadata: Map<String, String> = emptyMap(),
    ) = Message(
        id = MessageId(id), sessionId = SessionId("s"), role = Role.ASSISTANT,
        parts = listOf(
            Part.Tool(
                PartId("p$id"),
                ToolCall("c$id", name, arguments),
                state,
                ToolResult("c$id", output, isError = state == ToolState.ERROR, metadata = metadata),
            ),
        ),
        createdAt = 0,
    )

    @Test
    fun `the header names the command, not the raw output`() {
        val rows = StepMapper.fromMessages(
            listOf(tool("m1", "bash", "{\"command\":\"npm test\"}", "[exit 0]\nall green")),
        )
        val step = rows.single()
        assertEquals("npm test", step.summary)
        assertEquals("all green", step.body)
        assertTrue(step.rows.isEmpty(), "raw output is no longer parsed into duplicate rows")
    }

    @Test
    fun `toolArg reads the first meaningful key across tool shapes`() {
        assertEquals("src/App.kt", StepMapper.toolArg("{\"path\":\"src/App.kt\"}"))
        assertEquals("TODO", StepMapper.toolArg("{\"pattern\":\"TODO\",\"path\":\"src\"}"))
        assertEquals("https://example.com", StepMapper.toolArg("{\"url\":\"https://example.com\"}"))
        assertEquals("build", StepMapper.toolArg("{\"prompt\":\"build\",\"description\":\"build\"}"))
        assertNull(StepMapper.toolArg("{\"count\":3}"))
        assertNull(StepMapper.toolArg(""))
    }

    @Test
    fun `toolArg unescapes simple JSON escapes`() {
        assertEquals("echo \"hi\"", StepMapper.toolArg("{\"command\":\"echo \\\"hi\\\"\"}"))
    }

    @Test
    fun `cleanToolOutput drops the exit and truncation framing`() {
        val raw = "[exit 0]\nhello world\n\u2026[output truncated at 60000 chars]"
        assertEquals("hello world", StepMapper.cleanToolOutput(raw))
        assertEquals("Command timed out after 30ms", StepMapper.cleanToolOutput("[timeout]\nCommand timed out after 30ms"))
        assertEquals("File not found: x.txt", StepMapper.cleanToolOutput("failed: File not found: x.txt"))
    }

    @Test
    fun `failureLine speaks plainly for timeout, exit and generic errors`() {
        assertEquals(
            "timed out after 30s",
            StepMapper.failureLine(mapOf("timeout" to "true", "timeoutMs" to "30000"), "[timeout]\nCommand timed out after 30000ms"),
        )
        assertEquals("failed (exit 159)", StepMapper.failureLine(emptyMap(), "failed: [exit 159] uname -a\nmore"))
        assertEquals("File not found: x.txt", StepMapper.failureLine(emptyMap(), "File not found: x.txt\ncontext"))
    }

    @Test
    fun `humanMillis formats a short human duration`() {
        assertEquals("450ms", StepMapper.humanMillis(450))
        assertEquals("1s", StepMapper.humanMillis(1000))
        assertEquals("1.2s", StepMapper.humanMillis(1200))
        assertEquals("30s", StepMapper.humanMillis(30_000))
    }
}
