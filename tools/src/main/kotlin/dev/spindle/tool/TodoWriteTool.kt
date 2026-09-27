package dev.spindle.tool

import dev.spindle.core.model.Ids
import dev.spindle.core.model.TodoItem
import dev.spindle.core.model.TodoStatus
import dev.spindle.core.provider.ToolSpec
import dev.spindle.core.tool.Tool
import dev.spindle.core.tool.ToolContext
import dev.spindle.core.tool.ToolOutcome
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * Accept a todo list, persist it to the session via [ToolContext.setTodos], and
 * render it as a checklist.
 */
class TodoWriteTool : Tool {
    override val spec = ToolSpec(
        name = "todowrite",
        description = "Create or update the session todo list. Each todo has a content " +
            "string and a status. Returns the rendered checklist.",
        parametersJson = """
            {
              "type": "object",
              "properties": {
                "todos": {
                  "type": "array",
                  "description": "The full todo list",
                  "items": {
                    "type": "object",
                    "properties": {
                      "content": {"type": "string", "description": "What needs to be done"},
                      "status": {
                        "type": "string",
                        "enum": ["pending", "in_progress", "done", "cancelled"],
                        "description": "Current status of the todo"
                      }
                    },
                    "required": ["content", "status"],
                    "additionalProperties": false
                  }
                }
              },
              "required": ["todos"],
              "additionalProperties": false
            }
        """.trimIndent(),
    )

    override suspend fun run(input: JsonObject, ctx: ToolContext): ToolOutcome {
        val array = input["todos"] as? JsonArray
            ?: return ToolOutcome("Missing required array field 'todos'", isError = true)

        val parsed = ArrayList<TodoItem>(array.size)
        val rendered = StringBuilder()
        var index = 0
        for (element in array) {
            index++
            val todo = element as? JsonObject
                ?: return ToolOutcome("todos[$index] must be an object", isError = true)
            val content = todo.stringOrNull("content")
                ?: return ToolOutcome("todos[$index].content is required", isError = true)
            val status = parseStatus(todo.stringOrNull("status"))
            parsed.add(TodoItem(id = Ids.new("todo"), content = content, status = status))
            rendered.append("- ").append(marker(status)).append(' ').append(content).append('\n')
        }

        ctx.setTodos(parsed)

        if (parsed.isEmpty()) return ToolOutcome("(no todos)")
        val output = rendered.toString().trimEnd('\n')
        return ToolOutcome(
            output = output,
            metadata = mapOf("count" to parsed.size.toString()),
        )
    }

    private fun parseStatus(raw: String?): TodoStatus = when (raw?.lowercase()?.replace('-', '_')) {
        null, "", "pending" -> TodoStatus.PENDING
        "in_progress", "inprogress", "active" -> TodoStatus.IN_PROGRESS
        "done", "completed", "complete" -> TodoStatus.DONE
        "cancelled", "canceled" -> TodoStatus.CANCELLED
        else -> TodoStatus.PENDING
    }

    private fun marker(status: TodoStatus): String = when (status) {
        TodoStatus.PENDING -> "[ ]"
        TodoStatus.IN_PROGRESS -> "[~]"
        TodoStatus.DONE -> "[x]"
        TodoStatus.CANCELLED -> "[-]"
    }
}
