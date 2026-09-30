package dev.spindle.core.agent

import dev.spindle.core.model.Message
import dev.spindle.core.model.MessageId
import dev.spindle.core.model.Part
import dev.spindle.core.model.PartId
import dev.spindle.core.model.Role
import dev.spindle.core.model.SessionId
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
