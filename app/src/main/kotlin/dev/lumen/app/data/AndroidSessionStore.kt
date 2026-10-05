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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
 *
 * All public methods hop to [Dispatchers.IO] before touching SQLite, and the
 * connection/schema/FTS index are opened lazily on that worker, so the main
 * thread never blocks on disk.
 */
class AndroidSessionStore internal constructor(private val shared: AndroidDatabase) :
    SessionStore, SessionSearch, AutoCloseable {

    constructor(context: Context) : this(AndroidDatabase(context))

    private val mutex = Mutex()

    /** The shared, lazily-opened connection. Only touch from [locked]. */
    private val db: SQLiteDatabase get() = shared.db()

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

    /** One-time FTS setup/backfill, guarded so it runs on the first worker call. */
    @Volatile
    private var ftsInitialized = false

    /** True once FTS5 answered; when false the linear scan is used. */
    @Volatile
    private var ftsReady = false

    /**
     * FTS row rewrites since the last `optimize`. `DELETE`/`INSERT` on an FTS5
     * table leaves tombstones until a merge, so a long session needs periodic
     * optimization; the counter avoids doing it after every tiny run.
     */
    private var ftsWrites = 0

    /**
     * Run [block] on the IO dispatcher under the store mutex, after ensuring the
     * optional FTS index exists. Every suspend entry point funnels through here.
     */
    private suspend fun <T> locked(block: () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureFts()
            block()
        }
    }

    /**
     * Best-effort FTS5 index over message text. Android's bundled SQLite may not
     * have FTS5 (it historically shipped FTS3/4), so this is entirely optional:
     * if the virtual table cannot be created we stay on the linear scan and
     * search still works. When the index exists it is kept in sync and backfilled
     * once, in a single transaction, from one batched message read.
     */
    private fun ensureFts() {
        if (ftsInitialized) return
        synchronized(this) {
            if (ftsInitialized) return
            try {
                db.execSQL(
                    "CREATE VIRTUAL TABLE IF NOT EXISTS message_fts USING fts5(message_id UNINDEXED, session_id UNINDEXED, role UNINDEXED, body)",
                )
                ftsReady = true
            } catch (_: Throwable) {
                ftsReady = false
                ftsInitialized = true
                return
            }
            try {
                val indexed = db.rawQuery("SELECT COUNT(*) FROM message_fts", null).use { c ->
                    if (c.moveToFirst()) c.getInt(0) else 0
                }
                if (indexed == 0) backfillFts()
            } catch (_: Throwable) {
                // A backfill failure is non-fatal; insert/delete keep it in sync.
            }
            ftsInitialized = true
        }
    }

    /** Rebuild the FTS rows from a single batched read of every stored message. */
    private fun backfillFts() {
        val messages = allMessages()
        if (messages.isEmpty()) return
        db.beginTransaction()
        try {
            for (message in messages) ftsRow(message)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun ftsRow(message: Message) {
        if (!ftsReady) return
        try {
            db.delete("message_fts", "message_id=?", arrayOf(message.id.value))
            val body = messageSearchText(message)
            if (body.isNotBlank()) {
                db.execSQL(
                    "INSERT INTO message_fts(message_id, session_id, role, body) VALUES(?,?,?,?)",
                    arrayOf(message.id.value, message.sessionId.value, message.role.name, body),
                )
            }
            ftsWrites++
        } catch (_: Throwable) {
            // The index is optional: a failed write makes it stale, so stop
            // trusting it and let search() fall back to the linear scan.
            ftsReady = false
        }
    }

    private fun ftsDeleteMessage(messageId: String) {
        if (!ftsReady) return
        try {
            db.delete("message_fts", "message_id=?", arrayOf(messageId))
            ftsWrites++
        } catch (_: Throwable) {
            ftsReady = false
        }
    }

    private fun ftsDeleteSession(sessionId: String) {
        if (!ftsReady) return
        try {
            db.delete("message_fts", "session_id=?", arrayOf(sessionId))
            ftsWrites++
        } catch (_: Throwable) {
            ftsReady = false
        }
    }

    // ---- sessions ----

    override suspend fun createSession(session: Session) = locked { writeSession(session) }

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

    override suspend fun session(id: SessionId): Session? = locked { sessionUnlocked(id) }

    private fun sessionUnlocked(id: SessionId): Session? =
        db.rawQuery("SELECT * FROM sessions WHERE id=?", arrayOf(id.value)).use { c ->
            if (c.moveToFirst()) c.toSession() else null
        }

    override suspend fun sessions(
        limit: Int,
        includeChildren: Boolean,
        includeArchived: Boolean,
    ): List<Session> = locked { sessionsUnlocked(limit, includeChildren, includeArchived) }

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

    override suspend fun deleteSession(id: SessionId) = locked {
        transaction { deleteSessionUnlocked(id) }
    }

    private fun deleteSessionUnlocked(id: SessionId) {
        db.delete("parts", "session_id=?", arrayOf(id.value))
        db.delete("messages", "session_id=?", arrayOf(id.value))
        db.delete("todos", "session_id=?", arrayOf(id.value))
        // Snapshots are large whole-file copies; a deleted session must not leave
        // them orphaned in the shared DB forever.
        db.delete("snapshots", "session_id=?", arrayOf(id.value))
        ftsDeleteSession(id.value)
        db.delete("sessions", "id=?", arrayOf(id.value))
    }

    override suspend fun forkSession(
        sourceId: SessionId,
        atMessageId: MessageId?,
        newId: SessionId,
    ): Session? = locked {
        val source = sessionUnlocked(sourceId) ?: return@locked null
        val sourceMessages = messagesUnlocked(sourceId)
        val upTo = if (atMessageId == null) {
            sourceMessages.toList()
        } else {
            val index = sourceMessages.indexOfFirst { it.id == atMessageId }
            if (index < 0) return@locked null
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
        transaction {
            writeSession(fork)
            for (m in upTo) {
                insertMessage(m.copy(id = MessageId(Ids.new("msg")), sessionId = newId, parts = m.parts.map { it.withNewId() }))
            }
        }
        fork
    }

    override suspend fun rewind(sessionId: SessionId, toMessageId: MessageId): Int = locked {
        val list = messagesUnlocked(sessionId)
        val index = list.indexOfFirst { it.id == toMessageId }
        if (index < 0) return@locked 0
        val tail = list.subList(index + 1, list.size)
        transaction {
            for (m in tail) {
                db.delete("parts", "message_id=?", arrayOf(m.id.value))
                db.delete("messages", "id=?", arrayOf(m.id.value))
                ftsDeleteMessage(m.id.value)
            }
        }
        tail.size
    }

    // ---- messages ----

    override suspend fun appendMessage(message: Message) = locked { transaction { insertMessage(message) } }

    private fun insertMessage(message: Message) {
        val seq = nextSeq(message.sessionId)
        writeMessageRow(message, seq)
    }

    override suspend fun updateMessage(message: Message) = locked {
        transaction {
            // A missing id is an insert, not a rewrite: allocate the next
            // sequence slot so it sorts after existing history rather than before.
            val existing = db.rawQuery("SELECT seq FROM messages WHERE id=?", arrayOf(message.id.value)).use { c ->
                if (c.moveToFirst()) c.getLong(0) else null
            }
            writeMessageRow(message, existing ?: nextSeq(message.sessionId))
        }
    }

    /** Write the message row plus its parts and FTS entry as one unit. */
    private fun writeMessageRow(message: Message, seq: Long) {
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

    override suspend fun message(sessionId: SessionId, id: MessageId): Message? = locked {
        val row = db.rawQuery("SELECT * FROM messages WHERE id=?", arrayOf(id.value)).use { c ->
            if (c.moveToFirst()) c.toMessageRow() else null
        } ?: return@locked null
        row.copy(parts = partsFor(listOf(row.id.value))[row.id.value].orEmpty())
    }

    override suspend fun messages(sessionId: SessionId): List<Message> = locked { messagesUnlocked(sessionId) }

    private fun messagesUnlocked(sessionId: SessionId): List<Message> {
        val rows = db.rawQuery("SELECT * FROM messages WHERE session_id=? ORDER BY seq", arrayOf(sessionId.value)).use { c ->
            buildList { while (c.moveToNext()) add(c.toMessageRow()) }
        }
        val parts = partsFor(rows.map { it.id.value })
        return rows.map { it.copy(parts = parts[it.id.value].orEmpty()) }
    }

    /** Every message, ordered, with its parts read in batched queries. */
    private fun allMessages(): List<Message> {
        val rows = db.rawQuery("SELECT * FROM messages ORDER BY session_id, seq", null).use { c ->
            buildList { while (c.moveToNext()) add(c.toMessageRow()) }
        }
        val parts = partsFor(rows.map { it.id.value })
        return rows.map { it.copy(parts = parts[it.id.value].orEmpty()) }
    }

    override suspend fun latestMessage(sessionId: SessionId): Message? = locked {
        val row = db.rawQuery("SELECT * FROM messages WHERE session_id=? ORDER BY seq DESC LIMIT 1", arrayOf(sessionId.value)).use { c ->
            if (c.moveToFirst()) c.toMessageRow() else null
        } ?: return@locked null
        row.copy(parts = partsFor(listOf(row.id.value))[row.id.value].orEmpty())
    }

    // ---- search ----

    override suspend fun search(query: String, limit: Int): List<SearchHit> = locked {
        val needle = query.trim()
        if (needle.isEmpty()) return@locked emptyList()
        if (ftsReady) {
            ftsSearch(needle, limit)?.let { return@locked it }
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
        for (message in allMessages()) {
            if (hits.size >= limit) break
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
        return hits
    }

    // ---- todos ----

    override suspend fun todos(sessionId: SessionId): List<TodoItem> = locked {
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

    override suspend fun setTodos(sessionId: SessionId, todos: List<TodoItem>) = locked {
        transaction {
            db.delete("todos", "session_id=?", arrayOf(sessionId.value))
            todos.forEachIndexed { i, t ->
                db.execSQL("INSERT INTO todos VALUES(?,?,?,?,?)", arrayOf(t.id, sessionId.value, i, t.content, t.status.name))
            }
        }
    }

    override suspend fun prune(keepSessions: Int): Int = locked {
        // keepSessions == 0 means "drop everything"; the old LIMIT 0 query
        // returned an empty keep-set and bailed out, making it a silent no-op.
        if (keepSessions <= 0) {
            val all = sessionsUnlocked(Int.MAX_VALUE, includeChildren = true, includeArchived = true).map { it.id.value }
            transaction { for (id in all) deleteSessionUnlocked(SessionId(id)) }
            return@locked all.size
        }
        val keep = sessionsUnlocked(keepSessions, includeChildren = true, includeArchived = true).map { it.id.value }
        val placeholders = keep.joinToString(",") { "?" }
        val gone = db.rawQuery("SELECT id FROM sessions WHERE id NOT IN ($placeholders)", keep.toTypedArray()).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
        transaction { for (id in gone) deleteSessionUnlocked(SessionId(id)) }
        gone.size
    }

    /**
     * Fold FTS tombstones left by message rewrites/compaction and truncate the
     * WAL. Called at the end of a run so a very long session cannot grow the
     * index or the `-wal` file without bound.
     */
    override suspend fun maintain() {
        locked {
            // Only merge when enough rewrites have accumulated; calling optimize
            // on every run would make a subagent-heavy session pay O(index) each time.
            if (ftsReady && ftsWrites >= FTS_OPTIMIZE_AFTER) {
                runCatching {
                    db.execSQL("INSERT INTO message_fts(message_fts) VALUES('optimize')")
                    ftsWrites = 0
                }
            }
            runCatching {
                db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
            }
        }
    }

    override fun close() {
        // Guard with the mutex so a close cannot race an in-flight worker query.
        runBlocking {
            mutex.withLock { shared.close() }
        }
    }

    /** Run [block] as one SQLite transaction, committing only on success. */
    private inline fun <T> transaction(block: () -> T): T {
        db.beginTransaction()
        try {
            val result = block()
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Parts for [messageIds], read in one query per batch and grouped by
     * message id, so a page of messages is one round-trip instead of N.
     */
    private fun partsFor(messageIds: Collection<String>): Map<String, List<Part>> {
        if (messageIds.isEmpty()) return emptyMap()
        val acc = HashMap<String, MutableList<Part>>()
        for (chunk in messageIds.chunked(SQLITE_PARAM_BATCH)) {
            val placeholders = chunk.joinToString(",") { "?" }
            db.rawQuery(
                "SELECT message_id, data FROM parts WHERE message_id IN ($placeholders) ORDER BY message_id, ord",
                chunk.toTypedArray(),
            ).use { c ->
                while (c.moveToNext()) {
                    val mid = c.getString(0)
                    val part = runCatching { json.decodeFromString(Part.serializer(), c.getString(1)) }.getOrNull() ?: continue
                    acc.getOrPut(mid) { mutableListOf() }.add(part)
                }
            }
        }
        return acc
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

    /** A message row without its parts; callers attach [partsFor] output. */
    private fun android.database.Cursor.toMessageRow(): Message {
        val id = getString(getColumnIndexOrThrow("id"))
        val sid = getString(getColumnIndexOrThrow("session_id"))
        val usageStr = getString(getColumnIndexOrThrow("usage"))
        return Message(
            id = MessageId(id),
            sessionId = SessionId(sid),
            role = runCatching { Role.valueOf(getString(getColumnIndexOrThrow("role"))) }.getOrDefault(Role.ASSISTANT),
            parts = emptyList(),
            createdAt = getLong(getColumnIndexOrThrow("created_at")),
            model = getString(getColumnIndexOrThrow("model")),
            providerId = getString(getColumnIndexOrThrow("provider_id")),
            agent = getString(getColumnIndexOrThrow("agent")),
            usage = usageStr?.let { runCatching { json.decodeFromString(Usage.serializer(), it) }.getOrNull() } ?: Usage(),
            finish = getString(getColumnIndexOrThrow("finish"))?.let { runCatching { FinishReason.valueOf(it) }.getOrNull() },
            error = getString(getColumnIndexOrThrow("error")),
        )
    }

    private companion object {
        /** Stay well under SQLite's bound-variable limit for old devices. */
        const val SQLITE_PARAM_BATCH = 900

        /** FTS rewrites tolerated before an `optimize` merges the tombstones. */
        const val FTS_OPTIMIZE_AFTER = 128
    }
}
