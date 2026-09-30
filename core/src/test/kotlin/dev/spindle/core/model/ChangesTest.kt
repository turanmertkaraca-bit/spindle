package dev.spindle.core.model

import dev.spindle.core.store.InMemorySnapshotStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull

class ChangesTest {

    private fun edit(path: String, added: Int, removed: Int, id: String = path + added) = FileEdit(
        id = id,
        sessionId = SessionId("ses_1"),
        path = path,
        added = added,
        removed = removed,
    )

    @Test
    fun `run changes aggregate counts files and lines`() {
        val changes = RunChanges.EMPTY +
            edit("src/a.kt", added = 3, removed = 1) +
            edit("src/a.kt", added = 2, removed = 0, id = "e2") +
            edit("README.md", added = 1, removed = 1)

        assertEquals(2, changes.fileCount)
        assertEquals(3, changes.editCount)
        assertEquals(6, changes.added)
        assertEquals(2, changes.removed)
    }

    @Test
    fun `run changes group by file preserves order`() {
        val changes = RunChanges.EMPTY + edit("a", 1, 0) + edit("b", 1, 0) + edit("a", 1, 0, id = "e2")
        val byFile = changes.byFile()
        assertEquals(listOf("a", "b"), byFile.keys.toList())
        assertEquals(2, byFile.getValue("a").size)
    }

    @Test
    fun `snapshot store returns the latest for a path and deletes`() = runTest {
        val store = InMemorySnapshotStore()
        val sid = SessionId("ses_1")
        val s1 = Snapshot("s1", sid, "a.kt", "one", "hash1", 1)
        val s2 = Snapshot("s2", sid, "a.kt", "two", "hash2", 2)
        store.record(s1)
        store.record(s2)

        assertEquals("two", store.latest(sid, "a.kt")?.content)
        assertNull(store.latest(sid, "missing.kt"))

        store.delete(listOf("s2"))
        assertNotNull(store.latest(sid, "a.kt"))
        assertEquals("one", store.latest(sid, "a.kt")?.content)
    }

    @Test
    fun `snapshot store prunes oldest beyond the cap`() = runTest {
        val store = InMemorySnapshotStore()
        val sid = SessionId("ses_1")
        repeat(5) { i -> store.record(Snapshot("s$i", sid, "a.kt", "v$i", "h$i", i.toLong())) }

        val removed = store.prune(keep = 2)
        assertEquals(3, removed)
        assertEquals(2, store.forSession(sid).size)
    }
}
