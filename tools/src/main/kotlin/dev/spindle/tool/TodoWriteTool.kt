package dev.spindle.tool

import dev.spindle.core.provider.ToolSpec
import dev.spindle.core.tool.Tool
import dev.spindle.core.tool.ToolContext
import dev.spindle.core.tool.ToolOutcome
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Accept a todo list and render it as a checklist. Persistence is the session
 * store's job for now, so this tool only echoes the rendered list back.
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
        if (array.isEmpty()) return ToolOutcome("(no todos)")

        val rendered = StringBuilder()
        var index = 0
        for (element in array) {
            index++
            val todo = element as? JsonObject
                ?: return ToolOutcome("todos[$index] must be an object", isError = true)
            val content = todo.stringOrNull("content")
                ?: return ToolOutcome("todos[$index].content is required", isError = true)
            val status = parseStatus(todo.stringOrNull("status"))
            rendered.append("- ").append(marker(status)).append(' ').append(content).append('\n')
        }

        val output = rendered.toString().trimEnd('\n')
        return ToolOutcome(
            output = output,
            metadata = mapOf("count" to array.size.toString()),
        )
    }

    private fun parseStatus(raw: String?): Status = when (raw?.lowercase()?.replace('-', '_')) {
        null, "", "pending" -> Status.PENDING
        "in_progress", "inprogress", "active" -> Status.IN_PROGRESS
        "done", "completed", "complete" -> Status.DONE
        "cancelled", "canceled" -> Status.CANCELLED
        else -> Status.PENDING
    }

    private fun marker(status: Status): String = when (status) {
        Status.PENDING -> "[ ]"
        Status.IN_PROGRESS -> "[~]"
        Status.DONE -> "[x]"
        Status.CANCELLED -> "[-]"
    }

    private enum class Status { PENDING, IN_PROGRESS, DONE, CANCELLED }
}
