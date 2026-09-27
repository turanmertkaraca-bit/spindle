package dev.spindle.core.tool

import dev.spindle.core.model.SessionId
import kotlinx.serialization.json.JsonObject

/** Everything a tool needs that the loop owns. */
interface ToolContext {
    val sessionId: SessionId
    /** Absolute, sandboxed working directory for this session. */
    val cwd: java.nio.file.Path
    /** Returns true if the user approved this invocation. */
    suspend fun requestPermission(tool: String, detail: String, pattern: String? = null): Boolean
    /** Emit out-of-band events (progress, diff, etc.) to the session stream. */
    fun emit(event: ToolProgress)
    fun checkAborted()
}

data class ToolProgress(val message: String, val fraction: Double? = null)

data class ToolOutcome(
    val output: String,
    val isError: Boolean = false,
    val diff: String? = null,
    val metadata: Map<String, String> = emptyMap(),
)

interface Tool {
    val spec: dev.spindle.core.provider.ToolSpec
    suspend fun run(input: JsonObject, ctx: ToolContext): ToolOutcome
}

class ToolRegistry(tools: List<Tool>) {
    private val byName: Map<String, Tool> = tools.associateBy { it.spec.name }
    val specs: List<dev.spindle.core.provider.ToolSpec> get() = byName.values.map { it.spec }
    fun get(name: String): Tool? = byName[name]
    fun all(): List<Tool> = byName.values.toList()
}
