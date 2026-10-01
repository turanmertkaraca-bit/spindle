package dev.lumen.app.ui.model

import dev.spindle.core.model.Message
import dev.spindle.core.model.MessageId
import dev.spindle.core.model.Part
import dev.spindle.core.model.PartId
import dev.spindle.core.model.Role
import dev.spindle.core.model.SessionId
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A user message that carries an image keeps it on the mapped row, so the
 * timeline can draw the attachment. Pure, no Android needed.
 */
class StepMapperAttachmentTest {

    private fun user(vararg parts: Part) = Message(
        id = MessageId("m1"), sessionId = SessionId("s1"), role = Role.USER,
        parts = parts.toList(), createdAt = 0,
    )

    @Test
    fun `an image part is carried on the user row`() {
        val rows = StepMapper.fromMessages(
            listOf(
                user(
                    Part.Text(PartId("p1"), "look at this"),
                    Part.File(PartId("p2"), path = "photo.png", mime = "image/png", dataBase64 = "QUJD"),
                ),
            ),
        )
        assertEquals(1, rows.size)
        assertEquals(StepKind.YOU, rows[0].kind)
        assertEquals("look at this", rows[0].body)
        assertEquals(listOf(UiImage("photo.png", "image/png", "QUJD")), rows[0].images)
    }

    @Test
    fun `an image-only user message still produces a row`() {
        val rows = StepMapper.fromMessages(
            listOf(user(Part.File(PartId("p"), path = "pic.jpg", mime = "image/jpeg", dataBase64 = "QUJD"))),
        )
        assertEquals(1, rows.size)
        assertEquals(1, rows[0].images.size)
        assertEquals("pic.jpg", rows[0].images[0].name)
    }
}
