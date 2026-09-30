package dev.spindle.core.store

import dev.spindle.core.model.Ids
import dev.spindle.core.model.Message
import dev.spindle.core.model.Part
import dev.spindle.core.model.PartId
import dev.spindle.core.model.SessionId

/** One full-text hit. [snippet] is a short context window around the match. */
data class SearchHit(
    val sessionId: SessionId,
    val messageId: String,
    val role: String,
    val snippet: String,
    val at: Long,
)

/**
 * Full-text search across stored messages. A separate SPI so :core stays free
 * of any index implementation; the Android/SQLite host supplies FTS.
 */
interface SessionSearch {
    suspend fun search(query: String, limit: Int = 20): List<SearchHit>
}

/**
 * The searchable body of a message: assistant/user text, reasoning, and tool
 * output. Shared by the in-memory scan and the SQLite FTS index so both index
 * exactly the same surface.
 */
fun messageSearchText(message: Message): String =
    message.parts.asSequence()
        .mapNotNull { part ->
            when (part) {
                is Part.Text -> part.text
                is Part.Reasoning -> part.text
                is Part.Tool -> part.result?.output
                else -> null
            }
        }
        .filter { it.isNotBlank() }
        .joinToString("\n")

/** The window of [text] around the first case-insensitive [query] occurrence. */
fun searchSnippet(text: String, query: String, radius: Int = 40): String {
    val flat = text.replace('\n', ' ').trim()
    if (flat.isEmpty()) return flat
    val at = flat.indexOf(query, ignoreCase = true)
    if (at < 0) return flat.take(radius * 2)
    val start = (at - radius).coerceAtLeast(0)
    val end = (at + query.length + radius).coerceAtMost(flat.length)
    return buildString {
        if (start > 0) append('…')
        append(flat, start, end)
        if (end < flat.length) append('…')
    }
}

/** A copy of this part with a fresh [PartId], used when forking a session. */
fun Part.withNewId(): Part = when (this) {
    is Part.Text -> copy(id = PartId(Ids.new("prt")))
    is Part.Reasoning -> copy(id = PartId(Ids.new("prt")))
    is Part.Tool -> copy(id = PartId(Ids.new("prt")))
    is Part.File -> copy(id = PartId(Ids.new("prt")))
    is Part.Step -> copy(id = PartId(Ids.new("prt")))
}
