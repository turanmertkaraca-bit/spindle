package dev.spindle.store.sqlite

import dev.spindle.core.model.FinishReason
import dev.spindle.core.model.Message
import dev.spindle.core.model.MessageId
import dev.spindle.core.model.Part
import dev.spindle.core.model.PartId
import dev.spindle.core.model.Role
import dev.spindle.core.model.Session
import dev.spindle.core.model.SessionId
import dev.spindle.core.model.TodoItem
import dev.spindle.core.model.TodoStatus
import dev.spindle.core.model.ToolCall
import dev.spindle.core.model.ToolResult
import dev.spindle.core.model.ToolState
import dev.spindle.core.model.Usage
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class SqliteSessionStoreTest {

    private var store: SqliteSessionStore? = null

    @AfterEach
    fun tearDown() {
        store?.close()
        store = null
    }

    private fun text(id: String, value: String) = Part.Text(PartId(id), value)

    @Test
    fun roundTripAcrossReopen(@TempDir tmpDir: Path) = runTest {
        val db = tmpDir.resolve("nested").resolve("spindle.db")
        val session = Session(
            id = SessionId("ses_1"),
            title = "Round trip",
            cwd = "/work",
            createdAt = 100L,
            updatedAt = 200L,
            model = "gpt-x",
            providerId = "openai",
            agent = "build",
        )
        val messageId = MessageId("msg_1")
        val pendingTool = Part.Tool(
            id = PartId("part_tool"),
            call = ToolCall(id = "call_1", name = "bash", argumentsJson = """{"cmd":"ls"}"""),
            state = ToolState.PENDING,
            title = "List files",
        )
        val message = Message(
            id = messageId,
            sessionId = session.id,
            role = Role.ASSISTANT,
            parts = listOf(
                text("part_text", "here is the answer"),
                Part.Reasoning(PartId("part_reason"), "because reasons"),
                pendingTool,
            ),
            createdAt = 150L,
            model = "gpt-x",
            providerId = "openai",
            agent = "build",
            usage = Usage(
                inputTokens = 11,
                outputTokens = 22,
                reasoningTokens = 3,
                cacheReadTokens = 4,
                cacheWriteTokens = 5,
                costUsd = 0.0123,
            ),
            finish = FinishReason.STOP,
        )
        val doneTool = pendingTool.copy(
            state = ToolState.DONE,
            result = ToolResult(
                callId = "call_1",
                output = "file.txt",
                isError = false,
                diff = "--- a\n+++ b",
                metadata = mapOf("exit" to "0"),
            ),
        )
        val updated = message.copy(parts = listOf(message.parts[0], message.parts[1], doneTool))

        val first = SqliteSessionStore.open(db)
        store = first
        first.createSession(session)
        first.appendMessage(message)
        first.updateMessage(updated)
        first.setTodos(
            session.id,
            listOf(TodoItem("todo_1", "write tests", TodoStatus.IN_PROGRESS), TodoItem("todo_2", "ship", TodoStatus.DONE)),
        )
        first.close()

        val second = SqliteSessionStore.open(db)
        store = second
        assertEquals(session, second.session(session.id))

        val loaded = second.messages(session.id)
        assertEquals(1, loaded.size)
        assertEquals(updated, loaded.single())
        assertEquals(FinishReason.STOP, loaded.single().finish)
        assertEquals(0.0123, loaded.single().usage.costUsd, 1e-9)
        assertEquals(updated, second.message(session.id, messageId))
        assertEquals(updated, second.latestMessage(session.id))
        assertEquals(
            listOf(
                TodoItem("todo_1", "write tests", TodoStatus.IN_PROGRESS),
                TodoItem("todo_2", "ship", TodoStatus.DONE),
            ),
            second.todos(session.id),
        )
    }

    @Test
    fun messagesAndPartsPreserveOrder(@TempDir tmpDir: Path) = runTest {
        val db = tmpDir.resolve("order.db")
        val s = SqliteSessionStore.open(db)
        store = s
        val sessionId = SessionId("ses_order")
        s.createSession(Session(id = sessionId, cwd = "/work", createdAt = 1L, updatedAt = 1L))

        val m1 = Message(
            id = MessageId("msg_1"),
            sessionId = sessionId,
            role = Role.USER,
            parts = listOf(text("a", "first"), text("b", "second")),
            createdAt = 1L,
        )
        val m2 = Message(
            id = MessageId("msg_2"),
            sessionId = sessionId,
            role = Role.ASSISTANT,
            parts = listOf(text("c", "third"), text("d", "fourth"), text("e", "fifth")),
            createdAt = 2L,
        )
        s.appendMessage(m1)
        s.appendMessage(m2)
        s.updateMessage(m1.copy(parts = listOf(text("a2", "first-updated"), text("b2", "second-updated"))))

        val loaded = s.messages(sessionId)
        assertEquals(listOf("msg_1", "msg_2"), loaded.map { it.id.value })
        assertEquals(listOf("first-updated", "second-updated"), loaded[0].parts.map { (it as Part.Text).text })
        assertEquals(listOf("third", "fourth", "fifth"), loaded[1].parts.map { (it as Part.Text).text })
        assertEquals("msg_2", s.latestMessage(sessionId)?.id?.value)
    }

    @Test
    fun pruneKeepsMostRecentSessions(@TempDir tmpDir: Path) = runTest {
        val db = tmpDir.resolve("prune.db")
        val s = SqliteSessionStore.open(db)
        store = s
        val ids = (1..5).map { SessionId("ses_$it") }
        ids.forEachIndexed { index, id ->
            val at = index.toLong()
            s.createSession(Session(id = id, cwd = "/work", createdAt = at, updatedAt = at))
            s.appendMessage(
                Message(
                    id = MessageId("msg_$id"),
                    sessionId = id,
                    role = Role.USER,
                    parts = listOf(text("part_$id", "hello")),
                    createdAt = at,
                ),
            )
            s.setTodos(id, listOf(TodoItem("todo_$id", "task", TodoStatus.PENDING)))
        }

        val removed = s.prune(keepSessions = 2)
        assertTrue(removed > 0)

        val remaining = s.sessions(limit = 100).map { it.id.value }
        assertEquals(listOf("ses_5", "ses_4"), remaining)
        assertTrue(s.messages(ids[0]).isEmpty())
        assertNotNull(s.session(ids[4]))
        assertEquals(1, s.messages(ids[4]).size)
        assertEquals(1, s.todos(ids[4]).size)
        assertTrue(s.todos(ids[0]).isEmpty())
    }
}
