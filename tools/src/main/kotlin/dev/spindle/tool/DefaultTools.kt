package dev.spindle.tool

import dev.spindle.core.tool.ToolRegistry

/** The built-in tool set wired into the agent loop. */
object DefaultTools {
    fun registry(): ToolRegistry = ToolRegistry(
        listOf(
            ReadTool(),
            WriteTool(),
            EditTool(),
            BashTool(),
            ApplyPatchTool(),
            GlobTool(),
            GrepTool(),
            TodoWriteTool(),
            WebFetchTool(),
            QuestionTool(),
            TaskTool(),
        ),
    )
}
