package dev.spindle.core.store

import dev.spindle.core.model.SessionId
import dev.spindle.core.model.Snapshot
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SnapshotStoreTest {

    private fun snap(session: String, id: String, createdAt: Long) = Snapshot(
        id = id,
        sessionId = SessionId(session),
        path = "a.kt",
        content = "c",
        sha256 = "h",
        createdAt = createdAt,
    )

    @Test
    fun `prune caps each session independently and is bounds safe`() = runTest {
        val store = InMemorySnapshotStore()
        store.record(snap("s1", "s1a", 1))
        store.record(snap("s1", "s1b", 2))
        store.record(snap("s1", "s1c", 3))
        store.record(snap("s2", "s2a", 1))
        store.record(snap("s2", "s2b", 2))

        assertEquals(1, store.prune(2))
        assertEquals(listOf("s1b", "s1c"), store.forSession(SessionId("s1")).map { it.id })
        assertEquals(listOf("s2a", "s2b"), store.forSession(SessionId("s2")).map { it.id })

        assertEquals(4, store.prune(0))
        assertEquals(emptyList(), store.forSession(SessionId("s1")))
        assertEquals(0, store.prune(-5))
    }
}
