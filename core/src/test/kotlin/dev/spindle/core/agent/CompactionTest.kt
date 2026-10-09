package dev.spindle.core.agent

import dev.spindle.core.event.EventBus
import dev.spindle.core.model.FinishReason
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
import dev.spindle.core.provider.ChatRequest
import dev.spindle.core.provider.ModelInfo
import dev.spindle.core.provider.Provider
import dev.spindle.core.provider.ProviderEvent
import dev.spindle.core.provider.SimpleProviderRegistry
import dev.spindle.core.store.InMemorySessionStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CompactionTest {

    private class SummaryProvider : Provider {
        override val id = "fake"
        var calls = 0
        override suspend fun models() = listOf(ModelInfo(providerId = id, id = "fake-1", label = "fake"))
        override fun stream(request: ChatRequest): Flow<ProviderEvent> {
            calls++
            return listOf(
                ProviderEvent.TextDelta("SUMMARY"),
                ProviderEvent.Finished(FinishReason.STOP),
            ).asFlow()
        }
    }

    @Test
    fun `a long single-prompt run still compacts and keeps the instruction`() = runTest {
        val store = InMemorySessionStore()
        val sid = SessionId("ses_first_prompt")
        store.createSession(
            Session(id = sid, cwd = System.getProperty("user.dir"), createdAt = 0, updatedAt = 0),
        )

        // Only ONE user message, at index 0, followed by enough assistant turns
        // that compaction must engage. The old boundary returned false here and
        // the run could never shrink.
        val total = Compaction.KEEP_RECENT + 4
        val originals = (0 until total).map { index ->
            Message(
                id = MessageId("m$index"),
                sessionId = sid,
                role = if (index == 0) Role.USER else Role.ASSISTANT,
                parts = listOf(
                    Part.Text(
                        PartId("p$index"),
                        if (index == 0) "the instruction" else "assistant $index",
                    ),
                ),
                createdAt = index.toLong(),
            )
        }
        originals.forEach { store.appendMessage(it) }

        val provider = SummaryProvider()
        val changed = Compaction.compact(
            store = store,
            sessionId = sid,
            modelRef = "fake/fake-1",
            providers = SimpleProviderRegistry(listOf(provider)),
            bus = EventBus(),
        )

        assertTrue(changed, "compaction must engage for a long single-prompt run")
        val after = store.messages(sid)
        assertEquals("the instruction", after[0].parts.filterIsInstance<Part.Text>().single().text)
        assertTrue(
            after.drop(1).any { m ->
                m.parts.filterIsInstance<Part.Text>().any { it.text.startsWith(AgentLoop.COMPACT_MARKER) }
            },
            "a summary should have been folded into the head: $after",
        )
    }

    @Test
    fun `compact folds the head and keeps the recent tail verbatim`() = runTest {
        val store = InMemorySessionStore()
        val sid = SessionId("ses_compact")
        store.createSession(
            Session(id = sid, cwd = System.getProperty("user.dir"), createdAt = 0, updatedAt = 0),
        )

        val total = Compaction.KEEP_RECENT + 3
        val originals = (0 until total).map { index ->
            Message(
                id = MessageId("m$index"),
                sessionId = sid,
                role = if (index % 2 == 0) Role.USER else Role.ASSISTANT,
                parts = listOf(Part.Text(PartId("p$index"), "text $index")),
                createdAt = index.toLong(),
            )
        }
        originals.forEach { store.appendMessage(it) }

        val provider = SummaryProvider()
        val changed = Compaction.compact(
            store = store,
            sessionId = sid,
            modelRef = "fake/fake-1",
            providers = SimpleProviderRegistry(listOf(provider)),
            bus = EventBus(),
        )

        assertTrue(changed)
        assertEquals(1, provider.calls)

        val after = store.messages(sid)
        assertEquals(total, after.size)

        val firstText = after.first().parts.filterIsInstance<Part.Text>().single().text
        assertTrue(firstText.startsWith(AgentLoop.COMPACT_MARKER))
        assertTrue(firstText.contains("SUMMARY"))

        val tail = after.takeLast(Compaction.KEEP_RECENT)
        assertEquals(originals.takeLast(Compaction.KEEP_RECENT), tail)

        val currentUser = after.last()
        assertEquals(Role.USER, currentUser.role)
        assertEquals("text ${total - 1}", currentUser.parts.filterIsInstance<Part.Text>().single().text)
    }

    @Test
    fun `compaction runs again as new turns arrive but not when there is nothing new`() = runTest {
        val store = InMemorySessionStore()
        val sid = SessionId("ses_recompact")
        store.createSession(
            Session(id = sid, cwd = System.getProperty("user.dir"), createdAt = 0, updatedAt = 0),
        )
        val provider = SummaryProvider()
        val registry = SimpleProviderRegistry(listOf(provider))

        fun append(index: Int) = Message(
            id = MessageId("m$index"),
            sessionId = sid,
            role = if (index % 2 == 0) Role.USER else Role.ASSISTANT,
            parts = listOf(Part.Text(PartId("p$index"), "text $index")),
            createdAt = index.toLong(),
        )

        val first = Compaction.KEEP_RECENT + 3
        (0 until first).forEach { store.appendMessage(append(it)) }
        assertTrue(Compaction.compact(store, sid, "fake/fake-1", registry, EventBus()))
        assertEquals(1, provider.calls)

        // Nothing new since the first compaction: no second summarization call.
        assertFalse(Compaction.compact(store, sid, "fake/fake-1", registry, EventBus()))
        assertEquals(1, provider.calls)

        // New turns push fresh material into the head, so it compacts again.
        (first until first + Compaction.KEEP_RECENT + 3).forEach { store.appendMessage(append(it)) }
        assertTrue(Compaction.compact(store, sid, "fake/fake-1", registry, EventBus()))
        assertEquals(2, provider.calls)
    }

    @Test
    fun `compaction never folds the current user turn`() = runTest {
        val store = InMemorySessionStore()
        val sid = SessionId("ses_current_turn")
        store.createSession(
            Session(id = sid, cwd = System.getProperty("user.dir"), createdAt = 0, updatedAt = 0),
        )
        val provider = SummaryProvider()
        val registry = SimpleProviderRegistry(listOf(provider))

        // Prior turns that are safe to fold.
        (0 until 4).forEach { i ->
            store.appendMessage(
                Message(
                    id = MessageId("h$i"),
                    sessionId = sid,
                    role = if (i % 2 == 0) Role.USER else Role.ASSISTANT,
                    parts = listOf(Part.Text(PartId("hp$i"), "old $i")),
                    createdAt = i.toLong(),
                ),
            )
        }
        store.appendMessage(
            Message(
                id = MessageId("cur"),
                sessionId = sid,
                role = Role.USER,
                parts = listOf(Part.Text(PartId("curp"), "DO THE THING")),
                createdAt = 100,
            ),
        )
        // More model turns than KEEP_RECENT follow the current prompt, so it is
        // outside the verbatim tail. It must still never be folded.
        repeat(Compaction.KEEP_RECENT + 2) { i ->
            store.appendMessage(
                Message(
                    id = MessageId("a$i"),
                    sessionId = sid,
                    role = Role.ASSISTANT,
                    parts = listOf(Part.Text(PartId("ap$i"), "step $i")),
                    createdAt = 101L + i,
                ),
            )
        }

        assertTrue(Compaction.compact(store, sid, "fake/fake-1", registry, EventBus()))
        assertEquals(1, provider.calls, "prior history should have been summarized once")

        val after = store.messages(sid)
        val preserved = after.first { it.id == MessageId("cur") }
        assertEquals(
            "DO THE THING",
            preserved.parts.filterIsInstance<Part.Text>().single().text,
            "the current user instruction must stay verbatim",
        )
        // The current turn's assistant steps are untouched too.
        assertEquals(Compaction.KEEP_RECENT + 2, after.count { it.id.value.startsWith("a") })
        assertTrue(after.first().parts.filterIsInstance<Part.Text>().single().text.startsWith(AgentLoop.COMPACT_MARKER))
    }

    @Test
    fun `trim bounds old tool diffs and drops oversized inline images`() = runTest {
        val store = InMemorySessionStore()
        val sid = SessionId("ses_trim_media")
        store.createSession(
            Session(id = sid, cwd = System.getProperty("user.dir"), createdAt = 0, updatedAt = 0),
        )
        val hugeDiff = "d".repeat(40_000)
        val hugeImage = "A".repeat(40_000)
        store.appendMessage(
            Message(
                id = MessageId("media"),
                sessionId = sid,
                role = Role.ASSISTANT,
                createdAt = 0,
                parts = listOf(
                    Part.Tool(
                        PartId("t0"),
                        ToolCall("c0", "edit", "{}"),
                        ToolState.DONE,
                        ToolResult("c0", "ok", diff = hugeDiff),
                    ),
                    Part.File(PartId("f0"), path = "pic.png", mime = "image/png", dataBase64 = hugeImage),
                ),
            ),
        )
        repeat(Compaction.KEEP_RECENT + 1) { i ->
            store.appendMessage(
                Message(
                    id = MessageId("pad$i"),
                    sessionId = sid,
                    role = Role.USER,
                    createdAt = i + 1L,
                    parts = listOf(Part.Text(PartId("padp$i"), "x")),
                ),
            )
        }

        Compaction.trim(store, sid)

        val media = store.messages(sid).first { it.id == MessageId("media") }
        val tool = media.parts.filterIsInstance<Part.Tool>().single()
        assertTrue(tool.result!!.diff!!.length < hugeDiff.length, "the diff must be clipped")
        val file = media.parts.filterIsInstance<Part.File>().single()
        assertNull(file.dataBase64, "an old inline image must be dropped from history")
    }
}
