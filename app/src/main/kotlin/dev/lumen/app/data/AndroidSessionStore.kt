package dev.lumen.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
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

/**
 * Escape an FTS5 MATCH query the same way for the index and for any test:
 * lowercase, split on non-word characters, and prefix-match each token
 * (`foo bar` -> `foo* bar*`). Returns null when there is nothing to match.
 */
internal fun ftsMatch(query: String): String? {
    val tokens = query.lowercase()
        .split(Regex("[^\\p{L}\\p{N}_]+"))
        .filter { it.isNotBlank() }
    if (tokens.isEmpty()) return null
    return tokens.joinToString(" ") { "$it*" }
}

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
class AndroidSessionStore(context: Context) : SessionStore, SessionSearch, AutoCloseable {

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
                 updated_at INTEGER, model TEXT, provider_id TEXT, agent TEXT, parent_id TEXT,
                 state TEXT, pinned INTEGER, archived INTEGER, tags TEXT)""",
        )
        migrateSessions()
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
        setupFts()
    }

    /**
     * A best-effort FTS5 index over message text. Android's bundled SQLite may
     * not have FTS5 (it historically shipped FTS3/4), so this is entirely
     * optional: if the virtual table cannot be created we stay on the linear
     * scan and search still works. When the index exists it is kept in sync and
     * backfilled once.
     */
    private var ftsReady = false

    private fun setupFts() {
        try {
            db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS message_fts USING fts5(message_id UNINDEXED, session_id UNINDEXED, role UNINDEXED, body)")
            ftsReady = true
        } catch (_: Throwable) {
            ftsReady = false
            return
        }
        try {
            val indexed = db.rawQuery("SELECT COUNT(*) FROM message_fts", null).use { c ->
                if (c.moveToFirst()) c.getInt(0) else 0
            }
            if (indexed == 0) backfillFts()
        } catch (_: Throwable) {
            // A backfill failure is non-fatal; insert/delete keep it in sync going forward.
        }
    }

    private fun backfillFts() {
        val ids = db.rawQuery("SELECT id FROM messages", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
        for (id in ids) {
            ftsInsert(id)
        }
    }

    private fun ftsRow(message: Message) {
        if (!ftsReady) return
        runCatching {
            db.delete("message_fts", "message_id=?", arrayOf(message.id.value))
            val body = messageSearchText(message)
            if (body.isNotBlank()) {
                db.execSQL(
                    "INSERT INTO message_fts(message_id, session_id, role, body) VALUES(?,?,?,?)",
                    arrayOf(message.id.value, message.sessionId.value, message.role.name, body),
                )
            }
        }
    }

    private fun ftsDeleteMessage(messageId: String) {
        if (!ftsReady) return
        runCatching { db.delete("message_fts", "message_id=?", arrayOf(messageId)) }
    }

    private fun ftsDeleteSession(sessionId: String) {
        if (!ftsReady) return
        runCatching { db.delete("message_fts", "session_id=?", arrayOf(sessionId)) }
    }

    /** Add the columns introduced after the first schema, so old DBs migrate forward. */
    private fun migrateSessions() {
        val existing = columnNames("sessions")
        fun add(name: String, ddl: String) {
            if (name !in existing) db.execSQL("ALTER TABLE sessions ADD COLUMN $name $ddl")
        }
        add("state", "TEXT")
        add("pinned", "INTEGER")
        add("archived", "INTEGER")
        add("tags", "TEXT")
    }

    private fun columnNames(table: String): Set<String> =
        db.rawQuery("PRAGMA table_info($table)", null).use { c ->
            buildSet {
                val name = c.getColumnIndexOrThrow("name")
                while (c.moveToNext()) add(c.getString(name))
            }
        }

    // ---- sessions ----

    override suspend fun createSession(session: Session) = mutex.withLock { writeSession(session) }

    private fun writeSession(session: Session) {
        db.execSQL(
            """INSERT OR REPLACE INTO sessions
               (id,title,cwd,created_at,updated_at,model,provider_id,agent,parent_id,state,pinned,archived,tags)
               VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)""",
            arrayOf(
                session.id.value, session.title, session.cwd, session.createdAt,
                session.updatedAt, session.model, session.providerId, session.agent,
                session.parentId?.value, session.state.name,
                if (session.pinned) 1 else 0, if (session.archived) 1 else 0,
                json.encodeToString(ListSerializer(String.serializer()), session.tags),
            ),
        )
    }

    override suspend fun updateSession(session: Session) = createSession(session)

    override suspend fun session(id: SessionId): Session? = mutex.withLock { sessionUnlocked(id) }

    private fun sessionUnlocked(id: SessionId): Session? =
        db.rawQuery("SELECT * FROM sessions WHERE id=?", arrayOf(id.value)).use { c ->
            if (c.moveToFirst()) c.toSession() else null
        }

    override suspend fun sessions(
        limit: Int,
        includeChildren: Boolean,
        includeArchived: Boolean,
    ): List<Session> = mutex.withLock { sessionsUnlocked(limit, includeChildren, includeArchived) }

    private fun sessionsUnlocked(limit: Int, includeChildren: Boolean, includeArchived: Boolean): List<Session> {
        val where = buildList {
            if (!includeChildren) add("parent_id IS NULL")
            if (!includeArchived) add("(archived IS NULL OR archived=0)")
        }.joinToString(" AND ").let { if (it.isBlank()) "" else "WHERE $it" }
        return db.rawQuery(
            "SELECT * FROM sessions $where ORDER BY updated_at DESC LIMIT ?",
            arrayOf(limit.toString()),
        ).use { c -> buildList { while (c.moveToNext()) add(c.toSession()) } }
    }

    override suspend fun deleteSession(id: SessionId) = mutex.withLock { deleteSessionUnlocked(id) }

    private fun deleteSessionUnlocked(id: SessionId) {
        db.delete("parts", "session_id=?", arrayOf(id.value))
        db.delete("messages", "session_id=?", arrayOf(id.value))
        db.delete("todos", "session_id=?", arrayOf(id.value))
        ftsDeleteSession(id.value)
        db.delete("sessions", "id=?", arrayOf(id.value))
    }

    override suspend fun forkSession(
        sourceId: SessionId,
        atMessageId: MessageId?,
        newId: SessionId,
    ): Session? = mutex.withLock {
        val source = sessionUnlocked(sourceId) ?: return@withLock null
        val sourceMessages = messagesUnlocked(sourceId)
        val upTo = if (atMessageId == null) {
            sourceMessages.toList()
        } else {
            val index = sourceMessages.indexOfFirst { it.id == atMessageId }
            if (index < 0) return@withLock null
            sourceMessages.subList(0, index + 1).toList()
        }
        val now = System.currentTimeMillis()
        val fork = source.copy(
            id = newId,
            parentId = sourceId,
            createdAt = now,
            updatedAt = now,
            archived = false,
            pinned = false,
        )
        writeSession(fork)
        for (m in upTo) {
            insertMessage(m.copy(id = MessageId(Ids.new("msg")), sessionId = newId, parts = m.parts.map { it.withNewId() }))
        }
        fork
    }

    override suspend fun rewind(sessionId: SessionId, toMessageId: MessageId): Int = mutex.withLock {
        val list = messagesUnlocked(sessionId)
        val index = list.indexOfFirst { it.id == toMessageId }
        if (index < 0) return@withLock 0
        val tail = list.subList(index + 1, list.size)
        for (m in tail) {
            db.delete("parts", "message_id=?", arrayOf(m.id.value))
            db.delete("messages", "id=?", arrayOf(m.id.value))
            ftsDeleteMessage(m.id.value)
        }
        tail.size
    }

    // ---- messages ----

    override suspend fun appendMessage(message: Message) = mutex.withLock { insertMessage(message) }

    private fun insertMessage(message: Message) {
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
        ftsRow(message)
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
        ftsRow(message)
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

    override suspend fun messages(sessionId: SessionId): List<Message> = mutex.withLock { messagesUnlocked(sessionId) }

    private fun messagesUnlocked(sessionId: SessionId): List<Message> =
        db.rawQuery("SELECT * FROM messages WHERE session_id=? ORDER BY seq", arrayOf(sessionId.value)).use { c ->
            buildList { while (c.moveToNext()) add(c.toMessage()) }
        }

    override suspend fun latestMessage(sessionId: SessionId): Message? = mutex.withLock {
        db.rawQuery("SELECT * FROM messages WHERE session_id=? ORDER BY seq DESC LIMIT 1", arrayOf(sessionId.value)).use { c ->
            if (c.moveToFirst()) c.toMessage() else null
        }
    }

    // ---- search ----

    override suspend fun search(query: String, limit: Int): List<SearchHit> = mutex.withLock {
        val needle = query.trim()
        if (needle.isEmpty()) return@withLock emptyList()
        if (ftsReady) {
            ftsSearch(needle, limit)?.let { return@withLock it }
        }
        scanSearch(needle, limit)
    }

    /**
     * FTS5 MATCH search. Returns null when the index cannot answer (so the
     * caller falls back to the scan); an empty list is a real "no hits".
     */
    private fun ftsSearch(needle: String, limit: Int): List<SearchHit>? {
        val match = ftsMatch(needle) ?: return emptyList()
        return try {
            db.rawQuery(
                "SELECT f.message_id AS mid, f.session_id AS sid, f.role AS role, " +
                    "snippet(message_fts, 3, '[', ']', '…', 12) AS snip, m.created_at AS at " +
                    "FROM message_fts f JOIN messages m ON m.id = f.message_id " +
                    "WHERE message_fts MATCH ? ORDER BY bm25(message_fts) LIMIT ?",
                arrayOf(match, limit.toString()),
            ).use { c ->
                buildList {
                    while (c.moveToNext()) {
                        add(
                            SearchHit(
                                sessionId = SessionId(c.getString(c.getColumnIndexOrThrow("sid"))),
                                messageId = c.getString(c.getColumnIndexOrThrow("mid")),
                                role = c.getString(c.getColumnIndexOrThrow("role")),
                                snippet = c.getString(c.getColumnIndexOrThrow("snip")) ?: "",
                                at = c.getLong(c.getColumnIndexOrThrow("at")),
                            ),
                        )
                    }
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    /** Linear fallback used when FTS is unavailable. */
    private fun scanSearch(needle: String, limit: Int): List<SearchHit> {
        val hits = ArrayList<SearchHit>()
        db.rawQuery("SELECT * FROM messages ORDER BY created_at DESC", null).use { c ->
            while (c.moveToNext() && hits.size < limit) {
                val message = c.toMessage()
                val text = messageSearchText(message)
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
        }
        return hits
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
        val keep = sessionsUnlocked(keepSessions, includeChildren = true, includeArchived = true).map { it.id.value }
        if (keep.isEmpty()) return@withLock 0
        val placeholders = keep.joinToString(",") { "?" }
        val gone = db.rawQuery("SELECT id FROM sessions WHERE id NOT IN ($placeholders)", keep.toTypedArray()).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
        for (id in gone) deleteSessionUnlocked(SessionId(id))
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
        state = runCatching { SessionState.valueOf(getString(getColumnIndexOrThrow("state")) ?: "") }
            .getOrDefault(SessionState.IDLE),
        pinned = getInt(getColumnIndexOrThrow("pinned")) != 0,
        archived = getInt(getColumnIndexOrThrow("archived")) != 0,
        tags = getString(getColumnIndexOrThrow("tags"))
            ?.let { runCatching { json.decodeFromString(ListSerializer(String.serializer()), it) }.getOrNull() }
            .orEmpty(),
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
