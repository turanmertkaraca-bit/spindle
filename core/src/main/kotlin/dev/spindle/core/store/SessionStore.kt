package dev.spindle.core.store

import dev.spindle.core.model.Message
import dev.spindle.core.model.MessageId
import dev.spindle.core.model.Part
import dev.spindle.core.model.Session
import dev.spindle.core.model.SessionId
import dev.spindle.core.model.SessionState
import dev.spindle.core.model.TodoItem

/**
 * The single source of truth. The UI renders from this store; there is no
 * parallel client-side model to keep in sync.
 */
interface SessionStore {
    suspend fun createSession(session: Session)
    suspend fun updateSession(session: Session)

    /**
     * Update ONLY the run [state] of session [id] (and its `updated_at` where the
     * storage has that column). A missing session is a no-op. Callers that only
     * move the run state use this instead of reading and rewriting a whole row.
     */
    suspend fun updateSessionState(id: SessionId, state: SessionState)

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

    /**
     * Upsert a single [part] into the message ([sessionId], [messageId]) keyed by
     * `part.id`, leaving the message's other parts and their order untouched. A
     * part whose id already exists is replaced in place; an unknown id is
     * appended. A message that does not exist is a no-op. Unlike
     * [updateMessage], this never rewrites (and so cannot clobber) unrelated
     * parts written by a concurrent updater.
     */
    suspend fun updatePart(sessionId: SessionId, messageId: MessageId, part: Part)

    suspend fun message(sessionId: SessionId, id: MessageId): Message?
    suspend fun messages(sessionId: SessionId): List<Message>
    suspend fun latestMessage(sessionId: SessionId): Message?

    suspend fun todos(sessionId: SessionId): List<TodoItem>
    suspend fun setTodos(sessionId: SessionId, todos: List<TodoItem>)

    /** Drop old sessions/parts to stay bounded. Returns rows removed. */
    suspend fun prune(keepSessions: Int = 100): Int

    /**
     * Best-effort storage maintenance at a safe boundary (end of a run): FTS
     * optimization, WAL checkpointing, and the like. The default is a no-op so
     * in-memory and headless stores stay simple.
     */
    suspend fun maintain() {}
}
