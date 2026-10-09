package dev.spindle.core.agent

import dev.spindle.core.model.Message
import dev.spindle.core.model.MessageId
import dev.spindle.core.model.Part
import dev.spindle.core.model.PartId
import dev.spindle.core.model.Role
import dev.spindle.core.model.SessionId
import dev.spindle.core.model.ToolCall
import dev.spindle.core.model.ToolState
import dev.spindle.core.provider.ResponseFormat
import dev.spindle.core.provider.ToolSpec
import dev.spindle.core.provider.WireMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WireTest {

    private fun userMessage(parts: List<Part>) = Message(
        id = MessageId("msg_1"),
        sessionId = SessionId("ses_1"),
        role = Role.USER,
        parts = parts,
        createdAt = 0,
    )

    @Test
    fun `maps inline image parts into wire images in order`() {
        val wire = Wire.toWire(
            listOf(
                userMessage(
                    listOf(
                        Part.Text(PartId("p1"), "look at this"),
                        Part.File(PartId("p2"), "/tmp/a.png", "image/png", "QUJD"),
                        Part.File(PartId("p3"), "/tmp/b.jpg", "image/jpeg", "REVG"),
                    ),
                ),
            ),
        ).single()

        assertEquals("user", wire.role)
        assertEquals("look at this", wire.text)
        assertEquals(
            listOf("image/png" to "QUJD", "image/jpeg" to "REVG"),
            wire.images.map { it.mime to it.base64 },
        )
    }

    @Test
    fun `ignores non-image and non-inline file parts`() {
        val wire = Wire.toWire(
            listOf(
                userMessage(
                    listOf(
                        Part.Text(PartId("p0"), "files"),
                        Part.File(PartId("p1"), "/tmp/a.txt", "text/plain", "aGk="),
                        Part.File(PartId("p2"), "/tmp/b.png", "image/png", null),
                        Part.File(PartId("p3"), "/tmp/c.png", null, "QUJD"),
                    ),
                ),
            ),
        ).single()

        assertEquals(emptyList(), wire.images)
    }

    @Test
    fun `text-only user message keeps empty images`() {
        val wire = Wire.toWire(listOf(userMessage(listOf(Part.Text(PartId("p1"), "hi"))))).single()

        assertEquals("hi", wire.text)
        assertEquals(emptyList(), wire.images)
    }

    @Test
    fun `a pending tool part still gets a matching tool reply`() {
        val assistant = Message(
            id = MessageId("msg_a"),
            sessionId = SessionId("ses_1"),
            role = Role.ASSISTANT,
            parts = listOf(
                Part.Tool(
                    id = PartId("p1"),
                    call = ToolCall("call_1", "bash", "{}"),
                    state = ToolState.PENDING,
                ),
            ),
            createdAt = 0,
        )

        val wire = Wire.toWire(listOf(assistant))

        val callIds = wire.filter { it.role == "assistant" }.flatMap { it.toolCalls }.map { it.id }
        val replyIds = wire.filter { it.role == "tool" }.mapNotNull { it.toolCallId }.toSet()
        assertEquals(listOf("call_1"), callIds)
        assertTrue("call_1" in replyIds, "every advertised tool call needs a reply: $wire")
        assertEquals("(tool did not complete)", wire.single { it.role == "tool" }.text)
    }

    @Test
    fun `a fully empty assistant message is skipped`() {
        val empty = Message(
            id = MessageId("msg_a"),
            sessionId = SessionId("ses_1"),
            role = Role.ASSISTANT,
            parts = emptyList(),
            createdAt = 0,
        )

        assertTrue(Wire.toWire(listOf(empty)).isEmpty())
    }

    @Test
    fun `request carries the agent response format`() {
        val format = ResponseFormat(
            name = "answer",
            schemaJson = """{"type":"object","properties":{"answer":{"type":"string"}}}""",
            strict = false,
        )

        val req = Wire.request(
            model = "gpt-4o",
            system = "sys",
            messages = listOf(WireMessage(role = "user", text = "hi")),
            tools = listOf(ToolSpec("t", "d", "{}")),
            agent = AgentConfig(responseFormat = format),
            sessionHint = "ses_1",
        )

        assertEquals(format, req.responseFormat)
        assertEquals("ses_1", req.sessionHint)
    }

    @Test
    fun `request omits the response format when the agent does not set one`() {
        val req = Wire.request(
            model = "gpt-4o",
            system = "sys",
            messages = listOf(WireMessage(role = "user", text = "hi")),
            tools = emptyList(),
            agent = AgentConfig(),
            sessionHint = null,
        )

        assertNull(req.responseFormat)
    }

    @Test
    fun `empty user and reasoning-only assistant messages are skipped`() {
        val emptyUser = userMessage(
            listOf(Part.File(PartId("p1"), "/tmp/a.txt", "text/plain", "aGk=")),
        )
        val reasoningOnly = Message(
            id = MessageId("msg_a"),
            sessionId = SessionId("ses_1"),
            role = Role.ASSISTANT,
            parts = listOf(Part.Reasoning(PartId("r1"), "thinking...")),
            createdAt = 0,
        )

        assertTrue(Wire.toWire(listOf(emptyUser, reasoningOnly)).isEmpty())
    }

    @Test
    fun `request keeps the model's full output limit`() {
        val req = Wire.request(
            model = "gpt-4o",
            system = "sys",
            messages = listOf(WireMessage(role = "user", text = "hi")),
            tools = emptyList(),
            agent = AgentConfig(),
            sessionHint = null,
            maxOutputTokens = 64_000,
        )

        assertEquals(64_000, req.maxTokens)
    }

    @Test
    fun `usage total counts cached tokens and cost bills the miss once`() {
        val usage = dev.spindle.core.model.Usage(
            inputTokens = 20,
            outputTokens = 7,
            cacheReadTokens = 80,
            cacheWriteTokens = 5,
        )
        assertEquals(112, usage.totalTokens, "miss + output + cache read + cache write")

        val model = dev.spindle.core.provider.ModelInfo(
            providerId = "p",
            id = "m",
            inputCostPerM = 1.0,
            outputCostPerM = 2.0,
            cacheReadCostPerM = 0.1,
            cacheWriteCostPerM = 1.25,
        )
        // 20 miss + 80 read + 5 write + 7 out, priced per 1M.
        val expected = (20 + 7 * 2.0 + 80 * 0.1 + 5 * 1.25) / 1_000_000.0
        assertEquals(expected, Wire.cost(model, usage), 1e-12)
    }
}
