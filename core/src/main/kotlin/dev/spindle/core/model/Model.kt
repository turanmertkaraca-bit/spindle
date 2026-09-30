package dev.spindle.core.model

import kotlinx.serialization.Serializable

enum class Role { USER, ASSISTANT, SYSTEM, TOOL }

enum class FinishReason { STOP, TOOL_CALLS, LENGTH, CONTENT_FILTER, ERROR, UNKNOWN }

enum class ToolState { PENDING, RUNNING, DONE, ERROR }

enum class SessionState { IDLE, RUNNING, ERROR }

@Serializable
data class Usage(
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val reasoningTokens: Int = 0,
    val cacheReadTokens: Int = 0,
    val cacheWriteTokens: Int = 0,
    val costUsd: Double = 0.0,
) {
    operator fun plus(other: Usage) = Usage(
        inputTokens = inputTokens + other.inputTokens,
        outputTokens = outputTokens + other.outputTokens,
        reasoningTokens = reasoningTokens + other.reasoningTokens,
        cacheReadTokens = cacheReadTokens + other.cacheReadTokens,
        cacheWriteTokens = cacheWriteTokens + other.cacheWriteTokens,
        costUsd = costUsd + other.costUsd,
    )

    val totalTokens: Int get() = inputTokens + outputTokens
}

@Serializable
data class ToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String,
)

@Serializable
data class ToolResult(
    val callId: String,
    val output: String,
    val isError: Boolean = false,
    val diff: String? = null,
    val metadata: Map<String, String> = emptyMap(),
)

/** A piece of a message. Sealed so the UI can render each kind distinctly. */
@Serializable
sealed interface Part {
    val id: PartId

    @Serializable
    data class Text(override val id: PartId, val text: String) : Part

    @Serializable
    data class Reasoning(override val id: PartId, val text: String) : Part

    @Serializable
    data class Tool(
        override val id: PartId,
        val call: ToolCall,
        val state: ToolState,
        val result: ToolResult? = null,
        val title: String? = null,
    ) : Part

    @Serializable
    data class File(override val id: PartId, val path: String, val mime: String? = null) : Part

    @Serializable
    data class Step(override val id: PartId, val index: Int) : Part
}

@Serializable
data class Message(
    val id: MessageId,
    val sessionId: SessionId,
    val role: Role,
    val parts: List<Part> = emptyList(),
    val createdAt: Long,
    val model: String? = null,
    val providerId: String? = null,
    val agent: String? = null,
    val usage: Usage = Usage(),
    val finish: FinishReason? = null,
    val error: String? = null,
)

@Serializable
data class TodoItem(
    val id: String,
    val content: String,
    val status: TodoStatus,
)

@Serializable
enum class TodoStatus { PENDING, IN_PROGRESS, DONE, CANCELLED }

@Serializable
data class Session(
    val id: SessionId,
    val title: String = "",
    val cwd: String,
    val createdAt: Long,
    val updatedAt: Long,
    val model: String? = null,
    val providerId: String? = null,
    val agent: String = "build",
    val parentId: SessionId? = null,
    val state: SessionState = SessionState.IDLE,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val tags: List<String> = emptyList(),
)
