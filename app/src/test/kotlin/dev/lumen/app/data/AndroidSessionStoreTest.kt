package dev.lumen.app.data

import androidx.test.core.app.ApplicationProvider
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
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The Android store must round-trip real conversations. Robolectric gives us a
 * working SQLite, so this catches bad SQL and serialization at CI time — the
 * class of bug that was crashing the app on launch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidSessionStoreTest {

    private fun newStore() = AndroidSessionStore(ApplicationProvider.getApplicationContext())

    @Test
    fun `create, read back a session`() = runTest {
        newStore().use { store ->
            val s = Session(SessionId("ses1"), "hello", "/tmp", 1L, 2L, model = "m", providerId = "p")
            store.createSession(s)
            assertEquals(s, store.session(SessionId("ses1")))
            assertEquals(1, store.sessions().size)
        }
    }

    @Test
    fun `messages and parts round-trip, including a tool part`() = runTest {
        newStore().use { store ->
            store.createSession(Session(SessionId("ses2"), "t", "/tmp", 0, 0))
            val msg = Message(
                id = MessageId("m1"), sessionId = SessionId("ses2"), role = Role.ASSISTANT,
                createdAt = 5, usage = Usage(inputTokens = 10, outputTokens = 3),
                parts = listOf(
                    Part.Text(PartId("p1"), "hello"),
                    Part.Tool(
                        PartId("p2"),
                        ToolCall("c1", "read", "{\"path\":\"a.kt\"}"),
                        ToolState.DONE,
                        ToolResult("c1", "contents", false),
                    ),
                ),
            )
            store.appendMessage(msg)
            val back = store.messages(SessionId("ses2"))
            assertEquals(1, back.size)
            assertEquals(2, back[0].parts.size)
            assertEquals("hello", (back[0].parts[0] as Part.Text).text)
            val tool = back[0].parts[1] as Part.Tool
            assertEquals("read", tool.call.name)
            assertEquals(ToolState.DONE, tool.state)
            assertEquals("contents", tool.result?.output)
            assertEquals(13, back[0].usage.totalTokens)
        }
    }

    @Test
    fun `updating a message replaces its parts`() = runTest {
        newStore().use { store ->
            store.createSession(Session(SessionId("ses3"), "t", "/tmp", 0, 0))
            val m = Message(id = MessageId("m2"), sessionId = SessionId("ses3"), role = Role.ASSISTANT, createdAt = 1)
            store.appendMessage(m)
            store.updateMessage(m.copy(parts = listOf(Part.Text(PartId("p9"), "streamed"))))
            val back = store.messages(SessionId("ses3")).single()
            assertEquals(listOf("streamed"), back.parts.filterIsInstance<Part.Text>().map { it.text })
        }
    }

    @Test
    fun `latest message and ordering`() = runTest {
        newStore().use { store ->
            store.createSession(Session(SessionId("ses4"), "t", "/tmp", 0, 0))
            repeat(3) { i ->
                store.appendMessage(
                    Message(MessageId("m$i"), SessionId("ses4"), Role.ASSISTANT, createdAt = i.toLong()),
                )
            }
            val all = store.messages(SessionId("ses4"))
            assertEquals(3, all.size)
            assertEquals("m2", store.latestMessage(SessionId("ses4"))?.id?.value)
        }
    }

    @Test
    fun `child sessions are linked by parentId`() = runTest {
        newStore().use { store ->
            store.createSession(Session(SessionId("p"), "parent", "/tmp", 0, 0))
            store.createSession(Session(SessionId("c"), "child", "/tmp", 0, 0, parentId = SessionId("p")))
            assertEquals(1, store.sessions().size)
            assertEquals(2, store.sessions(includeChildren = true).size)
        }
    }

    @Test
    fun `delete removes everything for the session`() = runTest {
        newStore().use { store ->
            store.createSession(Session(SessionId("ses5"), "t", "/tmp", 0, 0))
            store.appendMessage(Message(MessageId("mz"), SessionId("ses5"), Role.USER, createdAt = 0))
            store.deleteSession(SessionId("ses5"))
            assertEquals(0, store.sessions().size)
            assertEquals(0, store.messages(SessionId("ses5")).size)
        }
    }
}
