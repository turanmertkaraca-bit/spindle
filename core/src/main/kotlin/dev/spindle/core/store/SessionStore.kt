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

    /** Most recently updated first. Hidden children/archived sessions are opt-in. */
    suspend fun sessions(
        limit: Int = 50,
        includeChildren: Boolean = false,
        includeArchived: Boolean = false,
    ): List<Session>

    suspend fun deleteSession(id: SessionId)

    /**
     * Copy [sourceId] into [newId] as a child (`parentId = sourceId`). Messages
     * are copied in order up to and including [atMessageId], or all of them when
     * it is null. Returns the new session, or null when the source or the fork
     * point does not exist.
     */
    suspend fun forkSession(sourceId: SessionId, atMessageId: MessageId?, newId: SessionId): Session?

    /**
     * Drop every message strictly after [toMessageId] (the rewind point is
     * kept). Returns the number of messages removed; zero when the point is
     * unknown.
     */
    suspend fun rewind(sessionId: SessionId, toMessageId: MessageId): Int

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
