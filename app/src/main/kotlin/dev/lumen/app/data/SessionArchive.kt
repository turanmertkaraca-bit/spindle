package dev.lumen.app.data

import dev.spindle.core.model.FinishReason
import dev.spindle.core.model.Ids
import dev.spindle.core.model.Message
import dev.spindle.core.model.MessageId
import dev.spindle.core.model.Part
import dev.spindle.core.model.PartId
import dev.spindle.core.model.Role
import dev.spindle.core.model.Session
import dev.spindle.core.model.SessionId
import dev.spindle.core.model.SessionState
import dev.spindle.core.model.ToolCall
import dev.spindle.core.model.ToolResult
import dev.spindle.core.model.ToolState
import dev.spindle.core.model.Usage
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray

/**
 * Portable, human-readable session export/import.
 *
 * The on-disk document is deliberately a set of standalone DTOs rather than the
 * domain [Part] sealed hierarchy, so the wire format stays stable even when the
 * core model gains new part kinds. Decoding is forgiving: unknown keys are
 * ignored, every field has a default, and enum names that do not parse fall
 * back to a safe value.
 *
 * With [encode]'s default `redact = true`, inline binary/large payloads are
 * stripped: image `dataBase64` is dropped, tool `diff`s are dropped, and very
 * long tool outputs are truncated, so an export never balloons to megabytes.
 */
object SessionArchive {
    const val VERSION = 1

    /** Tool outputs longer than this (chars) are truncated when redacting. */
    const val MAX_TOOL_OUTPUT = 4 * 1024

    const val IMPORT_SUFFIX = " (imported)"

    private val archiveJson = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    /** Serialize [session] and its [messages] to a versioned JSON document. */
    fun encode(session: Session, messages: List<Message>, redact: Boolean = true): String {
        val document = ArchiveDocument(
            version = VERSION,
            exportedAt = System.currentTimeMillis(),
            session = session.toDto(),
            messages = messages.map { it.toDto(redact) },
        )
        return archiveJson.encodeToString(ArchiveDocument.serializer(), document)
    }

    /**
     * Parse a document produced by [encode]. Each returned session gets fresh
     * ids, `parentId = null`, and an "(imported)" title suffix. A single JSON
     * document or an array of them is accepted. Malformed input yields an empty
     * list rather than throwing.
     */
    fun decode(json: String): List<ImportedSession> {
        val root = runCatching { Json.parseToJsonElement(json) }.getOrNull() ?: return emptyList()
        val documents: List<ArchiveDocument> = when (root) {
            is JsonArray -> root.mapNotNull { element ->
                runCatching {
                    archiveJson.decodeFromJsonElement(ArchiveDocument.serializer(), element)
                }.getOrNull()
            }
            else -> listOfNotNull(
                runCatching {
                    archiveJson.decodeFromJsonElement(ArchiveDocument.serializer(), root)
                }.getOrNull(),
            )
        }
        return documents.map { it.toImported() }
    }

    /** Roll-up of a decoded batch, for callers that want the counts. */
    fun summarize(imported: List<ImportedSession>): ImportResult =
        ImportResult(sessions = imported.size, messages = imported.sumOf { it.messages.size })
}

/** A decoded session plus its decoded messages, ready to be written to a store. */
data class ImportedSession(val session: Session, val messages: List<Message>)

/** Counts produced by an import batch. */
data class ImportResult(val sessions: Int, val messages: Int)

// --- Wire format -------------------------------------------------------------

@Serializable
internal data class ArchiveDocument(
    val version: Int = SessionArchive.VERSION,
    val exportedAt: Long = 0L,
    val session: SessionDto = SessionDto(),
    val messages: List<MessageDto> = emptyList(),
)

@Serializable
internal data class SessionDto(
    val id: String = "",
    val title: String = "",
    val cwd: String = "",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val model: String? = null,
    val providerId: String? = null,
    val agent: String = "build",
    val parentId: String? = null,
    val state: String = "IDLE",
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val tags: List<String> = emptyList(),
)

@Serializable
internal data class MessageDto(
    val id: String = "",
    val role: String = "USER",
    val createdAt: Long = 0L,
    val model: String? = null,
    val providerId: String? = null,
    val agent: String? = null,
    val usage: UsageDto = UsageDto(),
    val finish: String? = null,
    val error: String? = null,
    val parts: List<PartDto> = emptyList(),
)

@Serializable
internal data class UsageDto(
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val reasoningTokens: Int = 0,
    val cacheReadTokens: Int = 0,
    val cacheWriteTokens: Int = 0,
    val costUsd: Double = 0.0,
)

/** One part; [kind] is `text`, `reasoning`, `tool`, `file` or `step`. */
@Serializable
internal data class PartDto(
    val kind: String = "text",
    val id: String = "",
    val text: String? = null,
    val tool: ToolDto? = null,
    val file: FileDto? = null,
    val step: Int? = null,
)

@Serializable
internal data class ToolDto(
    val callId: String? = null,
    val name: String = "",
    val arguments: String = "",
    val state: String = "DONE",
    val output: String? = null,
    val isError: Boolean = false,
    val diff: String? = null,
    val title: String? = null,
)

@Serializable
internal data class FileDto(
    val path: String = "",
    val mime: String? = null,
    val dataBase64: String? = null,
)

// --- Domain -> DTO -----------------------------------------------------------

private fun Session.toDto() = SessionDto(
    id = id.value,
    title = title,
    cwd = cwd,
    createdAt = createdAt,
    updatedAt = updatedAt,
    model = model,
    providerId = providerId,
    agent = agent,
    parentId = parentId?.value,
    state = state.name,
    pinned = pinned,
    archived = archived,
    tags = tags,
)

private fun Message.toDto(redact: Boolean) = MessageDto(
    id = id.value,
    role = role.name,
    createdAt = createdAt,
    model = model,
    providerId = providerId,
    agent = agent,
    usage = usage.toDto(),
    finish = finish?.name,
    error = error,
    parts = parts.map { it.toDto(redact) },
)

private fun Usage.toDto() = UsageDto(
    inputTokens = inputTokens,
    outputTokens = outputTokens,
    reasoningTokens = reasoningTokens,
    cacheReadTokens = cacheReadTokens,
    cacheWriteTokens = cacheWriteTokens,
    costUsd = costUsd,
)

private fun Part.toDto(redact: Boolean): PartDto = when (this) {
    is Part.Text -> PartDto(kind = "text", id = id.value, text = text)
    is Part.Reasoning -> PartDto(kind = "reasoning", id = id.value, text = text)
    is Part.Tool -> PartDto(
        kind = "tool",
        id = id.value,
        tool = ToolDto(
            callId = call.id,
            name = call.name,
            arguments = call.argumentsJson,
            state = state.name,
            output = result?.output?.let { if (redact) it.truncated(SessionArchive.MAX_TOOL_OUTPUT) else it },
            isError = result?.isError ?: false,
            diff = if (redact) null else result?.diff,
            title = title,
        ),
    )
    is Part.File -> PartDto(
        kind = "file",
        id = id.value,
        file = FileDto(
            path = path,
            mime = mime,
            dataBase64 = if (redact) null else dataBase64,
        ),
    )
    is Part.Step -> PartDto(kind = "step", id = id.value, step = index)
}

private fun String.truncated(limit: Int): String =
    if (length <= limit) this else take(limit) + "\n…[truncated ${length - limit} chars]"

// --- DTO -> Domain -----------------------------------------------------------

private fun ArchiveDocument.toImported(): ImportedSession {
    val sessionId = SessionId(Ids.new("ses"))
    val session = Session(
        id = sessionId,
        title = importTitle(this.session.title),
        cwd = this.session.cwd,
        createdAt = this.session.createdAt,
        updatedAt = this.session.updatedAt,
        model = this.session.model,
        providerId = this.session.providerId,
        agent = this.session.agent.ifBlank { "build" },
        parentId = null,
        state = enumOf(this.session.state, SessionState.IDLE),
        pinned = this.session.pinned,
        archived = this.session.archived,
        tags = this.session.tags,
    )
    val messages = this.messages.map { it.toDomain(sessionId) }
    return ImportedSession(session, messages)
}

private fun MessageDto.toDomain(sessionId: SessionId): Message = Message(
    id = MessageId(Ids.new("msg")),
    sessionId = sessionId,
    role = enumOf(role, Role.USER),
    parts = parts.map { it.toDomain() },
    createdAt = createdAt,
    model = model,
    providerId = providerId,
    agent = agent,
    usage = usage.toDomain(),
    finish = enumOrNull<FinishReason>(finish),
    error = error,
)

private fun UsageDto.toDomain() = Usage(
    inputTokens = inputTokens,
    outputTokens = outputTokens,
    reasoningTokens = reasoningTokens,
    cacheReadTokens = cacheReadTokens,
    cacheWriteTokens = cacheWriteTokens,
    costUsd = costUsd,
)

private fun PartDto.toDomain(): Part = when (kind.lowercase()) {
    "reasoning" -> Part.Reasoning(PartId(Ids.new("prt")), text.orEmpty())
    "tool" -> {
        val t = tool ?: ToolDto()
        val callId = t.callId ?: Ids.new("call")
        Part.Tool(
            id = PartId(Ids.new("prt")),
            call = ToolCall(callId, t.name, t.arguments),
            state = enumOf(t.state, ToolState.DONE),
            result = t.output?.let { ToolResult(callId, it, t.isError, t.diff) },
            title = t.title,
        )
    }
    "file" -> {
        val f = file ?: FileDto()
        Part.File(PartId(Ids.new("prt")), f.path, f.mime, f.dataBase64)
    }
    "step" -> Part.Step(PartId(Ids.new("prt")), step ?: 0)
    else -> Part.Text(PartId(Ids.new("prt")), text.orEmpty())
}

private fun importTitle(title: String): String =
    if (title.endsWith(SessionArchive.IMPORT_SUFFIX)) title else title + SessionArchive.IMPORT_SUFFIX

private inline fun <reified E : Enum<E>> enumOrNull(name: String?): E? =
    name?.takeIf { it.isNotBlank() }
        ?.let { runCatching { enumValueOf<E>(it.uppercase()) }.getOrNull() }

private inline fun <reified E : Enum<E>> enumOf(name: String?, fallback: E): E =
    enumOrNull<E>(name) ?: fallback
