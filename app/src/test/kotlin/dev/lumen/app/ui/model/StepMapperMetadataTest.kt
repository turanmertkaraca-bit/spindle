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
 * Tool result metadata rides along on the mapped row so the expanded card can
 * draw an inspector. Pure, no Android needed.
 */
class StepMapperMetadataTest {

    private fun toolMessage(id: String, callId: String, metadata: Map<String, String>) = Message(
        id = MessageId(id), sessionId = SessionId("s"), role = Role.ASSISTANT,
        parts = listOf(
            Part.Tool(
                PartId("p$id"),
                ToolCall(callId, "bash", "{}"),
                ToolState.DONE,
                ToolResult(callId, "ok", metadata = metadata),
            ),
        ),
        createdAt = 0,
    )

    @Test
    fun `tool metadata is carried onto the tool row`() {
        val rows = StepMapper.fromMessages(
            listOf(toolMessage("m1", "c1", mapOf("exitCode" to "0", "durationMs" to "1200"))),
        )
        assertEquals(1, rows.size)
        assertEquals(StepKind.TOOL, rows[0].kind)
        assertEquals("0", rows[0].toolMetadata["exitCode"])
        assertEquals("1200", rows[0].toolMetadata["durationMs"])
    }

    @Test
    fun `user and assistant rows leave metadata empty`() {
        val rows = StepMapper.fromMessages(
            listOf(
                Message(MessageId("u"), SessionId("s"), Role.USER, listOf(Part.Text(PartId("t1"), "hi")), 0),
                Message(MessageId("a"), SessionId("s"), Role.ASSISTANT, listOf(Part.Text(PartId("t2"), "hello")), 0),
            ),
        )
        assertTrue(rows.isNotEmpty())
        assertTrue(rows.all { it.toolMetadata.isEmpty() })
    }

    @Test
    fun `adjacent tool calls merge their metadata`() {
        val merged = StepMapper.mergeAdjacent(
            StepMapper.fromMessages(
                listOf(
                    toolMessage("m1", "c1", mapOf("exitCode" to "0", "count" to "1")),
                    toolMessage("m2", "c2", mapOf("count" to "3")),
                ),
            ),
        )
        assertEquals(1, merged.size)
        assertEquals("0", merged[0].toolMetadata["exitCode"])
        assertEquals("3", merged[0].toolMetadata["count"])
    }
}
