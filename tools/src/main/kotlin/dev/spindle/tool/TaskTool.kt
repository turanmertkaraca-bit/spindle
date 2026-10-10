package dev.spindle.tool

import dev.spindle.core.provider.ToolSpec
import dev.spindle.core.tool.SubagentSpec
import dev.spindle.core.tool.Tool
import dev.spindle.core.tool.ToolContext
import dev.spindle.core.tool.ToolOutcome
import kotlinx.serialization.json.JsonObject
import java.util.Locale

/**
 * Delegate a self-contained task to a subagent. The tool only forwards the
 * request to [ToolContext.subagent]; the agent loop owns spawning and awaiting
 * the child session.
 */
class TaskTool : Tool {
    override val spec = ToolSpec(
        name = "task",
        description = "Delegate a self-contained task to a subagent and return its final " +
            "answer. Use this for broad, independent research or work that would otherwise " +
            "clutter the main context. Available agents — ${TaskAgents.described()}.",
        parametersJson = """
            {
              "type": "object",
              "properties": {
                "description": {
                  "type": "string",
                  "description": "Short 3-5 word description of the task"
                },
                "prompt": {
                  "type": "string",
                  "description": "The task for the subagent to complete"
                },
                "agent": {
                  "type": "string",
                  "enum": [${TaskAgents.names.joinToString(", ") { "\"$it\"" }}],
                  "description": "Which subagent to use; defaults to general"
                },
                "model": {
                  "type": "string",
                  "description": "Optional model override (e.g. provider/model)"
                }
              },
              "required": ["description", "prompt"],
              "additionalProperties": false
            }
        """.trimIndent(),
    )

    // A subagent owns its own runtime budget; the loop must not wrap it.
    override val timeoutMs = 0L

    override suspend fun run(input: JsonObject, ctx: ToolContext): ToolOutcome {
        val description = input.requireString("description")
        val prompt = input.requireString("prompt")
        val agent = input.stringOrNull("agent")
        val model = input.stringOrNull("model")

        val result = ctx.subagent(
            SubagentSpec(description = description, prompt = prompt, agent = agent, model = model),
        )

        val label = agent?.takeIf { it.isNotBlank() } ?: "general"
        val cost = String.format(Locale.ROOT, "%.4f", result.costUsd)
        val header = "[subagent $label · ${result.childTokens} tok · $$cost]"
        return ToolOutcome(
            output = "$header\n${result.text}",
            isError = !result.ok,
            metadata = mapOf(
                "sessionId" to result.sessionId.value,
                "tokens" to result.childTokens.toString(),
                "cost" to result.costUsd.toString(),
            ),
        )
    }
}
