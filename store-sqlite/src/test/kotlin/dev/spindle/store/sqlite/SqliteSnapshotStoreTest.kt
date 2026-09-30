package dev.spindle.store.sqlite

import dev.spindle.core.model.SessionId
import dev.spindle.core.model.Snapshot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class SqliteSnapshotStoreTest {

    private var store: SqliteSnapshotStore? = null

    @AfterEach
    fun tearDown() {
        store?.close()
        store = null
    }

    private fun snap(
        id: String,
        session: String,
        path: String,
        content: String,
        at: Long,
    ) = Snapshot(
        id = id,
        sessionId = SessionId(session),
        path = path,
        content = content,
        sha256 = "sha-$id",
        createdAt = at,
    )

    @Test
    fun recordAndLatest(@TempDir tmpDir: Path) = runTest {
        val s = SqliteSnapshotStore.open(tmpDir.resolve("latest.db"))
        store = s
        val session = SessionId("ses_1")
        s.record(snap("snap_1", "ses_1", "src/a.kt", "one", 10L))
        s.record(snap("snap_2", "ses_1", "src/a.kt", "two", 20L))
        s.record(snap("snap_3", "ses_1", "src/b.kt", "other", 30L))
        s.record(snap("snap_4", "ses_2", "src/a.kt", "foreign", 40L))

        assertEquals("snap_2", s.latest(session, "src/a.kt")?.id)
        assertEquals("snap_3", s.latest(session, "src/b.kt")?.id)
        assertNull(s.latest(session, "src/missing.kt"))
        assertEquals("snap_4", s.latest(SessionId("ses_2"), "src/a.kt")?.id)
    }

    @Test
    fun forSessionReturnsOldestToNewest(@TempDir tmpDir: Path) = runTest {
        val s = SqliteSnapshotStore.open(tmpDir.resolve("order.db"))
        store = s
        val session = SessionId("ses_order")
        s.record(snap("snap_c", "ses_order", "c.kt", "c", 30L))
        s.record(snap("snap_a", "ses_order", "a.kt", "a", 10L))
        s.record(snap("snap_b", "ses_order", "b.kt", "b", 20L))
        s.record(snap("snap_other", "ses_other", "a.kt", "x", 5L))

        assertEquals(
            listOf("snap_a", "snap_b", "snap_c"),
            s.forSession(session).map { it.id },
        )
        assertEquals(listOf("snap_other"), s.forSession(SessionId("ses_other")).map { it.id })
    }

    @Test
    fun deleteById(@TempDir tmpDir: Path) = runTest {
        val s = SqliteSnapshotStore.open(tmpDir.resolve("delete.db"))
        store = s
        val session = SessionId("ses_del")
        s.record(snap("snap_1", "ses_del", "a.kt", "one", 1L))
        s.record(snap("snap_2", "ses_del", "a.kt", "two", 2L))
        s.record(snap("snap_3", "ses_del", "b.kt", "three", 3L))

        s.delete(listOf("snap_1", "snap_3"))
        assertEquals(listOf("snap_2"), s.forSession(session).map { it.id })
        assertEquals("snap_2", s.latest(session, "a.kt")?.id)
        assertNull(s.latest(session, "b.kt"))
    }

    @Test
    fun pruneKeepsNewestPerSession(@TempDir tmpDir: Path) = runTest {
        val s = SqliteSnapshotStore.open(tmpDir.resolve("prune.db"))
        store = s
        (1..5).forEach { i -> s.record(snap("a_$i", "ses_a", "f.kt", "v$i", i.toLong())) }
        (1..3).forEach { i -> s.record(snap("b_$i", "ses_b", "f.kt", "v$i", i.toLong())) }

        val removed = s.prune(keep = 2)
        assertEquals(4, removed)
        assertEquals(listOf("a_4", "a_5"), s.forSession(SessionId("ses_a")).map { it.id })
        assertEquals(listOf("b_2", "b_3"), s.forSession(SessionId("ses_b")).map { it.id })
        assertEquals(0, s.prune(keep = 2))
    }

    @Test
    fun reopenRetainsSnapshots(@TempDir tmpDir: Path) = runTest {
        val db = tmpDir.resolve("nested").resolve("snapshots.db")
        val first = SqliteSnapshotStore.open(db)
        store = first
        first.record(snap("snap_1", "ses_1", "src/a.kt", "hello", 1L))
        first.record(snap("snap_2", "ses_1", "src/a.kt", "world", 2L))
        first.close()

        val second = SqliteSnapshotStore.open(db)
        store = second
        assertEquals(listOf("snap_1", "snap_2"), second.forSession(SessionId("ses_1")).map { it.id })
        assertEquals("world", second.latest(SessionId("ses_1"), "src/a.kt")?.content)
        assertEquals(
            Snapshot("snap_2", SessionId("ses_1"), "src/a.kt", "world", "sha-snap_2", 2L),
            second.latest(SessionId("ses_1"), "src/a.kt"),
        )
        assertTrue(second.forSession(SessionId("ses_missing")).isEmpty())
    }
}
