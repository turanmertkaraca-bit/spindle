package dev.lumen.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
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

/**
 * A durable [SessionStore] on Android's own SQLite.
 *
 * WHY THIS EXISTS: the backend's `:store-sqlite` uses `org.xerial:sqlite-jdbc`,
 * a desktop JDBC driver whose native library is not shipped for Android — calling
 * it on device throws at startup. This implementation uses
 * `android.database.sqlite`, which is always present.
 *
 * The schema mirrors `:store-sqlite` so a future migration is mechanical.
 */
class AndroidSessionStore(context: Context) : SessionStore, AutoCloseable {

    private val db: SQLiteDatabase =
        context.applicationContext.openOrCreateDatabase("lumen.db", Context.MODE_PRIVATE, null)

    private val mutex = Mutex()

    private val json = Json {
        encodeDefaults = true
        classDiscriminator = "kind"
        serializersModule = SerializersModule {
            polymorphic(Part::class) {
                subclass(Part.Text::class)
                subclass(Part.Reasoning::class)
                subclass(Part.Tool::class)
                subclass(Part.File::class)
                subclass(Part.Step::class)
            }
        }
    }

    init {
        // PRAGMA statements return rows, so they must go through rawQuery —
        // execSQL only accepts statements that produce no result set.
        db.rawQuery("PRAGMA foreign_keys=ON", null).use { it.moveToFirst() }
        db.rawQuery("PRAGMA journal_mode=WAL", null).use { it.moveToFirst() }
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS sessions(
                 id TEXT PRIMARY KEY, title TEXT, cwd TEXT, created_at INTEGER,
                 updated_at INTEGER, model TEXT, provider_id TEXT, agent TEXT, parent_id TEXT)""",
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS messages(
                 id TEXT PRIMARY KEY, session_id TEXT, role TEXT, created_at INTEGER,
                 model TEXT, provider_id TEXT, agent TEXT, usage TEXT, finish TEXT,
                 error TEXT, seq INTEGER)""",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_msg ON messages(session_id, seq)")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS parts(
                 id TEXT PRIMARY KEY, message_id TEXT, session_id TEXT, ord INTEGER, data TEXT)""",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_part ON parts(message_id, ord)")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS todos(
                 id TEXT PRIMARY KEY, session_id TEXT, ord INTEGER, content TEXT, status TEXT)""",
        )
    }

    // ---- sessions ----

    override suspend fun createSession(session: Session) = mutex.withLock {
        db.execSQL(
            "INSERT OR REPLACE INTO sessions VALUES(?,?,?,?,?,?,?,?,?)",
            arrayOf(
                session.id.value, session.title, session.cwd, session.createdAt,
                session.updatedAt, session.model, session.providerId, session.agent,
                session.parentId?.value,
            ),
        )
    }

    override suspend fun updateSession(session: Session) = createSession(session)

    override suspend fun session(id: SessionId): Session? = mutex.withLock {
        db.rawQuery("SELECT * FROM sessions WHERE id=?", arrayOf(id.value)).use { c ->
            if (c.moveToFirst()) c.toSession() else null
        }
    }

    override suspend fun sessions(limit: Int, includeChildren: Boolean): List<Session> = mutex.withLock {
        val where = if (includeChildren) "" else "WHERE parent_id IS NULL"
        db.rawQuery("SELECT * FROM sessions $where ORDER BY updated_at DESC LIMIT ?", arrayOf(limit.toString())).use { c ->
            buildList { while (c.moveToNext()) add(c.toSession()) }
        }
    }

    override suspend fun deleteSession(id: SessionId) = mutex.withLock {
        db.delete("parts", "session_id=?", arrayOf(id.value))
        db.delete("messages", "session_id=?", arrayOf(id.value))
        db.delete("todos", "session_id=?", arrayOf(id.value))
        db.delete("sessions", "id=?", arrayOf(id.value))
        Unit
    }

    // ---- messages ----

    override suspend fun appendMessage(message: Message) = mutex.withLock {
        val seq = nextSeq(message.sessionId)
        db.execSQL(
            "INSERT OR REPLACE INTO messages VALUES(?,?,?,?,?,?,?,?,?,?,?)",
            arrayOf(
                message.id.value, message.sessionId.value, message.role.name, message.createdAt,
                message.model, message.providerId, message.agent,
                json.encodeToString(Usage.serializer(), message.usage),
                message.finish?.name, message.error, seq,
            ),
        )
        writeParts(message)
    }

    override suspend fun updateMessage(message: Message) = mutex.withLock {
        db.execSQL(
            "INSERT OR REPLACE INTO messages VALUES(?,?,?,?,?,?,?,?,?,?,COALESCE((SELECT seq FROM messages WHERE id=?),0))",
            arrayOf(
                message.id.value, message.sessionId.value, message.role.name, message.createdAt,
                message.model, message.providerId, message.agent,
                json.encodeToString(Usage.serializer(), message.usage),
                message.finish?.name, message.error, message.id.value,
            ),
        )
        writeParts(message)
    }

    private fun writeParts(message: Message) {
        db.delete("parts", "message_id=?", arrayOf(message.id.value))
        message.parts.forEachIndexed { i, p ->
            db.execSQL(
                "INSERT INTO parts VALUES(?,?,?,?,?)",
                arrayOf(p.id.value, message.id.value, message.sessionId.value, i, json.encodeToString(Part.serializer(), p)),
            )
        }
    }

    private fun nextSeq(sessionId: SessionId): Long =
        db.rawQuery("SELECT COALESCE(MAX(seq),0)+1 FROM messages WHERE session_id=?", arrayOf(sessionId.value)).use { c ->
            if (c.moveToFirst()) c.getLong(0) else 1L
        }

    override suspend fun message(sessionId: SessionId, id: MessageId): Message? = mutex.withLock {
        db.rawQuery("SELECT * FROM messages WHERE id=?", arrayOf(id.value)).use { c ->
            if (c.moveToFirst()) c.toMessage() else null
        }
    }

    override suspend fun messages(sessionId: SessionId): List<Message> = mutex.withLock {
        db.rawQuery("SELECT * FROM messages WHERE session_id=? ORDER BY seq", arrayOf(sessionId.value)).use { c ->
            buildList { while (c.moveToNext()) add(c.toMessage()) }
        }
    }

    override suspend fun latestMessage(sessionId: SessionId): Message? = mutex.withLock {
        db.rawQuery("SELECT * FROM messages WHERE session_id=? ORDER BY seq DESC LIMIT 1", arrayOf(sessionId.value)).use { c ->
            if (c.moveToFirst()) c.toMessage() else null
        }
    }

    // ---- todos ----

    override suspend fun todos(sessionId: SessionId): List<TodoItem> = mutex.withLock {
        db.rawQuery("SELECT * FROM todos WHERE session_id=? ORDER BY ord", arrayOf(sessionId.value)).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        TodoItem(
                            id = c.getString(c.getColumnIndexOrThrow("id")),
                            content = c.getString(c.getColumnIndexOrThrow("content")),
                            status = runCatching {
                                TodoStatus.valueOf(c.getString(c.getColumnIndexOrThrow("status")))
                            }.getOrDefault(TodoStatus.PENDING),
                        ),
                    )
                }
            }
        }
    }

    override suspend fun setTodos(sessionId: SessionId, todos: List<TodoItem>) = mutex.withLock {
        db.delete("todos", "session_id=?", arrayOf(sessionId.value))
        todos.forEachIndexed { i, t ->
            db.execSQL("INSERT INTO todos VALUES(?,?,?,?,?)", arrayOf(t.id, sessionId.value, i, t.content, t.status.name))
        }
    }

    override suspend fun prune(keepSessions: Int): Int = mutex.withLock {
        val keep = sessions(keepSessions, includeChildren = true).map { it.id.value }
        if (keep.isEmpty()) return@withLock 0
        val placeholders = keep.joinToString(",") { "?" }
        val gone = db.rawQuery("SELECT id FROM sessions WHERE id NOT IN ($placeholders)", keep.toTypedArray()).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
        for (id in gone) {
            db.delete("parts", "session_id=?", arrayOf(id))
            db.delete("messages", "session_id=?", arrayOf(id))
            db.delete("todos", "session_id=?", arrayOf(id))
            db.delete("sessions", "id=?", arrayOf(id))
        }
        gone.size
    }

    override fun close() {
        runCatching { db.close() }
    }

    // ---- row mappers ----

    private fun android.database.Cursor.toSession(): Session = Session(
        id = SessionId(getString(getColumnIndexOrThrow("id"))),
        title = getString(getColumnIndexOrThrow("title")) ?: "",
        cwd = getString(getColumnIndexOrThrow("cwd")) ?: "",
        createdAt = getLong(getColumnIndexOrThrow("created_at")),
        updatedAt = getLong(getColumnIndexOrThrow("updated_at")),
        model = getString(getColumnIndexOrThrow("model")),
        providerId = getString(getColumnIndexOrThrow("provider_id")),
        agent = getString(getColumnIndexOrThrow("agent")) ?: "build",
        parentId = getString(getColumnIndexOrThrow("parent_id"))?.let { SessionId(it) },
    )

    private fun android.database.Cursor.toMessage(): Message {
        val id = getString(getColumnIndexOrThrow("id"))
        val sid = getString(getColumnIndexOrThrow("session_id"))
        val usageStr = getString(getColumnIndexOrThrow("usage"))
        return Message(
            id = MessageId(id),
            sessionId = SessionId(sid),
            role = runCatching { Role.valueOf(getString(getColumnIndexOrThrow("role"))) }.getOrDefault(Role.ASSISTANT),
            parts = readParts(id),
            createdAt = getLong(getColumnIndexOrThrow("created_at")),
            model = getString(getColumnIndexOrThrow("model")),
            providerId = getString(getColumnIndexOrThrow("provider_id")),
            agent = getString(getColumnIndexOrThrow("agent")),
            usage = usageStr?.let { runCatching { json.decodeFromString(Usage.serializer(), it) }.getOrNull() } ?: Usage(),
            finish = getString(getColumnIndexOrThrow("finish"))?.let { runCatching { FinishReason.valueOf(it) }.getOrNull() },
            error = getString(getColumnIndexOrThrow("error")),
        )
    }

    private fun readParts(messageId: String): List<Part> =
        db.rawQuery("SELECT data FROM parts WHERE message_id=? ORDER BY ord", arrayOf(messageId)).use { c ->
            buildList {
                while (c.moveToNext()) {
                    runCatching { json.decodeFromString(Part.serializer(), c.getString(0)) }.getOrNull()?.let { add(it) }
                }
            }
        }
}
