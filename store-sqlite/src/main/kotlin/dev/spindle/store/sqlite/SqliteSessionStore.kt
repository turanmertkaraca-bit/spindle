package dev.spindle.store.sqlite

import dev.spindle.core.model.FinishReason
import dev.spindle.core.model.Ids
import dev.spindle.core.model.Message
import dev.spindle.core.model.MessageId
import dev.spindle.core.model.Part
import dev.spindle.core.model.Role
import dev.spindle.core.model.Session
import dev.spindle.core.model.SessionId
import dev.spindle.core.model.SessionState
import dev.spindle.core.model.TodoItem
import dev.spindle.core.model.TodoStatus
import dev.spindle.core.model.Usage
import dev.spindle.core.store.SearchHit
import dev.spindle.core.store.SessionSearch
import dev.spindle.core.store.SessionStore
import dev.spindle.core.store.messageSearchText
import dev.spindle.core.store.searchSnippet
import dev.spindle.core.store.withNewId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

class SqliteSessionStore(private val path: Path) : SessionStore, SessionSearch, AutoCloseable {

    private val mutex = Mutex()
    private val connection: Connection
    private val tagsSerializer = ListSerializer(String.serializer())

    /**
     * Whether the FTS5 `message_fts` index exists and is usable. FTS5 is
     * optional; when it is unavailable (or a write fails) [search] falls back to
     * a linear scan so results are never silently dropped.
     */
    private var ftsReady = false

    private val json = Json {
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

    init {
        path.toAbsolutePath().parent?.let { Files.createDirectories(it) }
        connection = DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}")
        connection.createStatement().use { st ->
            st.execute("PRAGMA journal_mode=WAL")
            st.execute("PRAGMA foreign_keys=ON")
        }
        migrate()
        setupFts()
        backfillSearchIndex()
    }

    private fun migrate() {
        connection.createStatement().use {
            it.execute("CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL)")
        }
        // `PRAGMA user_version` is the durable source of truth (SPEC §5.1); the
        // `schema_version` table is kept for compatibility with older databases.
        // Taking the max lets either location record progress and makes a
        // database written by another host (e.g. the Android store, which keeps
        // neither) simply start at 0 and replay the idempotent migrations.
        val current = maxOf(
            connection.createStatement().use { st ->
                st.executeQuery("SELECT COALESCE(MAX(version), 0) FROM schema_version").use { rs ->
                    if (rs.next()) rs.getInt(1) else 0
                }
            },
            userVersion(),
        )
        for (migration in MIGRATIONS) {
            if (migration.version <= current) continue
            connection.autoCommit = false
            try {
                migration.apply(connection)
                connection.prepareStatement("INSERT INTO schema_version(version) VALUES (?)").use {
                    it.setInt(1, migration.version)
                    it.executeUpdate()
                }
                connection.createStatement().use { it.execute("PRAGMA user_version = ${migration.version}") }
                connection.commit()
            } catch (t: Throwable) {
                connection.rollback()
                throw t
            } finally {
                connection.autoCommit = true
            }
        }
    }

    private fun userVersion(): Int = connection.createStatement().use { st ->
        st.executeQuery("PRAGMA user_version").use { rs -> if (rs.next()) rs.getInt(1) else 0 }
    }

    /**
     * Create (or detect) the optional FTS5 index. Android's bundled SQLite may
     * lack FTS5, and the index can be dropped by an external writer, so this is
     * best-effort: a failure leaves [ftsReady] false and [search] on the scan.
     */
    private fun setupFts() {
        ftsReady = try {
            connection.createStatement().use {
                it.execute(
                    "CREATE VIRTUAL TABLE IF NOT EXISTS message_fts USING fts5(" +
                        "message_id UNINDEXED, session_id UNINDEXED, role UNINDEXED, body)",
                )
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    private inline fun <T> inTransaction(block: () -> T): T {
        connection.autoCommit = false
        return try {
            val result = block()
            connection.commit()
            result
        } catch (t: Throwable) {
            connection.rollback()
            throw t
        } finally {
            connection.autoCommit = true
        }
    }

    override suspend fun createSession(session: Session) {
        mutex.withLock { upsertSession(session) }
    }

    override suspend fun updateSession(session: Session) {
        mutex.withLock { upsertSession(session) }
    }

    private fun upsertSession(session: Session) {
        connection.prepareStatement(
            "INSERT INTO sessions(id, title, cwd, created_at, updated_at, model, provider_id, agent, parent_id, " +
                "state, pinned, archived, tags) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT(id) DO UPDATE SET title=excluded.title, cwd=excluded.cwd, " +
                "created_at=excluded.created_at, updated_at=excluded.updated_at, model=excluded.model, " +
                "provider_id=excluded.provider_id, agent=excluded.agent, parent_id=excluded.parent_id, " +
                "state=excluded.state, pinned=excluded.pinned, archived=excluded.archived, tags=excluded.tags",
        ).use { st ->
            st.setString(1, session.id.value)
            st.setString(2, session.title)
            st.setString(3, session.cwd)
            st.setLong(4, session.createdAt)
            st.setLong(5, session.updatedAt)
            st.setString(6, session.model)
            st.setString(7, session.providerId)
            st.setString(8, session.agent)
            st.setString(9, session.parentId?.value)
            st.setString(10, session.state.name)
            st.setInt(11, if (session.pinned) 1 else 0)
            st.setInt(12, if (session.archived) 1 else 0)
            st.setString(13, json.encodeToString(tagsSerializer, session.tags))
            st.executeUpdate()
        }
    }

    override suspend fun session(id: SessionId): Session? = mutex.withLock { readSessionRow(id.value) }

    override suspend fun sessions(
        limit: Int,
        includeChildren: Boolean,
        includeArchived: Boolean,
    ): List<Session> = mutex.withLock {
        val where = buildList {
            if (!includeChildren) add("parent_id IS NULL")
            if (!includeArchived) add("(archived IS NULL OR archived = 0)")
        }
        val sql = buildString {
            append("SELECT * FROM sessions")
            if (where.isNotEmpty()) append(" WHERE ").append(where.joinToString(" AND "))
            append(" ORDER BY updated_at DESC LIMIT ?")
        }
        connection.prepareStatement(sql).use { st ->
            st.setInt(1, limit)
            st.executeQuery().use { rs ->
                buildList { while (rs.next()) add(readSession(rs)) }
            }
        }
    }

    override suspend fun deleteSession(id: SessionId) {
        mutex.withLock { deleteSessionRows(id.value) }
    }

    private fun deleteSessionRows(sessionId: String) = inTransaction { removeSessionRows(sessionId) }

    private fun removeSessionRows(sessionId: String) {
        deleteFtsSession(sessionId)
        connection.prepareStatement("DELETE FROM parts WHERE session_id = ?").use {
            it.setString(1, sessionId); it.executeUpdate()
        }
        connection.prepareStatement("DELETE FROM messages WHERE session_id = ?").use {
            it.setString(1, sessionId); it.executeUpdate()
        }
        connection.prepareStatement("DELETE FROM todos WHERE session_id = ?").use {
            it.setString(1, sessionId); it.executeUpdate()
        }
        connection.prepareStatement("DELETE FROM sessions WHERE id = ?").use {
            it.setString(1, sessionId); it.executeUpdate()
        }
    }

    override suspend fun forkSession(
        sourceId: SessionId,
        atMessageId: MessageId?,
        newId: SessionId,
    ): Session? = mutex.withLock {
        val source = readSessionRow(sourceId.value) ?: return@withLock null
        val sourceMessages = readMessagesForSession(sourceId.value)
        val upTo = if (atMessageId == null) {
            sourceMessages
        } else {
            val index = sourceMessages.indexOfFirst { it.id == atMessageId }
            if (index < 0) return@withLock null
            sourceMessages.subList(0, index + 1)
        }
        val now = System.currentTimeMillis()
        val fork = source.copy(
            id = newId,
            parentId = sourceId,
            createdAt = now,
            updatedAt = now,
            archived = false,
        )
        connection.autoCommit = false
        try {
            upsertSession(fork)
            for (message in upTo) {
                val copy = message.copy(
                    id = MessageId(Ids.new("msg")),
                    sessionId = newId,
                    parts = message.parts.map { it.withNewId() },
                )
                insertMessage(copy, nextSeq(newId.value))
            }
            connection.commit()
        } catch (t: Throwable) {
            connection.rollback()
            throw t
        } finally {
            connection.autoCommit = true
        }
        fork
    }

    override suspend fun rewind(sessionId: SessionId, toMessageId: MessageId): Int = mutex.withLock {
        val seq = connection.prepareStatement(
            "SELECT seq FROM messages WHERE session_id = ? AND id = ?",
        ).use { st ->
            st.setString(1, sessionId.value)
            st.setString(2, toMessageId.value)
            st.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else null }
        } ?: return@withLock 0
        val dropped = connection.prepareStatement(
            "SELECT id FROM messages WHERE session_id = ? AND seq > ?",
        ).use { st ->
            st.setString(1, sessionId.value)
            st.setInt(2, seq)
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
        }
        if (dropped.isEmpty()) return@withLock 0
        connection.autoCommit = false
        try {
            for (messageId in dropped) {
                deleteParts(messageId)
                deleteFtsMessage(messageId)
            }
            connection.prepareStatement("DELETE FROM messages WHERE session_id = ? AND seq > ?").use {
                it.setString(1, sessionId.value)
                it.setInt(2, seq)
                it.executeUpdate()
            }
            connection.commit()
        } catch (t: Throwable) {
            connection.rollback()
            throw t
        } finally {
            connection.autoCommit = true
        }
        dropped.size
    }

    /**
     * Append a message. Idempotent on the message id: re-appending an existing
     * id is a no-op (`INSERT OR IGNORE`) rather than a replace or a duplicate.
     *
     * NOTE: the Android store (`AndroidSessionStore`) uses `INSERT OR REPLACE`
     * and `InMemorySessionStore` appends duplicates. This JVM store is the
     * documented reference; the others are owned elsewhere and not changed here.
     */
    override suspend fun appendMessage(message: Message) {
        mutex.withLock {
            insertMessage(message, nextSeq(message.sessionId.value))
        }
    }

    override suspend fun updateMessage(message: Message) {
        mutex.withLock {
            val updated = connection.prepareStatement(
                "UPDATE messages SET role=?, created_at=?, model=?, provider_id=?, agent=?, " +
                    "usage=?, finish=?, error=? WHERE id=? AND session_id=?",
            ).use { st ->
                st.setString(1, message.role.name)
                st.setLong(2, message.createdAt)
                st.setString(3, message.model)
                st.setString(4, message.providerId)
                st.setString(5, message.agent)
                st.setString(6, json.encodeToString(Usage.serializer(), message.usage))
                st.setString(7, message.finish?.name)
                st.setString(8, message.error)
                st.setString(9, message.id.value)
                st.setString(10, message.sessionId.value)
                st.executeUpdate()
            }
            if (updated == 0) {
                insertMessage(message, nextSeq(message.sessionId.value))
            } else {
                deleteParts(message.id.value)
                insertParts(message)
                deleteFtsMessage(message.id.value)
                insertFts(message)
            }
        }
    }

    override suspend fun message(sessionId: SessionId, id: MessageId): Message? = mutex.withLock {
        readMessageRow(sessionId.value, id.value)
    }

    override suspend fun messages(sessionId: SessionId): List<Message> = mutex.withLock {
        readMessagesForSession(sessionId.value)
    }

    override suspend fun latestMessage(sessionId: SessionId): Message? = mutex.withLock {
        val id = connection.prepareStatement(
            "SELECT id FROM messages WHERE session_id = ? ORDER BY seq DESC LIMIT 1",
        ).use { st ->
            st.setString(1, sessionId.value)
            st.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        } ?: return@withLock null
        readMessageRow(sessionId.value, id)
    }

    override suspend fun search(query: String, limit: Int): List<SearchHit> = mutex.withLock {
        val needle = query.trim()
        if (needle.isEmpty()) return@withLock emptyList()
        if (ftsReady) {
            // null means the index could not answer (unavailable/stale); only a
            // genuine no-match returns an empty list.
            ftsSearch(needle, limit)?.let { return@withLock it }
        }
        scanSearch(needle, limit)
    }

    /**
     * FTS5 MATCH search. Returns null when the index cannot answer, so [search]
     * can fall back to [scanSearch]; an empty list is a real "no hits".
     */
    private fun ftsSearch(needle: String, limit: Int): List<SearchHit>? {
        val match = ftsQuery(needle) ?: return emptyList()
        val sql = "SELECT message_fts.message_id AS message_id, message_fts.session_id AS session_id, " +
            "message_fts.role AS role, snippet(message_fts, 3, '[', ']', '…', 12) AS snippet, " +
            "m.created_at AS created_at FROM message_fts " +
            "JOIN messages m ON m.id = message_fts.message_id " +
            "WHERE message_fts MATCH ? ORDER BY bm25(message_fts) LIMIT ?"
        return try {
            connection.prepareStatement(sql).use { st ->
                st.setString(1, match)
                st.setInt(2, limit)
                st.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                SearchHit(
                                    sessionId = SessionId(rs.getString("session_id")),
                                    messageId = rs.getString("message_id"),
                                    role = rs.getString("role"),
                                    snippet = rs.getString("snippet") ?: "",
                                    at = rs.getLong("created_at"),
                                ),
                            )
                        }
                    }
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    /** Linear fallback used when the FTS index is unavailable or unusable. */
    private fun scanSearch(needle: String, limit: Int): List<SearchHit> {
        // Materialize the rows first: loading parts issues a second query, and
        // running it while the outer cursor is open is not portable.
        val messages = connection.prepareStatement("SELECT * FROM messages ORDER BY created_at DESC").use { st ->
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(readMessage(rs)) } }
        }
        val hits = ArrayList<SearchHit>()
        for (message in messages) {
            if (hits.size >= limit) break
            val text = messageSearchText(withParts(message))
            if (text.contains(needle, ignoreCase = true)) {
                hits += SearchHit(
                    sessionId = message.sessionId,
                    messageId = message.id.value,
                    role = message.role.name,
                    snippet = searchSnippet(text, needle),
                    at = message.createdAt,
                )
            }
        }
        return hits
    }

    override suspend fun todos(sessionId: SessionId): List<TodoItem> = mutex.withLock {
        connection.prepareStatement(
            "SELECT id, content, status FROM todos WHERE session_id = ? ORDER BY ord ASC",
        ).use { st ->
            st.setString(1, sessionId.value)
            st.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            TodoItem(
                                id = rs.getString("id"),
                                content = rs.getString("content"),
                                status = TodoStatus.valueOf(rs.getString("status")),
                            ),
                        )
                    }
                }
            }
        }
    }

    override suspend fun setTodos(sessionId: SessionId, todos: List<TodoItem>) {
        mutex.withLock {
            connection.autoCommit = false
            try {
                connection.prepareStatement("DELETE FROM todos WHERE session_id = ?").use {
                    it.setString(1, sessionId.value); it.executeUpdate()
                }
                connection.prepareStatement(
                    "INSERT INTO todos(id, session_id, ord, content, status) VALUES (?, ?, ?, ?, ?)",
                ).use { st ->
                    todos.forEachIndexed { index, todo ->
                        st.setString(1, todo.id)
                        st.setString(2, sessionId.value)
                        st.setInt(3, index)
                        st.setString(4, todo.content)
                        st.setString(5, todo.status.name)
                        st.addBatch()
                    }
                    st.executeBatch()
                }
                connection.commit()
            } catch (t: Throwable) {
                connection.rollback()
                throw t
            } finally {
                connection.autoCommit = true
            }
        }
    }

    /**
     * Drop whole sessions beyond the [keepSessions] most recently updated, plus
     * their messages/parts/todos. Negative values behave like zero (drop all).
     * Returns the number of *sessions* removed, matching SPEC §5 and the
     * in-memory/Android implementations.
     */
    override suspend fun prune(keepSessions: Int): Int = mutex.withLock {
        val keep = keepSessions.coerceAtLeast(0)
        val drop = connection.prepareStatement(
            "SELECT id FROM sessions ORDER BY updated_at DESC LIMIT -1 OFFSET ?",
        ).use { st ->
            st.setInt(1, keep)
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
        }
        inTransaction { drop.forEach { removeSessionRows(it) } }
        drop.size
    }

    private fun nextSeq(sessionId: String): Int =
        connection.prepareStatement(
            "SELECT COALESCE(MAX(seq), 0) + 1 FROM messages WHERE session_id = ?",
        ).use { st ->
            st.setString(1, sessionId)
            st.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 1 }
        }

    private fun insertMessage(message: Message, seq: Int) {
        val inserted = connection.prepareStatement(
            "INSERT OR IGNORE INTO messages(id, session_id, role, created_at, model, provider_id, agent, usage, finish, error, seq) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        ).use { st ->
            st.setString(1, message.id.value)
            st.setString(2, message.sessionId.value)
            st.setString(3, message.role.name)
            st.setLong(4, message.createdAt)
            st.setString(5, message.model)
            st.setString(6, message.providerId)
            st.setString(7, message.agent)
            st.setString(8, json.encodeToString(Usage.serializer(), message.usage))
            st.setString(9, message.finish?.name)
            st.setString(10, message.error)
            st.setInt(11, seq)
            st.executeUpdate()
        }
        // Ignored duplicate: leave the existing row, parts and index untouched.
        if (inserted == 0) return
        insertParts(message)
        insertFts(message)
    }

    private fun insertParts(message: Message) {
        connection.prepareStatement(
            "INSERT INTO parts(id, message_id, session_id, ord, data) VALUES (?, ?, ?, ?, ?)",
        ).use { st ->
            message.parts.forEachIndexed { index, part ->
                st.setString(1, part.id.value)
                st.setString(2, message.id.value)
                st.setString(3, message.sessionId.value)
                st.setInt(4, index)
                st.setString(5, json.encodeToString(Part.serializer(), part))
                st.addBatch()
            }
            st.executeBatch()
        }
    }

    private fun deleteParts(messageId: String) {
        connection.prepareStatement("DELETE FROM parts WHERE message_id = ?").use {
            it.setString(1, messageId); it.executeUpdate()
        }
    }

    private fun insertFts(message: Message) {
        if (!ftsReady) return
        val body = messageSearchText(message)
        if (body.isBlank()) return
        try {
            connection.prepareStatement(
                "INSERT INTO message_fts(message_id, session_id, role, body) VALUES (?, ?, ?, ?)",
            ).use { st ->
                st.setString(1, message.id.value)
                st.setString(2, message.sessionId.value)
                st.setString(3, message.role.name)
                st.setString(4, body)
                st.executeUpdate()
            }
        } catch (_: Throwable) {
            // The index is optional: disable it so search falls back to a scan.
            ftsReady = false
        }
    }

    private fun deleteFtsMessage(messageId: String) {
        if (!ftsReady) return
        try {
            connection.prepareStatement("DELETE FROM message_fts WHERE message_id = ?").use {
                it.setString(1, messageId); it.executeUpdate()
            }
        } catch (_: Throwable) {
            ftsReady = false
        }
    }

    private fun deleteFtsSession(sessionId: String) {
        if (!ftsReady) return
        try {
            connection.prepareStatement("DELETE FROM message_fts WHERE session_id = ?").use {
                it.setString(1, sessionId); it.executeUpdate()
            }
        } catch (_: Throwable) {
            ftsReady = false
        }
    }

    /**
     * Rebuild the index when it does not cover every searchable message.
     *
     * The old heuristic (any row implies complete) never repaired a partial or
     * stale index. Comparing the number of indexed rows with the number of
     * messages that actually produce indexable text repairs both: an empty
     * index, a partially deleted one, and one left behind by an older write.
     */
    private fun backfillSearchIndex() {
        if (!ftsReady) return
        val messageCount = countRows("messages")
        val indexed = countRows("message_fts")
        // Fast path: every message is indexed (the common case).
        if (indexed == messageCount) return
        // Slow path: messages without searchable text are legitimately absent,
        // so only rebuild when the index does not cover the searchable subset.
        val messages = connection.createStatement().use { st ->
            st.executeQuery("SELECT * FROM messages").use { rs ->
                buildList { while (rs.next()) add(withParts(readMessage(rs))) }
            }
        }
        val searchable = messages.filter { messageSearchText(it).isNotBlank() }
        if (indexed == searchable.size) return
        try {
            connection.createStatement().use { it.executeUpdate("DELETE FROM message_fts") }
            for (message in searchable) insertFts(message)
        } catch (_: Throwable) {
            ftsReady = false
        }
    }

    private fun countRows(table: String): Int =
        connection.createStatement().use { st ->
            st.executeQuery("SELECT COUNT(*) FROM $table").use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }

    private fun ftsQuery(query: String): String? {
        val tokens = query.lowercase()
            .split(Regex("[^\\p{L}\\p{N}_]+"))
            .filter { it.isNotBlank() }
        if (tokens.isEmpty()) return null
        return tokens.joinToString(" ") { "$it*" }
    }

    private fun readSessionRow(id: String): Session? =
        connection.prepareStatement("SELECT * FROM sessions WHERE id = ?").use { st ->
            st.setString(1, id)
            st.executeQuery().use { rs -> if (rs.next()) readSession(rs) else null }
        }

    private fun readMessageRow(sessionId: String, id: String): Message? =
        connection.prepareStatement("SELECT * FROM messages WHERE session_id = ? AND id = ?").use { st ->
            st.setString(1, sessionId)
            st.setString(2, id)
            st.executeQuery().use { rs ->
                if (!rs.next()) null
                else withParts(readMessage(rs))
            }
        }

    private fun withParts(message: Message): Message =
        message.copy(parts = readPartsForMessage(message.id.value))

    private fun readMessagesForSession(sessionId: String): List<Message> {
        val partsByMessage = readPartsForSession(sessionId)
        return connection.prepareStatement(
            "SELECT * FROM messages WHERE session_id = ? ORDER BY seq ASC",
        ).use { st ->
            st.setString(1, sessionId)
            st.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        val message = readMessage(rs)
                        add(message.copy(parts = partsByMessage[message.id.value].orEmpty()))
                    }
                }
            }
        }
    }

    private fun readPartsForMessage(messageId: String): List<Part> =
        connection.prepareStatement(
            "SELECT data FROM parts WHERE message_id = ? ORDER BY ord ASC",
        ).use { st ->
            st.setString(1, messageId)
            st.executeQuery().use { rs ->
                buildList { while (rs.next()) add(json.decodeFromString(Part.serializer(), rs.getString(1))) }
            }
        }

    private fun readPartsForSession(sessionId: String): Map<String, List<Part>> {
        val map = LinkedHashMap<String, MutableList<Part>>()
        connection.prepareStatement(
            "SELECT message_id, data FROM parts WHERE session_id = ? ORDER BY message_id ASC, ord ASC",
        ).use { st ->
            st.setString(1, sessionId)
            st.executeQuery().use { rs ->
                while (rs.next()) {
                    map.getOrPut(rs.getString("message_id")) { mutableListOf() }
                        .add(json.decodeFromString(Part.serializer(), rs.getString("data")))
                }
            }
        }
        return map
    }

    private fun readSession(rs: ResultSet) = Session(
        id = SessionId(rs.getString("id")),
        title = rs.getString("title") ?: "",
        cwd = rs.getString("cwd"),
        createdAt = rs.getLong("created_at"),
        updatedAt = rs.getLong("updated_at"),
        model = rs.getString("model"),
        providerId = rs.getString("provider_id"),
        agent = rs.getString("agent") ?: "build",
        parentId = rs.getString("parent_id")?.let { SessionId(it) },
        state = rs.getString("state")?.let { runCatching { SessionState.valueOf(it) }.getOrNull() }
            ?: SessionState.IDLE,
        pinned = rs.getInt("pinned") != 0,
        archived = rs.getInt("archived") != 0,
        tags = rs.getString("tags")?.let {
            runCatching { json.decodeFromString(tagsSerializer, it) }.getOrNull()
        } ?: emptyList(),
    )

    private fun readMessage(rs: ResultSet) = Message(
        id = MessageId(rs.getString("id")),
        sessionId = SessionId(rs.getString("session_id")),
        role = Role.valueOf(rs.getString("role")),
        parts = emptyList(),
        createdAt = rs.getLong("created_at"),
        model = rs.getString("model"),
        providerId = rs.getString("provider_id"),
        agent = rs.getString("agent"),
        usage = rs.getString("usage")?.let { json.decodeFromString(Usage.serializer(), it) } ?: Usage(),
        finish = rs.getString("finish")?.let { FinishReason.valueOf(it) },
        error = rs.getString("error"),
    )

    override fun close() {
        connection.createStatement().use { it.execute("PRAGMA wal_checkpoint(TRUNCATE)") }
        connection.close()
    }

    companion object {
        fun open(dbFile: Path): SqliteSessionStore = SqliteSessionStore(dbFile)
    }
}

private class Migration(val version: Int, val apply: (Connection) -> Unit)

private fun tableColumns(connection: Connection, table: String): Set<String> =
    connection.createStatement().use { st ->
        st.executeQuery("PRAGMA table_info($table)").use { rs ->
            buildSet {
                val name = rs.findColumn("name")
                while (rs.next()) add(rs.getString(name))
            }
        }
    }

private fun addColumnIfMissing(connection: Connection, table: String, name: String, ddl: String) {
    if (name !in tableColumns(connection, table)) {
        connection.createStatement().use { it.execute("ALTER TABLE $table ADD COLUMN $name $ddl") }
    }
}

private val MIGRATIONS = listOf(
    Migration(1) { connection ->
        connection.createStatement().use { st ->
            st.execute(
                "CREATE TABLE IF NOT EXISTS sessions (" +
                    "id TEXT PRIMARY KEY, " +
                    "title TEXT, " +
                    "cwd TEXT, " +
                    "created_at INTEGER, " +
                    "updated_at INTEGER, " +
                    "model TEXT, " +
                    "provider_id TEXT, " +
                    "agent TEXT, " +
                    "parent_id TEXT)",
            )
            st.execute(
                "CREATE TABLE IF NOT EXISTS messages (" +
                    "id TEXT PRIMARY KEY, " +
                    "session_id TEXT, " +
                    "role TEXT, " +
                    "created_at INTEGER, " +
                    "model TEXT, " +
                    "provider_id TEXT, " +
                    "agent TEXT, " +
                    "usage TEXT, " +
                    "finish TEXT, " +
                    "error TEXT, " +
                    "seq INTEGER)",
            )
            st.execute("CREATE INDEX IF NOT EXISTS idx_messages_session_seq ON messages(session_id, seq)")
            st.execute(
                "CREATE TABLE IF NOT EXISTS parts (" +
                    "id TEXT PRIMARY KEY, " +
                    "message_id TEXT, " +
                    "session_id TEXT, " +
                    "ord INTEGER, " +
                    "data TEXT)",
            )
            st.execute("CREATE INDEX IF NOT EXISTS idx_parts_message_ord ON parts(message_id, ord)")
            st.execute(
                "CREATE TABLE IF NOT EXISTS todos (" +
                    "id TEXT PRIMARY KEY, " +
                    "session_id TEXT, " +
                    "ord INTEGER, " +
                    "content TEXT, " +
                    "status TEXT)",
            )
            st.execute("CREATE INDEX IF NOT EXISTS idx_todos_session_ord ON todos(session_id, ord)")
        }
    },
    Migration(2) { connection ->
        // Idempotent: a database written by another host (e.g. the Android
        // store, which creates these columns up front) may already have them,
        // so check `PRAGMA table_info` before every ALTER. The FTS index is
        // created separately in `setupFts()` so its absence never fails a
        // migration.
        addColumnIfMissing(connection, "sessions", "state", "TEXT NOT NULL DEFAULT 'IDLE'")
        addColumnIfMissing(connection, "sessions", "pinned", "INTEGER NOT NULL DEFAULT 0")
        addColumnIfMissing(connection, "sessions", "archived", "INTEGER NOT NULL DEFAULT 0")
        addColumnIfMissing(connection, "sessions", "tags", "TEXT NOT NULL DEFAULT '[]'")
    },
)
