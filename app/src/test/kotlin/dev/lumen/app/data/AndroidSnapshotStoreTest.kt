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

    private fun snap(id: String, session: String, at: Long) = Snapshot(
        id = id,
        sessionId = SessionId(session),
        path = "a.kt",
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

        assertEquals(2, removed, "the aged snapshot and s1's oldest should go")
        assertEquals(listOf("a2", "a3"), store.forSession(SessionId("s1")).map { it.id })
        assertEquals(listOf("b1", "b2"), store.forSession(SessionId("s2")).map { it.id })
        assertEquals(emptyList(), store.forSession(SessionId("s3")))
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
}
