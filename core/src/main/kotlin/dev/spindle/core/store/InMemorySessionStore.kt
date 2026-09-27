package dev.spindle.core.store

import dev.spindle.core.model.Message
import dev.spindle.core.model.MessageId
import dev.spindle.core.model.Session
import dev.spindle.core.model.SessionId
import dev.spindle.core.model.TodoItem
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** In-memory store. Used by tests and as a fallback; the SQLite store is durable. */
class InMemorySessionStore : SessionStore {
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

    override suspend fun sessions(limit: Int, includeChildren: Boolean): List<Session> = mutex.withLock {
        sessions.values
            .filter { includeChildren || it.parentId == null }
            .sortedByDescending { it.updatedAt }
            .take(limit)
    }

    override suspend fun deleteSession(id: SessionId) {
        mutex.withLock { sessions.remove(id); messages.remove(id); todos.remove(id) }
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
        val drop = sessions.values.sortedByDescending { it.updatedAt }.drop(keepSessions)
        drop.forEach { sessions.remove(it.id); messages.remove(it.id); todos.remove(it.id) }
        drop.size
    }
}
