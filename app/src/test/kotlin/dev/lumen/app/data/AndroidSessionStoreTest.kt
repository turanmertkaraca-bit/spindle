package dev.lumen.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.spindle.core.model.Message
import dev.spindle.core.model.MessageId
import dev.spindle.core.model.Part
import dev.spindle.core.model.PartId
import dev.spindle.core.model.Role
import dev.spindle.core.model.Session
import dev.spindle.core.model.SessionId
import dev.spindle.core.model.SessionState
import dev.spindle.core.model.Snapshot
import dev.spindle.core.model.ToolCall
import dev.spindle.core.model.ToolResult
import dev.spindle.core.model.ToolState
import dev.spindle.core.model.Usage
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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

    @Test
    fun `delete frees the session's snapshots too`() = runTest {
        val database = AndroidDatabase(ApplicationProvider.getApplicationContext())
        val store = AndroidSessionStore(database)
        val snapshots = AndroidSnapshotStore(database)
        store.createSession(Session(SessionId("ses_snap"), "t", "/tmp", 0, 0))
        snapshots.record(Snapshot("snap_1", SessionId("ses_snap"), "a.kt", "old", "h", 1L))
        assertEquals(1, snapshots.forSession(SessionId("ses_snap")).size)

        store.deleteSession(SessionId("ses_snap"))

        assertEquals(0, snapshots.forSession(SessionId("ses_snap")).size)
        store.close()
    }

    @Test
    fun `search finds message text and forgets deleted sessions`() = runTest {
        newStore().use { store ->
            store.createSession(Session(SessionId("ses6"), "t", "/tmp", 0, 0))
            store.appendMessage(
                Message(
                    MessageId("s1"), SessionId("ses6"), Role.ASSISTANT, createdAt = 1,
                    parts = listOf(Part.Text(PartId("sp1"), "the needle is here")),
                ),
            )
            val hits = store.search("needle")
            assertEquals(1, hits.size, "a token in message text must be found")
            assertEquals("s1", hits.single().messageId)
            assertTrue(hits.single().snippet.contains("needle"), hits.single().snippet)

            store.deleteSession(SessionId("ses6"))
            assertTrue(store.search("needle").isEmpty(), "the index drops deleted sessions")
        }
    }

    @Test
    fun `maintenance keeps the index searchable`() = runTest {
        newStore().use { store ->
            store.createSession(Session(SessionId("ses_maint"), "t", "/tmp", 0, 0))
            store.appendMessage(
                Message(
                    MessageId("mm1"), SessionId("ses_maint"), Role.ASSISTANT, createdAt = 1,
                    parts = listOf(Part.Text(PartId("mp1"), "maintenance needle")),
                ),
            )
            store.maintain()
            assertEquals(1, store.search("needle").size, "an optimized index is still searchable")
        }
    }

    @Test
    fun `search falls back after an FTS write fails mid-session`() = runTest {
        val database = AndroidDatabase(ApplicationProvider.getApplicationContext())
        val store = AndroidSessionStore(database)
        val sid = SessionId("ses_fts_stale")
        store.createSession(Session(sid, "t", "/tmp", 0, 0))
        // Prime the index so it exists and is trusted before we break it.
        store.appendMessage(
            Message(
                MessageId("primer"), sid, Role.USER, createdAt = 0,
                parts = listOf(Part.Text(PartId("pp"), "primer text")),
            ),
        )

        // Yank the index out from under the live store. Only meaningful where
        // FTS5 exists; without it the scan is always used.
        val ftsAvailable = runCatching {
            database.db().execSQL("ALTER TABLE message_fts RENAME TO message_fts_gone")
        }.isSuccess
        assumeTrue("FTS5 is required to exercise the stale-index path", ftsAvailable)

        // This append's FTS write fails; the store must stop trusting the index.
        store.appendMessage(
            Message(
                MessageId("needle"), sid, Role.ASSISTANT, createdAt = 1,
                parts = listOf(Part.Text(PartId("np"), "the needle is here")),
            ),
        )
        // Leave a stale, empty index named message_fts: if the store still
        // trusted FTS it would answer "no hits" and silently miss the row.
        database.db().execSQL(
            "CREATE VIRTUAL TABLE message_fts USING fts5(" +
                "message_id UNINDEXED, session_id UNINDEXED, role UNINDEXED, body)",
        )

        val hits = store.search("needle")
        assertEquals(1, hits.size, "a failed FTS write must force the linear scan")
        assertEquals("needle", hits.single().messageId)
        store.close()
    }

    @Test
    fun `updating a message reindexes its text`() = runTest {
        newStore().use { store ->
            store.createSession(Session(SessionId("ses7"), "t", "/tmp", 0, 0))
            val m = Message(
                MessageId("s2"), SessionId("ses7"), Role.ASSISTANT, createdAt = 1,
                parts = listOf(Part.Text(PartId("sp2"), "alpha")),
            )
            store.appendMessage(m)
            store.updateMessage(m.copy(parts = listOf(Part.Text(PartId("sp3"), "bravo"))))
            assertTrue(store.search("alpha").isEmpty(), "the old text is gone from the index")
            assertEquals(1, store.search("bravo").size, "the new text is searchable")
        }
    }

    @Test
    fun `appendMessage is idempotent and preserves the existing seq`() = runTest {
        newStore().use { store ->
            store.createSession(Session(SessionId("ses_idem"), "t", "/tmp", 0, 0))
            val mx = Message(
                MessageId("mx"), SessionId("ses_idem"), Role.USER, createdAt = 1,
                parts = listOf(Part.Text(PartId("px"), "original text")),
            )
            store.appendMessage(mx)
            store.appendMessage(Message(MessageId("my"), SessionId("ses_idem"), Role.ASSISTANT, createdAt = 2))

            // A re-append of an existing id must neither duplicate it nor move it
            // to a fresh seq slot (which would reorder it after `my`).
            store.appendMessage(
                mx.copy(
                    createdAt = 99,
                    parts = listOf(Part.Text(PartId("px2"), "rewritten")),
                ),
            )

            val all = store.messages(SessionId("ses_idem"))
            assertEquals(2, all.size, "a duplicate id must not create a second row")
            assertEquals(listOf("mx", "my"), all.map { it.id.value }, "the original seq is retained")
            assertEquals("original text", (all[0].parts.single() as Part.Text).text)
        }
    }

    @Test
    fun `FTS rebuild repairs a partial index`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val first = AndroidSessionStore(context)
        val sid = SessionId("ses_partial")
        first.createSession(Session(sid, "t", "/tmp", 0, 0))
        first.appendMessage(
            Message(MessageId("pa"), sid, Role.USER, createdAt = 1, parts = listOf(Part.Text(PartId("qa"), "alpha one"))),
        )
        first.appendMessage(
            Message(MessageId("pb"), sid, Role.ASSISTANT, createdAt = 2, parts = listOf(Part.Text(PartId("qb"), "beta two"))),
        )
        first.appendMessage(
            Message(MessageId("pc"), sid, Role.USER, createdAt = 3, parts = listOf(Part.Text(PartId("qc"), "gamma three"))),
        )
        assertEquals(1, first.search("beta").size)
        first.close()

        // Yank one row out from under the index, as a partial write would.
        val removed = runCatching {
            val raw = context.openOrCreateDatabase("lumen.db", Context.MODE_PRIVATE, null)
            try {
                raw.execSQL("DELETE FROM message_fts WHERE message_id = 'pb'")
            } finally {
                raw.close()
            }
        }
        assumeTrue("FTS5 is required to exercise the repair path", removed.isSuccess)

        newStore().use { second ->
            assertEquals(1, second.search("beta").size, "the missing row is re-indexed")
            assertEquals(1, second.search("alpha").size)
            assertEquals(1, second.search("gamma").size)
        }
    }

    @Test
    fun `deleting a parent removes its descendant sessions`() = runTest {
        val database = AndroidDatabase(ApplicationProvider.getApplicationContext())
        val store = AndroidSessionStore(database)
        store.createSession(Session(SessionId("root"), "r", "/tmp", 0, 0))
        store.createSession(Session(SessionId("child"), "c", "/tmp", 0, 0, parentId = SessionId("root")))
        store.createSession(Session(SessionId("grand"), "g", "/tmp", 0, 0, parentId = SessionId("child")))
        store.appendMessage(Message(MessageId("rm"), SessionId("root"), Role.USER, createdAt = 0))
        store.appendMessage(Message(MessageId("cm"), SessionId("child"), Role.USER, createdAt = 0))
        store.appendMessage(Message(MessageId("gm"), SessionId("grand"), Role.USER, createdAt = 0))

        store.deleteSession(SessionId("root"))

        assertTrue(
            store.sessions(limit = 100, includeChildren = true).isEmpty(),
            "the whole parent_id chain is removed",
        )
        assertTrue(store.messages(SessionId("child")).isEmpty())
        assertTrue(store.messages(SessionId("grand")).isEmpty())
        store.close()
    }

    @Test
    fun `prune keeps only the newest N sessions`() = runTest {
        newStore().use { store ->
            (1..5).forEach { i ->
                store.createSession(Session(SessionId("p$i"), "t", "/tmp", i.toLong() * 10, i.toLong() * 10))
                store.appendMessage(Message(MessageId("pm$i"), SessionId("p$i"), Role.USER, createdAt = i.toLong()))
            }

            val removed = store.prune(keepSessions = 2)

            assertEquals(3, removed)
            assertEquals(listOf("p5", "p4"), store.sessions(limit = 100).map { it.id.value })
            assertTrue(store.messages(SessionId("p1")).isEmpty(), "pruned sessions lose their messages")
            assertEquals(1, store.messages(SessionId("p5")).size, "kept sessions are untouched")
        }
    }

    @Test
    fun `prune budgets over roots so hidden children do not evict them`() = runTest {
        newStore().use { store ->
            store.createSession(Session(SessionId("r1"), "r", "/tmp", 10, 10))
            store.createSession(Session(SessionId("r2"), "r", "/tmp", 20, 20))
            // Recent hidden children hang off r1 and are newer than both roots.
            (1..5).forEach { i ->
                store.createSession(
                    Session(SessionId("c$i"), "c", "/tmp", 100L + i, 100L + i, parentId = SessionId("r1")),
                )
            }

            val removed = store.prune(keepSessions = 2)

            assertEquals(0, removed, "children must not consume the root budget")
            assertEquals(
                listOf("r2", "r1"),
                store.sessions(limit = 100).map { it.id.value },
                "both roots survive even though children are newer",
            )
            assertEquals(5, store.sessions(limit = 100, includeChildren = true).count { it.parentId != null })
        }
    }

    @Test
    fun `prune drops a dropped root's whole subtree`() = runTest {
        newStore().use { store ->
            store.createSession(Session(SessionId("old"), "r", "/tmp", 1, 1))
            store.createSession(Session(SessionId("oc"), "c", "/tmp", 2, 2, parentId = SessionId("old")))
            store.createSession(Session(SessionId("og"), "g", "/tmp", 3, 3, parentId = SessionId("oc")))
            store.appendMessage(Message(MessageId("ogm"), SessionId("og"), Role.USER, createdAt = 0))
            store.createSession(Session(SessionId("new"), "r", "/tmp", 10, 10))

            val removed = store.prune(keepSessions = 1)

            assertEquals(3, removed, "the root and both descendants count as deleted")
            assertEquals(listOf("new"), store.sessions(limit = 100).map { it.id.value })
            assertTrue(store.messages(SessionId("og")).isEmpty(), "subtree messages go with the root")
        }
    }

    @Test
    fun `prune spares a root whose subtree has a running session`() = runTest {
        newStore().use { store ->
            store.createSession(Session(SessionId("old"), "r", "/tmp", 1, 1))
            store.createSession(
                Session(SessionId("live"), "c", "/tmp", 2, 2, parentId = SessionId("old"), state = SessionState.RUNNING),
            )
            store.createSession(Session(SessionId("new"), "r", "/tmp", 10, 10))

            val removed = store.prune(keepSessions = 1)

            assertEquals(0, removed, "a subtree containing a live run is never deleted")
            assertNotNull(store.session(SessionId("old")))
            assertNotNull(store.session(SessionId("live")))
        }
    }

    @Test
    fun `sweepSubagentSessions drops old terminal children but keeps recent and running`() = runTest {
        newStore().use { store ->
            val now = 1_000_000L
            store.createSession(Session(SessionId("parent"), "r", "/tmp", 0, now))
            store.createSession(
                Session(SessionId("old"), "c", "/tmp", 0, now - 5_000, parentId = SessionId("parent")),
            )
            store.createSession(
                Session(SessionId("grand"), "g", "/tmp", 0, now - 5_000, parentId = SessionId("old")),
            )
            store.createSession(
                Session(SessionId("recent"), "c", "/tmp", 0, now, parentId = SessionId("parent")),
            )
            store.createSession(
                Session(
                    SessionId("running"), "c", "/tmp", 0, now - 5_000,
                    parentId = SessionId("parent"), state = SessionState.RUNNING,
                ),
            )

            val removed = store.sweepSubagentSessions(maxAgeMillis = 1_000, now = now)

            assertEquals(2, removed, "the stale child and its grandchild are deleted")
            assertNotNull(store.session(SessionId("recent")), "a recent child is kept for on-demand transcripts")
            assertNotNull(store.session(SessionId("running")), "a running child is never swept")
            assertNull(store.session(SessionId("old")))
            assertNull(store.session(SessionId("grand")))
        }
    }
}
