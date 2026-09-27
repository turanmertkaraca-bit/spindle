package dev.spindle.store.sqlite

import dev.spindle.core.model.FinishReason
import dev.spindle.core.model.Message
import dev.spindle.core.model.MessageId
import dev.spindle.core.model.Part
import dev.spindle.core.model.Role
import dev.spindle.core.model.Session
import dev.spindle.core.model.SessionId
import dev.spindle.core.model.TodoItem
import dev.spindle.core.model.TodoStatus
import dev.spindle.core.model.Usage
import dev.spindle.core.store.SessionStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

class SqliteSessionStore(private val path: Path) : SessionStore, AutoCloseable {

    private val mutex = Mutex()
    private val connection: Connection

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
    }

    private fun migrate() {
        connection.createStatement().use {
            it.execute("CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL)")
        }
        val current = connection.createStatement().use { st ->
            st.executeQuery("SELECT COALESCE(MAX(version), 0) FROM schema_version").use { rs ->
                if (rs.next()) rs.getInt(1) else 0
            }
        }
        for (migration in MIGRATIONS) {
            if (migration.version <= current) continue
            connection.autoCommit = false
            try {
                migration.apply(connection)
                connection.prepareStatement("INSERT INTO schema_version(version) VALUES (?)").use {
                    it.setInt(1, migration.version)
                    it.executeUpdate()
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

    override suspend fun createSession(session: Session) {
        mutex.withLock { upsertSession(session) }
    }

    override suspend fun updateSession(session: Session) {
        mutex.withLock { upsertSession(session) }
    }

    private fun upsertSession(session: Session) {
        connection.prepareStatement(
            "INSERT INTO sessions(id, title, cwd, created_at, updated_at, model, provider_id, agent, parent_id) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT(id) DO UPDATE SET title=excluded.title, cwd=excluded.cwd, " +
                "created_at=excluded.created_at, updated_at=excluded.updated_at, model=excluded.model, " +
                "provider_id=excluded.provider_id, agent=excluded.agent, parent_id=excluded.parent_id",
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
            st.executeUpdate()
        }
    }

    override suspend fun session(id: SessionId): Session? = mutex.withLock {
        connection.prepareStatement("SELECT * FROM sessions WHERE id = ?").use { st ->
            st.setString(1, id.value)
            st.executeQuery().use { rs -> if (rs.next()) readSession(rs) else null }
        }
    }

    override suspend fun sessions(limit: Int, includeChildren: Boolean): List<Session> = mutex.withLock {
        val sql = buildString {
            append("SELECT * FROM sessions")
            if (!includeChildren) append(" WHERE parent_id IS NULL")
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

    private fun deleteSessionRows(sessionId: String) {
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
            }
        }
    }

    override suspend fun message(sessionId: SessionId, id: MessageId): Message? = mutex.withLock {
        connection.prepareStatement("SELECT * FROM messages WHERE session_id = ? AND id = ?").use { st ->
            st.setString(1, sessionId.value)
            st.setString(2, id.value)
            st.executeQuery().use { rs ->
                if (!rs.next()) return@withLock null
                val message = readMessage(rs)
                message.copy(parts = readPartsForMessage(message.id.value))
            }
        }
    }

    override suspend fun messages(sessionId: SessionId): List<Message> = mutex.withLock {
        val partsByMessage = readPartsForSession(sessionId.value)
        connection.prepareStatement(
            "SELECT * FROM messages WHERE session_id = ? ORDER BY seq ASC",
        ).use { st ->
            st.setString(1, sessionId.value)
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

    override suspend fun latestMessage(sessionId: SessionId): Message? = mutex.withLock {
        connection.prepareStatement(
            "SELECT * FROM messages WHERE session_id = ? ORDER BY seq DESC LIMIT 1",
        ).use { st ->
            st.setString(1, sessionId.value)
            st.executeQuery().use { rs ->
                if (!rs.next()) return@withLock null
                val message = readMessage(rs)
                message.copy(parts = readPartsForMessage(message.id.value))
            }
        }
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

    override suspend fun prune(keepSessions: Int): Int = mutex.withLock {
        val drop = connection.prepareStatement(
            "SELECT id FROM sessions ORDER BY updated_at DESC LIMIT -1 OFFSET ?",
        ).use { st ->
            st.setInt(1, keepSessions.coerceAtLeast(0))
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
        }
        var removed = 0
        for (id in drop) {
            removed += deleteCount("DELETE FROM parts WHERE session_id = ?", id)
            removed += deleteCount("DELETE FROM messages WHERE session_id = ?", id)
            removed += deleteCount("DELETE FROM todos WHERE session_id = ?", id)
            removed += deleteCount("DELETE FROM sessions WHERE id = ?", id)
        }
        removed
    }

    private fun deleteCount(sql: String, sessionId: String): Int =
        connection.prepareStatement(sql).use { it.setString(1, sessionId); it.executeUpdate() }

    private fun nextSeq(sessionId: String): Int =
        connection.prepareStatement(
            "SELECT COALESCE(MAX(seq), 0) + 1 FROM messages WHERE session_id = ?",
        ).use { st ->
            st.setString(1, sessionId)
            st.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 1 }
        }

    private fun insertMessage(message: Message, seq: Int) {
        connection.prepareStatement(
            "INSERT INTO messages(id, session_id, role, created_at, model, provider_id, agent, usage, finish, error, seq) " +
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
        insertParts(message)
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
)
