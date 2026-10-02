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
}
