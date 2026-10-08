package dev.spindle.core.agent

import dev.spindle.core.model.FinishReason
import dev.spindle.core.model.Part
import dev.spindle.core.model.Role
import dev.spindle.core.model.SessionId
import dev.spindle.core.model.SessionState
import dev.spindle.core.model.ToolResult
import dev.spindle.core.model.ToolState
import dev.spindle.core.store.SessionStore
import kotlinx.coroutines.CancellationException

/**
 * Repairs sessions left RUNNING by a killed process.
 *
 * A run cannot outlive its process in this app, so on a cold start any session
 * still marked RUNNING and not present in the host's live set is stale. Besides
 * clearing the state (otherwise the session reopens as permanently "busy"), the
 * persisted timeline must be repaired: a tool part left PENDING/RUNNING would
 * spin forever and permanently disable trim/compact, so it is rewritten as an
 * aborted ERROR result and any open assistant message is finalized.
 */
object RunRecovery {
    /**
     * Repair sessions left RUNNING by a killed process. A run cannot outlive its
     * process, so any RUNNING session whose id is not in [liveSessionIds] is
     * stale: its open tool parts become ERROR "aborted", any open assistant
     * message is finalized ERROR "aborted", and the session is set IDLE. Returns
     * the number of sessions repaired.
     */
    suspend fun reconcile(store: SessionStore, liveSessionIds: Set<String>): Int {
        val stale = try {
            store.sessions(limit = Int.MAX_VALUE, includeChildren = true, includeArchived = true)
                .filter { it.state == SessionState.RUNNING && it.id.value !in liveSessionIds }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            return 0
        }

        var repaired = 0
        for (session in stale) {
            val ok = try {
                abortStaleRun(store, session.id)
                store.updateSessionState(session.id, SessionState.IDLE)
                true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                false
            }
            if (ok) repaired++
        }
        return repaired
    }

    /** Rewrite a stale run's in-flight tool parts to ERROR and close open messages. */
    private suspend fun abortStaleRun(store: SessionStore, sid: SessionId) {
        val messages = runCatching { store.messages(sid) }.getOrDefault(emptyList())
        for (message in messages) {
            var rewroteTool = false
            val parts = message.parts.map { part ->
                if (part is Part.Tool && (part.state == ToolState.PENDING || part.state == ToolState.RUNNING)) {
                    rewroteTool = true
                    part.copy(
                        state = ToolState.ERROR,
                        result = ToolResult(part.call.id, "aborted", isError = true),
                    )
                } else {
                    part
                }
            }
            val openAssistant = message.role == Role.ASSISTANT && message.finish == null
            if (!rewroteTool && !openAssistant) continue
            store.updateMessage(
                message.copy(
                    parts = parts,
                    finish = if (openAssistant) FinishReason.ERROR else message.finish,
                    error = message.error ?: if (openAssistant) "aborted" else null,
                ),
            )
        }
    }
}
