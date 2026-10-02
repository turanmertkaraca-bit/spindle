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
import kotlin.test.assertTrue

/**
 * Tool output beyond the first structured rows is preserved so the expanded card
 * can render a long result in full instead of silently truncating it.
 */
class StepMapperToolOutputTest {

    @Test
    fun `toolExtraLines returns everything past the structured rows`() {
        val lines = (1..50).joinToString("\n") { "line $it" }
        val extra = StepMapper.toolExtraLines(lines, structured = 6)
        assertEquals(44, extra.size)
        assertEquals("line 7", extra.first())
        assertEquals("line 50", extra.last())
    }

    @Test
    fun `toolExtraLines drops blanks to match parseToolRows`() {
        val output = "a 1\n\nb 2\n\n\nc 3\nd 4"
        // parseToolRows sees a,b,c,d (4 rows) and returns the first 6.
        val extra = StepMapper.toolExtraLines(output, structured = 4)
        assertTrue(extra.isEmpty(), "no non-blank lines remain past the structured rows")
    }

    @Test
    fun `single-line output has no extra tail`() {
        assertTrue(StepMapper.toolExtraLines("just one line", structured = 0).isEmpty())
    }

    @Test
    fun `a long tool result maps its full output onto the step body`() {
        val lines = (1..50).joinToString("\n") { "output line $it" }
        val msg = Message(
            id = MessageId("m1"), sessionId = SessionId("s"), role = Role.ASSISTANT,
            parts = listOf(
                Part.Tool(
                    PartId("p1"),
                    ToolCall("c1", "bash", "{}"),
                    ToolState.DONE,
                    ToolResult("c1", lines),
                ),
            ),
            createdAt = 0,
        )
        val steps = StepMapper.fromMessages(listOf(msg))
        assertEquals(1, steps.size)
        // The raw 50-line output is retained on the body for the expanded card.
        assertEquals(50, steps[0].body.lines().filter { it.isNotBlank() }.size)
        // And the mapper exposes the remainder past the 6 structured rows.
        assertEquals(44, StepMapper.toolExtraLines(steps[0].body, 6).size)
    }
}
