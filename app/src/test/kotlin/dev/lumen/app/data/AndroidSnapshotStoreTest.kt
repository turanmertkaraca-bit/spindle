package dev.lumen.app.data

import androidx.test.core.app.ApplicationProvider
import dev.spindle.core.model.SessionId
import dev.spindle.core.model.Snapshot
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * The Android snapshot store must stay bounded across a long life: whole-file
 * snapshots are large, so per-session, global and age caps all matter. Robolectric
 * gives a working SQLite so the SQL is exercised, not just the interface.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidSnapshotStoreTest {

    private fun snap(id: String, session: String, at: Long) =
        snap(id, session, "a.kt", at)

    private fun snap(id: String, session: String, path: String, at: Long) = Snapshot(
        id = id,
        sessionId = SessionId(session),
        path = path,
        content = "c",
        sha256 = "h",
        createdAt = at,
    )

    @Test
    fun `pruneBounded caps per session, global count and age`() = runTest {
        val database = AndroidDatabase(ApplicationProvider.getApplicationContext())
        val store = AndroidSnapshotStore(database)
        val now = System.currentTimeMillis()
        store.record(snap("a1", "s1", now - 100))
        store.record(snap("a2", "s1", now - 90))
        store.record(snap("a3", "s1", now - 80))
        store.record(snap("b1", "s2", now - 70))
        store.record(snap("b2", "s2", now - 60))
        store.record(snap("old", "s3", now - 10L * 24 * 60 * 60 * 1_000))

        val removed = store.pruneBounded(
            keepPerSession = 2,
            maxTotal = 4,
            maxAgeMillis = 24L * 60 * 60 * 1_000,
        )

        assertEquals(2, removed, "s1's oldest two go; the aged snapshot is pinned")
        assertEquals(listOf("a3"), store.forSession(SessionId("s1")).map { it.id })
        assertEquals(listOf("b1", "b2"), store.forSession(SessionId("s2")).map { it.id })
        assertEquals(listOf("old"), store.forSession(SessionId("s3")).map { it.id })
        store.close()
    }

    @Test
    fun `pruneBounded pins the newest snapshot per session and path`() = runTest {
        val database = AndroidDatabase(ApplicationProvider.getApplicationContext())
        val store = AndroidSnapshotStore(database)
        val now = System.currentTimeMillis()
        store.record(snap("a1", "s1", "src/a.kt", now - 50))
        store.record(snap("a2", "s1", "src/a.kt", now - 40))
        store.record(snap("b1", "s1", "src/b.kt", now - 30))
        store.record(snap("b2", "s1", "src/b.kt", now - 20))
        store.record(snap("c1", "s2", "src/c.kt", now - 10))
        store.record(snap("old1", "s3", "src/old.kt", now - 10L * 24 * 60 * 60 * 1_000))

        val removed = store.pruneBounded(
            keepPerSession = 0,
            maxTotal = 0,
            maxAgeMillis = 24L * 60 * 60 * 1_000,
        )

        assertEquals("a2", store.latest(SessionId("s1"), "src/a.kt")?.id)
        assertEquals("b2", store.latest(SessionId("s1"), "src/b.kt")?.id)
        assertEquals("c1", store.latest(SessionId("s2"), "src/c.kt")?.id)
        assertEquals("old1", store.latest(SessionId("s3"), "src/old.kt")?.id)
        assertEquals(2, removed)
        assertEquals(listOf("a2", "b2"), store.forSession(SessionId("s1")).map { it.id })
        store.close()
    }

    @Test
    fun `pruneBounded enforces the global cap newest first`() = runTest {
        val database = AndroidDatabase(ApplicationProvider.getApplicationContext())
        val store = AndroidSnapshotStore(database)
        val now = System.currentTimeMillis()
        (0 until 10).forEach { i -> store.record(snap("s$i", "sess", now + i)) }

        val removed = store.pruneBounded(keepPerSession = 100, maxTotal = 3, maxAgeMillis = 0)

        assertEquals(7, removed)
        assertEquals(listOf("s7", "s8", "s9"), store.forSession(SessionId("sess")).map { it.id })
        store.close()
    }

    @Test
    fun `newest follows insertion order even when the clock rolls back`() = runTest {
        val database = AndroidDatabase(ApplicationProvider.getApplicationContext())
        val store = AndroidSnapshotStore(database)
        val now = System.currentTimeMillis()
        // The later record carries the *older* wall clock; monotonic sequence,
        // not created_at, must decide which is newest.
        store.record(snap("clock_ahead", "s1", now))
        store.record(snap("clock_behind", "s1", now - 10_000))

        assertEquals("clock_behind", store.latest(SessionId("s1"), "a.kt")?.id)
        assertEquals(listOf("clock_ahead", "clock_behind"), store.forSession(SessionId("s1")).map { it.id })
        store.close()
    }
}
