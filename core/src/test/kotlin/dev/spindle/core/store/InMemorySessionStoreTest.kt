package dev.spindle.core.store

import dev.spindle.core.model.Message
import dev.spindle.core.model.MessageId
import dev.spindle.core.model.Part
import dev.spindle.core.model.PartId
import dev.spindle.core.model.Role
import dev.spindle.core.model.Session
import dev.spindle.core.model.SessionId
import dev.spindle.core.model.SessionState
import dev.spindle.core.model.ToolCall
import dev.spindle.core.model.ToolResult
import dev.spindle.core.model.ToolState
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InMemorySessionStoreTest {

    private fun session(
        id: String,
        updatedAt: Long = 0L,
        parentId: String? = null,
        archived: Boolean = false,
    ) = Session(
        id = SessionId(id),
        cwd = "/work",
        createdAt = 0L,
        updatedAt = updatedAt,
        parentId = parentId?.let { SessionId(it) },
        archived = archived,
    )

    private fun message(
        id: String,
        sessionId: String,
        role: Role = Role.USER,
        parts: List<Part> = listOf(Part.Text(PartId("$id-t"), "text $id")),
        at: Long = 0L,
    ) = Message(
        id = MessageId(id),
        sessionId = SessionId(sessionId),
        role = role,
        parts = parts,
        createdAt = at,
    )

    @Test
    fun `fork copies messages up to and including the fork point`() = runTest {
        val store = InMemorySessionStore()
        store.createSession(session("s1"))
        store.appendMessage(message("m1", "s1"))
        store.appendMessage(message("m2", "s1"))
        store.appendMessage(message("m3", "s1"))

        val fork = store.forkSession(SessionId("s1"), MessageId("m2"), SessionId("s2"))
        assertEquals(SessionId("s2"), fork?.id)
        assertEquals(SessionId("s1"), fork?.parentId)

        val copied = store.messages(SessionId("s2"))
        assertEquals(listOf("text m1", "text m2"), copied.map { (it.parts.first() as Part.Text).text })
        assertNotEquals(
            store.messages(SessionId("s1")).map { it.id.value },
            copied.map { it.id.value },
        )

        val whole = store.forkSession(SessionId("s1"), null, SessionId("s3"))
        assertEquals(3, store.messages(SessionId("s3")).size)
        assertEquals(SessionId("s1"), whole?.parentId)

        assertNull(store.forkSession(SessionId("missing"), null, SessionId("s4")))
        assertNull(store.forkSession(SessionId("s1"), MessageId("nope"), SessionId("s5")))
    }

    @Test
    fun `rewind drops messages after the point and reports the count`() = runTest {
        val store = InMemorySessionStore()
        store.createSession(session("s1"))
        store.appendMessage(message("m1", "s1"))
        store.appendMessage(message("m2", "s1"))
        store.appendMessage(message("m3", "s1"))

        assertEquals(2, store.rewind(SessionId("s1"), MessageId("m1")))
        assertEquals(listOf("m1"), store.messages(SessionId("s1")).map { it.id.value })
        assertEquals(0, store.rewind(SessionId("s1"), MessageId("nope")))
    }

    @Test
    fun `updatePart replaces one part and leaves the others in order`() = runTest {
        val store = InMemorySessionStore()
        store.createSession(session("s1"))
        store.appendMessage(
            message(
                "m1",
                "s1",
                parts = listOf(
                    Part.Text(PartId("a"), "alpha"),
                    Part.Text(PartId("b"), "beta"),
                    Part.Text(PartId("c"), "gamma"),
                ),
            ),
        )

        store.updatePart(SessionId("s1"), MessageId("m1"), Part.Text(PartId("b"), "BETA"))

        val loaded = store.message(SessionId("s1"), MessageId("m1"))!!
        assertEquals(listOf("a", "b", "c"), loaded.parts.map { it.id.value })
        assertEquals(listOf("alpha", "BETA", "gamma"), loaded.parts.filterIsInstance<Part.Text>().map { it.text })

        // An unseen part id appends; it never disturbs the existing order.
        store.updatePart(SessionId("s1"), MessageId("m1"), Part.Text(PartId("d"), "delta"))
        assertEquals(
            listOf("a", "b", "c", "d"),
            store.message(SessionId("s1"), MessageId("m1"))!!.parts.map { it.id.value },
        )

        // An unknown message is a no-op: nothing is created.
        store.updatePart(SessionId("s1"), MessageId("missing"), Part.Text(PartId("x"), "nope"))
        assertNull(store.message(SessionId("s1"), MessageId("missing")))
        assertEquals(
            listOf("a", "b", "c", "d"),
            store.message(SessionId("s1"), MessageId("m1"))!!.parts.map { it.id.value },
        )
    }

    @Test
    fun `search scans text reasoning and tool output`() = runTest {
        val store = InMemorySessionStore()
        store.createSession(session("s1"))
        store.appendMessage(
            message(
                "m1",
                "s1",
                role = Role.ASSISTANT,
                parts = listOf(
                    Part.Text(PartId("t"), "the quick brown fox"),
                    Part.Reasoning(PartId("r"), "because of reasons"),
                    Part.Tool(
                        id = PartId("tool"),
                        call = ToolCall("c1", "bash", "{}"),
                        state = ToolState.DONE,
                        result = ToolResult("c1", "over the lazy dog"),
                    ),
                ),
                at = 42L,
            ),
        )

        val brown = store.search("brown")
        assertEquals(1, brown.size)
        assertEquals("m1", brown.single().messageId)
        assertEquals(Role.ASSISTANT.name, brown.single().role)
        assertTrue(brown.single().snippet.contains("brown"))
        assertEquals(42L, brown.single().at)

        assertEquals(1, store.search("lazy").size)
        assertEquals(1, store.search("reasons").size)
        assertTrue(store.search("absent").isEmpty())
    }

    @Test
    fun `session metadata round-trips and archived sessions filter out`() = runTest {
        val store = InMemorySessionStore()
        val meta = session("s1", updatedAt = 5L).copy(
            state = SessionState.RUNNING,
            pinned = true,
            archived = true,
            tags = listOf("alpha", "beta"),
        )
        store.createSession(meta)
        store.createSession(session("s2", updatedAt = 6L))

        assertEquals(meta, store.session(SessionId("s1")))

        assertEquals(listOf("s2"), store.sessions().map { it.id.value })
        assertEquals(setOf("s1", "s2"), store.sessions(includeArchived = true).map { it.id.value }.toSet())

        store.createSession(session("child", updatedAt = 7L, parentId = "s1"))
        assertTrue(store.sessions().none { it.id.value == "child" })
        assertTrue(store.sessions(includeChildren = true, includeArchived = true).any { it.id.value == "child" })
    }
}
