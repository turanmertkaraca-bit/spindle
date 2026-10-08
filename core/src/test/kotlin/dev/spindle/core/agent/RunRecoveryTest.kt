package dev.spindle.core.agent

import dev.spindle.core.model.FinishReason
import dev.spindle.core.model.Message
import dev.spindle.core.model.MessageId
import dev.spindle.core.model.Part
import dev.spindle.core.model.PartId
import dev.spindle.core.model.Role
import dev.spindle.core.model.Session
import dev.spindle.core.model.SessionId
import dev.spindle.core.model.SessionState
import dev.spindle.core.model.ToolCall
import dev.spindle.core.model.ToolState
import dev.spindle.core.store.InMemorySessionStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RunRecoveryTest {

    private fun session(id: String, state: SessionState = SessionState.IDLE) = Session(
        id = SessionId(id),
        cwd = "/work",
        createdAt = 0L,
        updatedAt = 0L,
        state = state,
    )

    private fun openAssistant(sessionId: String, toolState: ToolState = ToolState.PENDING) = Message(
        id = MessageId("msg_open"),
        sessionId = SessionId(sessionId),
        role = Role.ASSISTANT,
        parts = listOf(
            Part.Tool(
                id = PartId("prt_tool"),
                call = ToolCall("call_1", "bash", "{}"),
                state = toolState,
            ),
        ),
        createdAt = 1L,
        finish = null,
    )

    @Test
    fun `reconcile aborts an open tool and finalizes an open assistant`() = runTest {
        val store = InMemorySessionStore()
        store.createSession(session("ses_stale", SessionState.RUNNING))
        store.appendMessage(openAssistant("ses_stale"))

        val repaired = RunRecovery.reconcile(store, emptySet())

        assertEquals(1, repaired)
        assertEquals(SessionState.IDLE, store.session(SessionId("ses_stale"))?.state)

        val message = store.message(SessionId("ses_stale"), MessageId("msg_open"))
        assertNotNull(message)
        assertEquals(FinishReason.ERROR, message.finish)
        assertEquals("aborted", message.error)
        val tool = message.parts.single() as Part.Tool
        assertEquals(ToolState.ERROR, tool.state)
        assertEquals("aborted", tool.result?.output)
        assertEquals(true, tool.result?.isError)
    }

    @Test
    fun `reconcile also rewrites a RUNNING tool part`() = runTest {
        val store = InMemorySessionStore()
        store.createSession(session("ses_running", SessionState.RUNNING))
        store.appendMessage(openAssistant("ses_running", ToolState.RUNNING))

        assertEquals(1, RunRecovery.reconcile(store, emptySet()))

        val tool = store.message(SessionId("ses_running"), MessageId("msg_open"))!!.parts.single() as Part.Tool
        assertEquals(ToolState.ERROR, tool.state)
        assertEquals("aborted", tool.result?.output)
    }

    @Test
    fun `reconcile leaves a live session untouched`() = runTest {
        val store = InMemorySessionStore()
        store.createSession(session("ses_live", SessionState.RUNNING))
        store.appendMessage(openAssistant("ses_live"))

        val repaired = RunRecovery.reconcile(store, setOf("ses_live"))

        assertEquals(0, repaired)
        assertEquals(SessionState.RUNNING, store.session(SessionId("ses_live"))?.state)
        val message = store.message(SessionId("ses_live"), MessageId("msg_open"))
        assertNotNull(message)
        assertNull(message.finish)
        val tool = message.parts.single() as Part.Tool
        assertEquals(ToolState.PENDING, tool.state)
        assertNull(tool.result)
    }

    @Test
    fun `reconcile repairs stale sessions even when a live one is present`() = runTest {
        val store = InMemorySessionStore()
        store.createSession(session("ses_live", SessionState.RUNNING))
        store.createSession(session("ses_stale", SessionState.RUNNING))
        store.appendMessage(openAssistant("ses_live"))
        store.appendMessage(openAssistant("ses_stale"))

        val repaired = RunRecovery.reconcile(store, setOf("ses_live"))

        assertEquals(1, repaired)
        assertEquals(SessionState.RUNNING, store.session(SessionId("ses_live"))?.state)
        assertEquals(SessionState.IDLE, store.session(SessionId("ses_stale"))?.state)
    }

    @Test
    fun `updateSessionState changes only the state when other fields were set`() = runTest {
        val store = InMemorySessionStore()
        val seeded = Session(
            id = SessionId("ses_meta"),
            title = "keep me",
            cwd = "/work",
            createdAt = 1L,
            updatedAt = 2L,
            model = "gpt-x",
            providerId = "prov",
            agent = "plan",
            parentId = SessionId("ses_parent"),
            state = SessionState.RUNNING,
            pinned = true,
            archived = true,
            tags = listOf("alpha", "beta"),
        )
        store.createSession(seeded)

        store.updateSessionState(SessionId("ses_meta"), SessionState.IDLE)

        assertEquals(seeded.copy(state = SessionState.IDLE), store.session(SessionId("ses_meta")))
    }

    @Test
    fun `updateSessionState on a missing session is a no-op`() = runTest {
        val store = InMemorySessionStore()

        store.updateSessionState(SessionId("ses_absent"), SessionState.IDLE)

        assertNull(store.session(SessionId("ses_absent")))
    }
}
