package dev.lumen.app.data

import dev.spindle.core.model.Message
import dev.spindle.core.model.MessageId
import dev.spindle.core.model.Part
import dev.spindle.core.model.PartId
import dev.spindle.core.model.Role
import dev.spindle.core.model.Session
import dev.spindle.core.model.SessionId
import dev.spindle.core.model.ToolCall
import dev.spindle.core.model.ToolResult
import dev.spindle.core.model.ToolState
import dev.spindle.core.model.Usage
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The archive is pure JVM (no Android, no store), so this is a plain JUnit test.
 * It asserts the two promises that matter: redacted exports never leak inline
 * payloads, and a decode restores the readable content under fresh ids.
 */
class SessionArchiveTest {

    private val base64Secret = "AAABBBBCCCCDDDD_base64_blob_=="
    private val longOutput = "x".repeat(10_000)

    private fun sample(): Pair<Session, List<Message>> {
        val sid = SessionId("ses_original")
        val session = Session(
            id = sid,
            title = "demo",
            cwd = "/work",
            createdAt = 1L,
            updatedAt = 2L,
            model = "m1",
            providerId = "p1",
            agent = "build",
            pinned = true,
            archived = false,
            tags = listOf("a", "b"),
        )
        val messages = listOf(
            Message(
                id = MessageId("msg1"),
                sessionId = sid,
                role = Role.USER,
                createdAt = 10L,
                parts = listOf(Part.Text(PartId("p1"), "hello world")),
                usage = Usage(inputTokens = 3),
            ),
            Message(
                id = MessageId("msg2"),
                sessionId = sid,
                role = Role.ASSISTANT,
                createdAt = 11L,
                model = "m1",
                providerId = "p1",
                agent = "build",
                parts = listOf(
                    Part.Reasoning(PartId("p2"), "thinking..."),
                    Part.Tool(
                        PartId("p3"),
                        ToolCall("c1", "read", "{\"path\":\"a.kt\"}"),
                        ToolState.DONE,
                        ToolResult("c1", longOutput, isError = false, diff = "--- a\n+++ b"),
                    ),
                    Part.File(PartId("p4"), "a.png", "image/png", base64Secret),
                ),
            ),
        )
        return session to messages
    }

    @Test
    fun `redacted encode drops payloads and truncates long output`() {
        val (session, messages) = sample()
        val encoded = SessionArchive.encode(session, messages, redact = true)

        assertFalse(encoded.contains(base64Secret), "inline base64 payload leaked")
        assertFalse(encoded.contains("--- a\n+++ b"), "tool diff leaked")
        assertTrue(encoded.contains("\"version\""), "document is not versioned")
        assertTrue(encoded.contains("hello world"), "text part missing")
        // 4 KB cap + a short truncation marker, rather than all 10k.
        assertTrue(encoded.contains("truncated"), "long tool output was not truncated")
    }

    @Test
    fun `decode regenerates ids and preserves content`() {
        val (session, messages) = sample()
        val encoded = SessionArchive.encode(session, messages, redact = true)
        val imported = SessionArchive.decode(encoded)

        assertEquals(1, imported.size)
        val (restored, restoredMessages) = imported.single()

        assertEquals("demo (imported)", restored.title)
        assertNull(restored.parentId)
        assertNotEquals(session.id, restored.id)
        assertEquals(listOf("a", "b"), restored.tags)

        assertEquals(2, restoredMessages.size)
        assertNotEquals(messages[0].id, restoredMessages[0].id)
        assertEquals(restored.id, restoredMessages[0].sessionId)
        assertEquals(Role.USER, restoredMessages[0].role)

        val firstText = restoredMessages[0].parts.filterIsInstance<Part.Text>().single()
        assertEquals("hello world", firstText.text)
        assertNotEquals(messages[0].parts[0].id, firstText.id)

        val reasoning = restoredMessages[1].parts.filterIsInstance<Part.Reasoning>().single()
        assertEquals("thinking...", reasoning.text)

        val tool = restoredMessages[1].parts.filterIsInstance<Part.Tool>().single()
        assertEquals("read", tool.call.name)
        assertEquals("{\"path\":\"a.kt\"}", tool.call.argumentsJson)
        assertEquals(ToolState.DONE, tool.state)
        assertTrue(tool.result?.output?.startsWith("x") == true)

        val file = restoredMessages[1].parts.filterIsInstance<Part.File>().single()
        assertEquals("a.png", file.path)
        assertEquals("image/png", file.mime)
        assertNull(file.dataBase64, "redacted file kept its payload")
    }

    @Test
    fun `unredacted encode round-trips binary and diff`() {
        val (session, messages) = sample()
        val encoded = SessionArchive.encode(session, messages, redact = false)
        val imported = SessionArchive.decode(encoded)

        val restored = imported.single().messages
        val file = restored[1].parts.filterIsInstance<Part.File>().single()
        assertEquals(base64Secret, file.dataBase64)
        val tool = restored[1].parts.filterIsInstance<Part.Tool>().single()
        assertEquals("--- a\n+++ b", tool.result?.diff)
        assertEquals(longOutput, tool.result?.output)
    }

    @Test
    fun `malformed input decodes to nothing`() {
        assertTrue(SessionArchive.decode("not json").isEmpty())
    }

    @Test
    fun `an unknown part kind falls back to text instead of failing`() {
        val doc = """
            {
              "version": 1,
              "session": {"id": "s", "title": "t", "cwd": "/c"},
              "messages": [
                {"id": "m", "role": "USER", "parts": [
                  {"kind": "hologram", "id": "p", "text": "still here"}
                ]}
              ]
            }
        """.trimIndent()
        val restored = SessionArchive.decode(doc).single().messages.single().parts.single()
        assertEquals("still here", (restored as Part.Text).text)
    }
}
