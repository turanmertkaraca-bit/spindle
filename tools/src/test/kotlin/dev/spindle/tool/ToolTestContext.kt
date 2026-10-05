package dev.spindle.tool

import dev.spindle.core.model.Session
import dev.spindle.core.model.SessionId
import dev.spindle.core.model.TodoItem
import dev.spindle.core.store.SnapshotStore
import dev.spindle.core.tool.SubagentResult
import dev.spindle.core.tool.SubagentSpec
import dev.spindle.core.tool.ToolContext
import dev.spindle.core.tool.ToolProgress
import java.nio.file.Path

/**
 * Shared test [ToolContext] for the tool tests: a sandbox rooted at [cwd] with a
 * scripted permission decision. Defaults allow every request so tests can focus
 * on tool behaviour; set [permissionAllowed] to false to check denial paths.
 */
internal class ToolTestContext(
    override val cwd: Path,
    private val snapshotStore: SnapshotStore? = null,
) : ToolContext {
    override val sessionId = SessionId("test-session")
    override val snapshots: SnapshotStore? get() = snapshotStore
    override val session = Session(
        id = sessionId,
        cwd = cwd.toString(),
        createdAt = 0L,
        updatedAt = 0L,
    )

    var permissionAllowed: Boolean = true
    var permissionRequests: Int = 0
    var lastPermissionTool: String? = null
    var lastPermissionDetail: String? = null
    var lastPermissionPattern: String? = null

    override suspend fun requestPermission(tool: String, detail: String, pattern: String?): Boolean {
        permissionRequests++
        lastPermissionTool = tool
        lastPermissionDetail = detail
        lastPermissionPattern = pattern
        return permissionAllowed
    }

    override suspend fun ask(question: String, options: List<String>, multiple: Boolean): List<String> = emptyList()

    override fun emit(event: ToolProgress) {}

    override suspend fun checkAborted() {}

    override suspend fun setTodos(todos: List<TodoItem>) {}

    override suspend fun todos(): List<TodoItem> = emptyList()

    override suspend fun subagent(spec: SubagentSpec): SubagentResult =
        SubagentResult(SessionId("child"), "ok", true)
}
