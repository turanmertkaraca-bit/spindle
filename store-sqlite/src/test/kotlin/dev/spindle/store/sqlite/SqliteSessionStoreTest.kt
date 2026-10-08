package dev.spindle.store.sqlite

import dev.spindle.core.model.FinishReason
import dev.spindle.core.model.Message
import dev.spindle.core.model.MessageId
import dev.spindle.core.model.Part
import dev.spindle.core.model.PartId
import dev.spindle.core.model.Role
import dev.spindle.core.model.Session
import dev.spindle.core.model.SessionId
import dev.spindle.core.model.SessionState
import dev.spindle.core.model.TodoItem
import dev.spindle.core.model.TodoStatus
import dev.spindle.core.model.ToolCall
import dev.spindle.core.model.ToolResult
import dev.spindle.core.model.ToolState
import dev.spindle.core.model.Usage
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.DriverManager

class SqliteSessionStoreTest {

    private var store: SqliteSessionStore? = null

    @AfterEach
    fun tearDown() {
        store?.close()
        store = null
    }

    private fun text(id: String, value: String) = Part.Text(PartId(id), value)

    @Test
    fun roundTripAcrossReopen(@TempDir tmpDir: Path) = runTest {
        val db = tmpDir.resolve("nested").resolve("spindle.db")
        val session = Session(
            id = SessionId("ses_1"),
            title = "Round trip",
            cwd = "/work",
            createdAt = 100L,
            updatedAt = 200L,
            model = "gpt-x",
            providerId = "openai",
            agent = "build",
        )
        val messageId = MessageId("msg_1")
        val pendingTool = Part.Tool(
            id = PartId("part_tool"),
            call = ToolCall(id = "call_1", name = "bash", argumentsJson = """{"cmd":"ls"}"""),
            state = ToolState.PENDING,
            title = "List files",
        )
        val message = Message(
            id = messageId,
            sessionId = session.id,
            role = Role.ASSISTANT,
            parts = listOf(
                text("part_text", "here is the answer"),
                Part.Reasoning(PartId("part_reason"), "because reasons"),
                pendingTool,
            ),
            createdAt = 150L,
            model = "gpt-x",
            providerId = "openai",
            agent = "build",
            usage = Usage(
                inputTokens = 11,
                outputTokens = 22,
                reasoningTokens = 3,
                cacheReadTokens = 4,
                cacheWriteTokens = 5,
                costUsd = 0.0123,
            ),
            finish = FinishReason.STOP,
        )
        val doneTool = pendingTool.copy(
            state = ToolState.DONE,
            result = ToolResult(
                callId = "call_1",
                output = "file.txt",
                isError = false,
                diff = "--- a\n+++ b",
                metadata = mapOf("exit" to "0"),
            ),
        )
        val updated = message.copy(parts = listOf(message.parts[0], message.parts[1], doneTool))

        val first = SqliteSessionStore.open(db)
        store = first
        first.createSession(session)
        first.appendMessage(message)
        first.updateMessage(updated)
        first.setTodos(
            session.id,
            listOf(TodoItem("todo_1", "write tests", TodoStatus.IN_PROGRESS), TodoItem("todo_2", "ship", TodoStatus.DONE)),
        )
        first.close()

        val second = SqliteSessionStore.open(db)
        store = second
        assertEquals(session, second.session(session.id))

        val loaded = second.messages(session.id)
        assertEquals(1, loaded.size)
        assertEquals(updated, loaded.single())
        assertEquals(FinishReason.STOP, loaded.single().finish)
        assertEquals(0.0123, loaded.single().usage.costUsd, 1e-9)
        assertEquals(updated, second.message(session.id, messageId))
        assertEquals(updated, second.latestMessage(session.id))
        assertEquals(
            listOf(
                TodoItem("todo_1", "write tests", TodoStatus.IN_PROGRESS),
                TodoItem("todo_2", "ship", TodoStatus.DONE),
            ),
            second.todos(session.id),
        )
    }

    @Test
    fun messagesAndPartsPreserveOrder(@TempDir tmpDir: Path) = runTest {
        val db = tmpDir.resolve("order.db")
        val s = SqliteSessionStore.open(db)
        store = s
        val sessionId = SessionId("ses_order")
        s.createSession(Session(id = sessionId, cwd = "/work", createdAt = 1L, updatedAt = 1L))

        val m1 = Message(
            id = MessageId("msg_1"),
            sessionId = sessionId,
            role = Role.USER,
            parts = listOf(text("a", "first"), text("b", "second")),
            createdAt = 1L,
        )
        val m2 = Message(
            id = MessageId("msg_2"),
            sessionId = sessionId,
            role = Role.ASSISTANT,
            parts = listOf(text("c", "third"), text("d", "fourth"), text("e", "fifth")),
            createdAt = 2L,
        )
        s.appendMessage(m1)
        s.appendMessage(m2)
        s.updateMessage(m1.copy(parts = listOf(text("a2", "first-updated"), text("b2", "second-updated"))))

        val loaded = s.messages(sessionId)
        assertEquals(listOf("msg_1", "msg_2"), loaded.map { it.id.value })
        assertEquals(listOf("first-updated", "second-updated"), loaded[0].parts.map { (it as Part.Text).text })
        assertEquals(listOf("third", "fourth", "fifth"), loaded[1].parts.map { (it as Part.Text).text })
        assertEquals("msg_2", s.latestMessage(sessionId)?.id?.value)
    }

    @Test
    fun updatePartReplacesOnePartWithoutTouchingOthers(@TempDir tmpDir: Path) = runTest {
        val db = tmpDir.resolve("update-part.db")
        val s = SqliteSessionStore.open(db)
        store = s
        val sid = SessionId("ses_update_part")
        s.createSession(Session(id = sid, cwd = "/w", createdAt = 1L, updatedAt = 1L))
        s.appendMessage(
            Message(
                id = MessageId("m1"),
                sessionId = sid,
                role = Role.ASSISTANT,
                parts = listOf(text("a", "alpha"), text("b", "beta"), text("c", "gamma")),
                createdAt = 1L,
            ),
        )

        s.updatePart(sid, MessageId("m1"), text("b", "zeta"))

        val loaded = s.messages(sid).single()
        assertEquals(listOf("a", "b", "c"), loaded.parts.map { it.id.value })
        assertEquals(listOf("alpha", "zeta", "gamma"), loaded.parts.map { (it as Part.Text).text })
        // The refreshed FTS row picks up the new text and drops the old.
        assertEquals(1, s.search("zeta").size)
        assertTrue(s.search("beta").isEmpty())

        // An unseen part id appends; it never disturbs the existing order.
        s.updatePart(sid, MessageId("m1"), text("d", "delta"))
        assertEquals(listOf("a", "b", "c", "d"), s.messages(sid).single().parts.map { it.id.value })

        // An unknown message is a no-op: nothing is created.
        s.updatePart(sid, MessageId("missing"), text("x", "nope"))
        assertNull(s.message(sid, MessageId("missing")))
        assertEquals(listOf("a", "b", "c", "d"), s.messages(sid).single().parts.map { it.id.value })
    }

    @Test
    fun pruneKeepsMostRecentSessions(@TempDir tmpDir: Path) = runTest {
        val db = tmpDir.resolve("prune.db")
        val s = SqliteSessionStore.open(db)
        store = s
        val ids = (1..5).map { SessionId("ses_$it") }
        ids.forEachIndexed { index, id ->
            val at = index.toLong()
            s.createSession(Session(id = id, cwd = "/work", createdAt = at, updatedAt = at))
            s.appendMessage(
                Message(
                    id = MessageId("msg_$id"),
                    sessionId = id,
                    role = Role.USER,
                    parts = listOf(text("part_$id", "hello")),
                    createdAt = at,
                ),
            )
            s.setTodos(id, listOf(TodoItem("todo_$id", "task", TodoStatus.PENDING)))
        }

        val removed = s.prune(keepSessions = 2)
        assertEquals(3, removed)

        val remaining = s.sessions(limit = 100).map { it.id.value }
        assertEquals(listOf("ses_5", "ses_4"), remaining)
        assertTrue(s.messages(ids[0]).isEmpty())
        assertNotNull(s.session(ids[4]))
        assertEquals(1, s.messages(ids[4]).size)
        assertEquals(1, s.todos(ids[4]).size)
        assertTrue(s.todos(ids[0]).isEmpty())
    }

    @Test
    fun forkCopiesMessagesUpToForkPoint(@TempDir tmpDir: Path) = runTest {
        val db = tmpDir.resolve("fork.db")
        val s = SqliteSessionStore.open(db)
        store = s
        val source = SessionId("ses_src")
        s.createSession(Session(id = source, cwd = "/w", createdAt = 1L, updatedAt = 1L))
        s.appendMessage(
            Message(MessageId("m1"), source, Role.USER, listOf(text("p1", "one")), 1L),
        )
        s.appendMessage(
            Message(MessageId("m2"), source, Role.ASSISTANT, listOf(text("p2", "two")), 2L),
        )
        s.appendMessage(
            Message(MessageId("m3"), source, Role.USER, listOf(text("p3", "three")), 3L),
        )

        val fork = s.forkSession(source, MessageId("m2"), SessionId("ses_fork"))
        assertNotNull(fork)
        assertEquals(source, fork?.parentId)

        val copied = s.messages(SessionId("ses_fork"))
        assertEquals(listOf("one", "two"), copied.map { (it.parts.single() as Part.Text).text })
        assertEquals(listOf("m1", "m2", "m3"), s.messages(source).map { it.id.value })

        assertNull(s.forkSession(SessionId("nope"), null, SessionId("ses_x")))
    }

    @Test
    fun rewindDropsTail(@TempDir tmpDir: Path) = runTest {
        val db = tmpDir.resolve("rewind.db")
        val s = SqliteSessionStore.open(db)
        store = s
        val sid = SessionId("ses_rewind")
        s.createSession(Session(id = sid, cwd = "/w", createdAt = 1L, updatedAt = 1L))
        s.appendMessage(Message(MessageId("m1"), sid, Role.USER, listOf(text("p1", "one")), 1L))
        s.appendMessage(Message(MessageId("m2"), sid, Role.ASSISTANT, listOf(text("p2", "two")), 2L))
        s.appendMessage(Message(MessageId("m3"), sid, Role.USER, listOf(text("p3", "three")), 3L))

        assertEquals(2, s.rewind(sid, MessageId("m1")))
        assertEquals(listOf("m1"), s.messages(sid).map { it.id.value })
        assertEquals(0, s.rewind(sid, MessageId("m1")))
    }

    @Test
    fun searchTracksInsertsUpdatesAndDeletes(@TempDir tmpDir: Path) = runTest {
        val db = tmpDir.resolve("search.db")
        val s = SqliteSessionStore.open(db)
        store = s
        val sid = SessionId("ses_search")
        s.createSession(Session(id = sid, cwd = "/w", createdAt = 1L, updatedAt = 1L))
        val message = Message(
            id = MessageId("msg_search"),
            sessionId = sid,
            role = Role.ASSISTANT,
            parts = listOf(text("pt", "the quick brown fox")),
            createdAt = 9L,
        )
        s.appendMessage(message)

        val hits = s.search("brown")
        assertEquals(1, hits.size)
        assertEquals("msg_search", hits.single().messageId)
        assertEquals(Role.ASSISTANT.name, hits.single().role)
        assertEquals(9L, hits.single().at)
        assertTrue(hits.single().snippet.contains("brown"))
        assertEquals(1, s.search("bro").size)

        s.updateMessage(message.copy(parts = listOf(text("pt2", "entirely different words"))))
        assertTrue(s.search("brown").isEmpty())
        assertEquals(1, s.search("different").size)

        s.deleteSession(sid)
        assertTrue(s.search("different").isEmpty())
    }

    @Test
    fun sessionMetadataRoundTripsAndArchivedFilters(@TempDir tmpDir: Path) = runTest {
        val db = tmpDir.resolve("meta.db")
        val s = SqliteSessionStore.open(db)
        store = s
        val meta = Session(
            id = SessionId("ses_meta"),
            cwd = "/w",
            createdAt = 1L,
            updatedAt = 2L,
            state = SessionState.RUNNING,
            pinned = true,
            archived = true,
            tags = listOf("alpha", "beta"),
        )
        s.createSession(meta)
        s.createSession(Session(id = SessionId("ses_live"), cwd = "/w", createdAt = 3L, updatedAt = 4L))

        assertEquals(meta, s.session(SessionId("ses_meta")))
        assertEquals(listOf("ses_live"), s.sessions().map { it.id.value })
        assertEquals(
            setOf("ses_meta", "ses_live"),
            s.sessions(includeArchived = true).map { it.id.value }.toSet(),
        )

        s.createSession(Session(id = SessionId("ses_child"), cwd = "/w", createdAt = 5L, updatedAt = 6L, parentId = SessionId("ses_meta")))
        assertTrue(s.sessions().none { it.id.value == "ses_child" })
        assertTrue(
            s.sessions(includeChildren = true, includeArchived = true).any { it.id.value == "ses_child" },
        )
    }

    @Test
    fun migratesLegacyDatabaseWithoutDataLoss(@TempDir tmpDir: Path) = runTest {
        val db = tmpDir.resolve("legacy.db")
        val legacyJson = Json {
            encodeDefaults = true
            classDiscriminator = "kind"
            serializersModule = SerializersModule {
                polymorphic(Part::class) {
                    subclass(Part.Text::class, Part.Text.serializer())
                    subclass(Part.Reasoning::class, Part.Reasoning.serializer())
                    subclass(Part.Tool::class, Part.Tool.serializer())
                    subclass(Part.File::class, Part.File.serializer())
                    subclass(Part.Step::class, Part.Step.serializer())
                }
            }
        }
        val partData = legacyJson.encodeToString(Part.serializer(), Part.Text(PartId("p_old"), "legacy hello"))

        DriverManager.getConnection("jdbc:sqlite:$db").use { connection ->
            connection.createStatement().use { st ->
                st.execute("CREATE TABLE schema_version (version INTEGER NOT NULL)")
                st.execute("INSERT INTO schema_version(version) VALUES (1)")
                st.execute(
                    "CREATE TABLE sessions (id TEXT PRIMARY KEY, title TEXT, cwd TEXT, created_at INTEGER, " +
                        "updated_at INTEGER, model TEXT, provider_id TEXT, agent TEXT, parent_id TEXT)",
                )
                st.execute(
                    "CREATE TABLE messages (id TEXT PRIMARY KEY, session_id TEXT, role TEXT, created_at INTEGER, " +
                        "model TEXT, provider_id TEXT, agent TEXT, usage TEXT, finish TEXT, error TEXT, seq INTEGER)",
                )
                st.execute(
                    "CREATE TABLE parts (id TEXT PRIMARY KEY, message_id TEXT, session_id TEXT, ord INTEGER, data TEXT)",
                )
                st.execute(
                    "CREATE TABLE todos (id TEXT PRIMARY KEY, session_id TEXT, ord INTEGER, content TEXT, status TEXT)",
                )
                st.execute("INSERT INTO sessions VALUES ('ses_old','Old','/w',1,2,NULL,NULL,'build',NULL)")
                st.execute("INSERT INTO messages VALUES ('msg_old','ses_old','USER',1,NULL,NULL,NULL,NULL,NULL,NULL,1)")
                st.execute("INSERT INTO parts VALUES ('p_old','msg_old','ses_old',0,'$partData')")
            }
        }

        val s = SqliteSessionStore.open(db)
        store = s
        val migrated = s.session(SessionId("ses_old"))
        assertNotNull(migrated)
        assertEquals(SessionState.IDLE, migrated?.state)
        assertEquals(false, migrated?.pinned)
        assertEquals(false, migrated?.archived)
        assertEquals(emptyList<String>(), migrated?.tags)

        assertEquals(listOf("msg_old"), s.messages(SessionId("ses_old")).map { it.id.value })
        assertEquals(1, s.search("legacy").size)
        assertEquals(listOf("ses_old"), s.sessions().map { it.id.value })
    }

    @Test
    fun searchFallsBackWhenFtsIndexIsUnavailable(@TempDir tmpDir: Path) = runTest {
        val db = tmpDir.resolve("fts-fallback.db")
        val s = SqliteSessionStore.open(db)
        store = s
        val sid = SessionId("ses_fallback")
        s.createSession(Session(id = sid, cwd = "/w", createdAt = 1L, updatedAt = 1L))
        s.appendMessage(
            Message(
                id = MessageId("msg_fallback"),
                sessionId = sid,
                role = Role.ASSISTANT,
                parts = listOf(text("p_fb", "the fallback path still works")),
                createdAt = 7L,
            ),
        )
        assertEquals(1, s.search("fallback").size)

        // Break the index out from under the open store: search must fall back
        // to the linear scan rather than reporting "no matches".
        DriverManager.getConnection("jdbc:sqlite:$db").use { conn ->
            conn.createStatement().use { it.execute("ALTER TABLE message_fts RENAME TO message_fts_gone") }
        }

        val hits = s.search("fallback")
        assertEquals(1, hits.size)
        assertEquals("msg_fallback", hits.single().messageId)
        assertEquals(Role.ASSISTANT.name, hits.single().role)
        assertEquals(7L, hits.single().at)
        assertTrue(hits.single().snippet.contains("fallback"))
    }

    @Test
    fun backfillRepairsPartialIndex(@TempDir tmpDir: Path) = runTest {
        val db = tmpDir.resolve("fts-partial.db")
        val first = SqliteSessionStore.open(db)
        store = first
        val sid = SessionId("ses_partial")
        first.createSession(Session(id = sid, cwd = "/w", createdAt = 1L, updatedAt = 1L))
        first.appendMessage(Message(MessageId("m1"), sid, Role.USER, listOf(text("p1", "alpha one")), 1L))
        first.appendMessage(Message(MessageId("m2"), sid, Role.ASSISTANT, listOf(text("p2", "beta two")), 2L))
        first.appendMessage(Message(MessageId("m3"), sid, Role.USER, listOf(text("p3", "gamma three")), 3L))
        assertEquals(1, first.search("beta").size)
        first.close()

        DriverManager.getConnection("jdbc:sqlite:$db").use { conn ->
            conn.createStatement().use { it.executeUpdate("DELETE FROM message_fts WHERE message_id = 'm2'") }
        }

        val second = SqliteSessionStore.open(db)
        store = second
        val hits = second.search("beta")
        assertEquals(1, hits.size)
        assertEquals("m2", hits.single().messageId)
        assertEquals(1, second.search("alpha").size)
        assertEquals(1, second.search("gamma").size)
    }

    @Test
    fun opensDatabaseWithV2ColumnsAndNoSchemaVersion(@TempDir tmpDir: Path) = runTest {
        val db = tmpDir.resolve("android-style.db")
        DriverManager.getConnection("jdbc:sqlite:$db").use { conn ->
            conn.createStatement().use { st ->
                st.execute(
                    "CREATE TABLE sessions (id TEXT PRIMARY KEY, title TEXT, cwd TEXT, created_at INTEGER, " +
                        "updated_at INTEGER, model TEXT, provider_id TEXT, agent TEXT, parent_id TEXT, " +
                        "state TEXT, pinned INTEGER, archived INTEGER, tags TEXT)",
                )
                st.execute(
                    "CREATE TABLE messages (id TEXT PRIMARY KEY, session_id TEXT, role TEXT, created_at INTEGER, " +
                        "model TEXT, provider_id TEXT, agent TEXT, usage TEXT, finish TEXT, error TEXT, seq INTEGER)",
                )
                st.execute(
                    "CREATE TABLE parts (id TEXT PRIMARY KEY, message_id TEXT, session_id TEXT, ord INTEGER, data TEXT)",
                )
                st.execute(
                    "CREATE TABLE todos (id TEXT PRIMARY KEY, session_id TEXT, ord INTEGER, content TEXT, status TEXT)",
                )
                st.execute(
                    "INSERT INTO sessions VALUES " +
                        "('ses_android','Android','/w',1,2,NULL,NULL,'build',NULL,'IDLE',0,0,'[]')",
                )
            }
        }

        val s = SqliteSessionStore.open(db)
        store = s
        val loaded = s.session(SessionId("ses_android"))
        assertNotNull(loaded)
        assertEquals("Android", loaded?.title)
        assertEquals(SessionState.IDLE, loaded?.state)

        val fresh = SessionId("ses_new")
        s.createSession(Session(id = fresh, cwd = "/w", createdAt = 3L, updatedAt = 3L))
        assertNotNull(s.session(fresh))
    }

    @Test
    fun pruneZeroAndNegativeKeepAreConsistent(@TempDir tmpDir: Path) = runTest {
        suspend fun seed(db: Path, count: Int): SqliteSessionStore {
            val s = SqliteSessionStore.open(db)
            repeat(count) { i ->
                val sid = SessionId("ses_$i")
                s.createSession(Session(id = sid, cwd = "/w", createdAt = i.toLong(), updatedAt = i.toLong()))
                s.appendMessage(
                    Message(
                        id = MessageId("msg_$i"),
                        sessionId = sid,
                        role = Role.USER,
                        parts = listOf(text("p_$i", "hello $i")),
                        createdAt = i.toLong(),
                    ),
                )
            }
            return s
        }

        val zero = seed(tmpDir.resolve("prune-zero.db"), 3)
        store = zero
        assertEquals(3, zero.prune(keepSessions = 0))
        assertTrue(zero.sessions(limit = 100).isEmpty())
        assertEquals(0, zero.prune(keepSessions = 0))
        zero.close()
        store = null

        val negative = seed(tmpDir.resolve("prune-negative.db"), 2)
        store = negative
        assertEquals(2, negative.prune(keepSessions = -5))
        assertTrue(negative.sessions(limit = 100).isEmpty())
        assertEquals(0, negative.prune(keepSessions = -1))
    }
}
