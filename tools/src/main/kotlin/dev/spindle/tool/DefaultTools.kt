package dev.spindle.tool

import dev.spindle.core.tool.ShellExecutor
import dev.spindle.core.tool.ToolRegistry

/** The built-in tool set wired into the agent loop. */
object DefaultTools {
    fun registry(shell: ShellExecutor = HostShellExecutor()): ToolRegistry = ToolRegistry(
        listOf(
            ReadTool(),
            WriteTool(),
            EditTool(),
            BashTool(shell),
            ApplyPatchTool(),
            GlobTool(),
            GrepTool(),
            TodoWriteTool(),
            WebFetchTool(),
            WebSearchTool(),
            QuestionTool(),
            TaskTool(),
        ),
    )
}
