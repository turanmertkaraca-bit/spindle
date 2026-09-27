package dev.spindle.tool

import dev.spindle.core.provider.ToolSpec
import dev.spindle.core.tool.Tool
import dev.spindle.core.tool.ToolContext
import dev.spindle.core.tool.ToolOutcome
import kotlinx.serialization.json.JsonObject

/**
 * Ask the user a question. With options it behaves as a multiple-choice prompt;
 * without options it is a free-form question.
 */
class QuestionTool : Tool {
    override val spec = ToolSpec(
        name = "question",
        description = "Ask the user a question and wait for their answer. Provide options " +
            "to offer choices; omit them for a free-form answer.",
        parametersJson = """
            {
              "type": "object",
              "properties": {
                "question": {"type": "string", "description": "The question to ask"},
                "options": {
                  "type": "array",
                  "items": {"type": "string"},
                  "description": "Choices the user can pick from; omit for free-form input"
                },
                "multiple": {"type": "boolean", "description": "Allow selecting more than one option"}
              },
              "required": ["question"],
              "additionalProperties": false
            }
        """.trimIndent(),
    )

    override suspend fun run(input: JsonObject, ctx: ToolContext): ToolOutcome {
        val question = input.requireString("question")
        val options = input.stringList("options")
        val multiple = input.boolOrNull("multiple") ?: false

        val answers = ctx.ask(question, options, multiple)
        val output = if (answers.isEmpty()) "(no answer)" else answers.joinToString(", ")
        return ToolOutcome(
            output = output,
            metadata = mapOf("answers" to answers.size.toString()),
        )
    }
}
