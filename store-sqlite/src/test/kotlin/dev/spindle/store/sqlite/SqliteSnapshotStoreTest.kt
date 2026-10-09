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
import java.sql.DriverManager

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
    fun pruneBoundedPinsNewestPerSessionAndPath(@TempDir tmpDir: Path) = runTest {
        val s = SqliteSnapshotStore.open(tmpDir.resolve("bounded.db"))
        store = s
        val now = System.currentTimeMillis()
        s.record(snap("a1", "ses_a", "src/a.kt", "old", now - 50))
        s.record(snap("a2", "ses_a", "src/a.kt", "new", now - 40))
        s.record(snap("b1", "ses_a", "src/b.kt", "old", now - 30))
        s.record(snap("b2", "ses_a", "src/b.kt", "new", now - 20))
        s.record(snap("c1", "ses_b", "src/c.kt", "only", now - 10))
        s.record(snap("old1", "ses_c", "src/old.kt", "aged", now - 10L * 24 * 60 * 60 * 1_000))

        // A zero global cap and a tiny age would previously wipe everything.
        val removed = s.pruneBounded(
            keepPerSession = 0,
            maxTotal = 0,
            maxAgeMillis = 24L * 60 * 60 * 1_000,
        )

        // The newest snapshot of every path survives, even ses_c's aged one.
        assertEquals("a2", s.latest(SessionId("ses_a"), "src/a.kt")?.id)
        assertEquals("b2", s.latest(SessionId("ses_a"), "src/b.kt")?.id)
        assertEquals("c1", s.latest(SessionId("ses_b"), "src/c.kt")?.id)
        assertEquals("old1", s.latest(SessionId("ses_c"), "src/old.kt")?.id)
        // Older pre-images beyond the caps are gone.
        assertEquals(2, removed)
        assertEquals(listOf("a2", "b2"), s.forSession(SessionId("ses_a")).map { it.id })
    }

    @Test
    fun pruneBoundedNegativeCapsDropEverythingIncludingPins(@TempDir tmpDir: Path) = runTest {
        val s = SqliteSnapshotStore.open(tmpDir.resolve("negative.db"))
        store = s
        // One pin per (session, path); a negative sentinel must ignore them.
        s.record(snap("a1", "ses_a", "a.kt", "v1", 1L))
        s.record(snap("a2", "ses_a", "a.kt", "v2", 2L))
        s.record(snap("b1", "ses_b", "b.kt", "v1", 1L))

        val negativeKeep = s.pruneBounded(keepPerSession = -1, maxTotal = 100, maxAgeMillis = 0)
        assertEquals(3, negativeKeep, "negative keepPerSession drops everything, pins included")
        assertTrue(s.forSession(SessionId("ses_a")).isEmpty())
        assertTrue(s.forSession(SessionId("ses_b")).isEmpty())

        s.record(snap("c1", "ses_c", "c.kt", "v1", 1L))
        s.record(snap("c2", "ses_c", "c.kt", "v2", 2L))
        val negativeTotal = s.pruneBounded(keepPerSession = 100, maxTotal = -1, maxAgeMillis = 0)
        assertEquals(2, negativeTotal, "negative maxTotal drops everything, pins included")
        assertTrue(s.forSession(SessionId("ses_c")).isEmpty())
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

    @Test
    fun sharedContentIsStoredOnceAndOrphanBlobsAreReclaimed(@TempDir tmpDir: Path) = runTest {
        val db = tmpDir.resolve("blobs.db")
        val s = SqliteSnapshotStore.open(db)
        store = s
        // Two rows carry the same body and hash, so only one blob is written.
        s.record(Snapshot("a1", SessionId("ses_blob"), "a.kt", "shared", "h-shared", 1L))
        s.record(Snapshot("a2", SessionId("ses_blob"), "b.kt", "shared", "h-shared", 2L))

        assertEquals(1, blobCount(db), "the shared body is stored once")
        assertEquals("shared", s.latest(SessionId("ses_blob"), "a.kt")?.content)

        s.delete(listOf("a1", "a2"))
        assertEquals(0, blobCount(db), "the orphaned body is dropped")
    }

    private fun blobCount(db: Path): Int =
        DriverManager.getConnection("jdbc:sqlite:${db.toAbsolutePath()}").use { c ->
            c.createStatement().use { st ->
                st.executeQuery("SELECT COUNT(*) FROM snapshot_blobs").use { rs ->
                    if (rs.next()) rs.getInt(1) else -1
                }
            }
        }
}
