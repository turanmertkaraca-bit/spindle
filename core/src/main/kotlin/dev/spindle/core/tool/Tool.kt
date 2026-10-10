package dev.spindle.core.tool

import dev.spindle.core.model.FileEdit
import dev.spindle.core.model.Session
import dev.spindle.core.model.SessionId
import dev.spindle.core.model.TodoItem
import dev.spindle.core.store.SnapshotStore
import kotlinx.serialization.json.JsonObject

/** Everything a tool needs that the loop owns. */
interface ToolContext {
    val sessionId: SessionId
    /** Absolute, sandboxed working directory for this session. */
    val cwd: java.nio.file.Path
    /** The owning session (for cwd/model/agent defaults). */
    val session: Session
    /**
     * Pre-edit snapshot store. Null disables snapshots (the default, so tools
     * stay usable against lightweight contexts); when present, mutating tools
     * record a copy before they change a file.
     */
    val snapshots: SnapshotStore? get() = null
    /** Returns true if the user approved this invocation. */
    suspend fun requestPermission(tool: String, detail: String, pattern: String? = null): Boolean
    /** Ask the user a question and suspend until they answer. */
    suspend fun ask(question: String, options: List<String>, multiple: Boolean = false): List<String>
    /** Emit out-of-band events (progress, diff, etc.) to the session stream. */
    fun emit(event: ToolProgress)
    /** Throws [kotlinx.coroutines.CancellationException] if the run was aborted. */
    suspend fun checkAborted()
    /** Persist the session's todo list (used by the todo tool). */
    suspend fun setTodos(todos: List<TodoItem>)
    suspend fun todos(): List<TodoItem>
    /** Spawn a subagent and await its final answer. */
    suspend fun subagent(spec: SubagentSpec): SubagentResult
}

/** A subagent invocation requested by the `task` tool. */
data class SubagentSpec(
    val description: String,
    val prompt: String,
    /** Preferred agent name (e.g. "general", "explore"); null = default. */
    val agent: String? = null,
    val model: String? = null,
)

data class SubagentResult(
    val sessionId: SessionId,
    val text: String,
    val ok: Boolean,
    /** Token usage rolled up from the child session. */
    val childTokens: Int = 0,
    val costUsd: Double = 0.0,
)

/** Pure subagent entry point so tools never depend on the loop directly. */
fun interface SubagentRunner {
    suspend fun run(parent: ToolContext, spec: SubagentSpec): SubagentResult
}

data class ToolProgress(val message: String, val fraction: Double? = null)

data class ToolOutcome(
    val output: String,
    val isError: Boolean = false,
    val diff: String? = null,
    val metadata: Map<String, String> = emptyMap(),
    /** Structured change for single-file tools; the loop emits it as `FileEdited`. */
    val edit: FileEdit? = null,
    /** Snapshot recorded before [edit] was applied, when the file existed. */
    val snapshotId: String? = null,
    /** Structured changes for multi-file tools (e.g. `apply_patch`); supersedes [edit]. */
    val edits: List<ToolEdit> = emptyList(),
)

/** One structured change plus the snapshot that can restore the pre-edit file. */
data class ToolEdit(
    val edit: FileEdit,
    val snapshotId: String? = null,
    /** Path the snapshot belongs to when it differs from [FileEdit.path] (moves). */
    val snapshotPath: String? = null,
)

/** Default per-tool execution budget in milliseconds; `0` disables the timeout. */
const val DEFAULT_TOOL_TIMEOUT_MS = 120_000L

interface Tool {
    val spec: dev.spindle.core.provider.ToolSpec
    /**
     * Per-invocation wall-clock budget the loop enforces around [run]. `0` (or
     * negative) means no loop-level timeout: the tool owns its own deadline
     * (e.g. `bash`'s per-call `timeoutMs`, `task`'s subagent budget).
     */
    val timeoutMs: Long get() = DEFAULT_TOOL_TIMEOUT_MS
    suspend fun run(input: JsonObject, ctx: ToolContext): ToolOutcome
}

class ToolRegistry(tools: List<Tool>) {
    private val byName: Map<String, Tool> = tools.associateBy { it.spec.name }
    val specs: List<dev.spindle.core.provider.ToolSpec> get() = byName.values.map { it.spec }
    fun get(name: String): Tool? = byName[name]
    fun all(): List<Tool> = byName.values.toList()
    /** A registry with the given tool names removed (for restricted agents). */
    fun without(names: Set<String>): ToolRegistry = ToolRegistry(all().filter { it.spec.name !in names })
    fun with(extra: List<Tool>): ToolRegistry = ToolRegistry(all() + extra)
}

