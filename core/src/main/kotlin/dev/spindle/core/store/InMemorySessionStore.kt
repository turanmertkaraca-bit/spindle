package dev.spindle.core.store

import dev.spindle.core.model.Ids
import dev.spindle.core.model.Message
import dev.spindle.core.model.MessageId
import dev.spindle.core.model.Part
import dev.spindle.core.model.PartId
import dev.spindle.core.model.Session
import dev.spindle.core.model.SessionId
import dev.spindle.core.model.TodoItem
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** In-memory store. Used by tests and as a fallback; the SQLite store is durable. */
class InMemorySessionStore : SessionStore, SessionSearch {
    private val mutex = Mutex()
    private val sessions = LinkedHashMap<SessionId, Session>()
    private val messages = HashMap<SessionId, MutableList<Message>>()
    private val todos = HashMap<SessionId, List<TodoItem>>()

    override suspend fun createSession(session: Session) {
        mutex.withLock {
            sessions[session.id] = session
            messages.getOrPut(session.id) { mutableListOf() }
        }
    }

    override suspend fun updateSession(session: Session) {
        mutex.withLock { sessions[session.id] = session }
    }

    override suspend fun session(id: SessionId): Session? = mutex.withLock { sessions[id] }

    override suspend fun sessions(
        limit: Int,
        includeChildren: Boolean,
        includeArchived: Boolean,
    ): List<Session> = mutex.withLock {
        sessions.values
            .filter { includeChildren || it.parentId == null }
            .filter { includeArchived || !it.archived }
            .sortedByDescending { it.updatedAt }
            .take(limit.coerceAtLeast(0))
    }

    override suspend fun deleteSession(id: SessionId) {
        mutex.withLock { sessions.remove(id); messages.remove(id); todos.remove(id) }
    }

    override suspend fun forkSession(
        sourceId: SessionId,
        atMessageId: MessageId?,
        newId: SessionId,
    ): Session? = mutex.withLock {
        val source = sessions[sourceId] ?: return@withLock null
        val sourceMessages = messages[sourceId].orEmpty()
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
        )
        sessions[newId] = fork
        messages[newId] = upTo.map { it.forkedInto(newId) }.toMutableList()
        todos[newId] = emptyList()
        fork
    }

    override suspend fun rewind(sessionId: SessionId, toMessageId: MessageId): Int = mutex.withLock {
        val list = messages[sessionId] ?: return@withLock 0
        val index = list.indexOfFirst { it.id == toMessageId }
        if (index < 0) return@withLock 0
        val removed = list.size - index - 1
        while (list.size > index + 1) list.removeAt(list.size - 1)
        removed
    }

    override suspend fun appendMessage(message: Message) {
        mutex.withLock { messages.getOrPut(message.sessionId) { mutableListOf() }.add(message) }
    }

    override suspend fun updateMessage(message: Message) {
        mutex.withLock {
            val list = messages.getOrPut(message.sessionId) { mutableListOf() }
            val i = list.indexOfFirst { it.id == message.id }
            if (i >= 0) list[i] = message else list.add(message)
        }
    }

    override suspend fun message(sessionId: SessionId, id: MessageId): Message? = mutex.withLock {
        messages[sessionId]?.firstOrNull { it.id == id }
    }

    override suspend fun messages(sessionId: SessionId): List<Message> = mutex.withLock {
        messages[sessionId]?.toList() ?: emptyList()
    }

    override suspend fun latestMessage(sessionId: SessionId): Message? = mutex.withLock {
        messages[sessionId]?.lastOrNull()
    }

    override suspend fun todos(sessionId: SessionId): List<TodoItem> = mutex.withLock {
        todos[sessionId] ?: emptyList()
    }

    override suspend fun setTodos(sessionId: SessionId, todos: List<TodoItem>) {
        mutex.withLock { this.todos[sessionId] = todos }
    }

    override suspend fun prune(keepSessions: Int): Int = mutex.withLock {
        val drop = sessions.values.sortedByDescending { it.updatedAt }.drop(keepSessions.coerceAtLeast(0))
        drop.forEach { sessions.remove(it.id); messages.remove(it.id); todos.remove(it.id) }
        drop.size
    }

    override suspend fun search(query: String, limit: Int): List<SearchHit> = mutex.withLock {
        val needle = query.trim()
        if (needle.isEmpty()) return@withLock emptyList()
        val hits = ArrayList<SearchHit>()
        for ((sessionId, list) in messages) {
            for (message in list) {
                val text = messageSearchText(message)
                if (text.contains(needle, ignoreCase = true)) {
                    hits += SearchHit(
                        sessionId = sessionId,
                        messageId = message.id.value,
                        role = message.role.name,
                        snippet = searchSnippet(text, needle),
                        at = message.createdAt,
                    )
                }
            }
        }
        hits.sortedByDescending { it.at }.take(limit.coerceAtLeast(0))
    }
}

private fun Message.forkedInto(sessionId: SessionId): Message = copy(
    id = MessageId(Ids.new("msg")),
    sessionId = sessionId,
    parts = parts.map { it.forkedPart() },
)

/**
 * Give a copied part fresh ids, remapping a tool call and its result together so
 * the pairing stays internally consistent inside the fork.
 */
private fun Part.forkedPart(): Part = when (this) {
    is Part.Tool -> {
        val callId = Ids.new("call")
        copy(
            id = PartId(Ids.new("prt")),
            call = call.copy(id = callId),
            result = result?.copy(callId = callId),
        )
    }
    else -> withNewId()
}
