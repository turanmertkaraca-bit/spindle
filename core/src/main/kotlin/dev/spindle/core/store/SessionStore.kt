package dev.spindle.core.store

import dev.spindle.core.model.Message
import dev.spindle.core.model.MessageId
import dev.spindle.core.model.Session
import dev.spindle.core.model.SessionId
import dev.spindle.core.model.TodoItem

/**
 * The single source of truth. The UI renders from this store; there is no
 * parallel client-side model to keep in sync.
 */
interface SessionStore {
    suspend fun createSession(session: Session)
    suspend fun updateSession(session: Session)
    suspend fun session(id: SessionId): Session?
    suspend fun sessions(limit: Int = 50, includeChildren: Boolean = false): List<Session>
    suspend fun deleteSession(id: SessionId)

    suspend fun appendMessage(message: Message)
    suspend fun updateMessage(message: Message)
    suspend fun message(sessionId: SessionId, id: MessageId): Message?
    suspend fun messages(sessionId: SessionId): List<Message>
    suspend fun latestMessage(sessionId: SessionId): Message?

    suspend fun todos(sessionId: SessionId): List<TodoItem>
    suspend fun setTodos(sessionId: SessionId, todos: List<TodoItem>)

    /** Drop old sessions/parts to stay bounded. Returns rows removed. */
    suspend fun prune(keepSessions: Int = 100): Int
}
