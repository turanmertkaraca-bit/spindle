package dev.spindle.tool

import dev.spindle.core.model.SessionId
import dev.spindle.core.provider.ToolSpec
import dev.spindle.core.tool.SubagentSpec
import dev.spindle.core.tool.Tool
import dev.spindle.core.tool.ToolContext
import dev.spindle.core.tool.ToolOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import java.nio.file.Path
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
        val (report, reportPath) = inlineReport(ctx.cwd, result.sessionId, result.text)
        return ToolOutcome(
            output = "$header\n$report",
            isError = !result.ok,
            metadata = buildMap {
                put("sessionId", result.sessionId.value)
                put("tokens", result.childTokens.toString())
                put("cost", result.costUsd.toString())
                if (reportPath != null) put("reportPath", reportPath)
            },
        )
    }

    /**
     * Bound the child's report before it enters the parent's context.
     *
     * A subagent can read widely and hand back a very large string. That string
     * is persisted in the parent's tool result and re-sent on **every** later
     * turn, so an uncapped report is a permanent context tax, not a one-off
     * cost — the whole point of delegating to a subagent is to keep the parent
     * small. Over-cap text is spilled into the workspace so the parent can
     * `read` it on demand instead of paying for it always.
     *
     * Keeps the head (the findings) and the tail (the conclusion) and drops the
     * middle. A failed write degrades to a plain clip: the report is never lost
     * silently, it just costs more context.
     */
    private suspend fun inlineReport(
        cwd: Path,
        sessionId: SessionId,
        text: String,
    ): Pair<String, String?> {
        if (text.length <= MAX_REPORT_CHARS) return text to null
        val relative = "$SPILL_DIR/${sessionId.value.replace(UNSAFE_CHARS, "_")}.txt"
        val written = runCatching {
            withContext(Dispatchers.IO) {
                val file = cwd.resolve(relative)
                Files.createDirectories(file.parent)
                Files.writeString(file, text)
                true
            }
        }.getOrDefault(false)

        val head = MAX_REPORT_CHARS * 3 / 4
        val tail = MAX_REPORT_CHARS - head
        val preview = text.take(head) + "\n\u2026[truncated]\u2026\n" + text.takeLast(tail)
        return if (written) {
            preview + "\n\n[full report: $relative — read it if you need the middle]" to relative
        } else {
            preview to null
        }
    }

    private companion object {
        /**
         * Characters of the child's report inlined into the parent. Deliberately
         * far below the loop's global 60k tool-output cap: a report is a
         * summary, and the spill file is the escape hatch for detail.
         */
        const val MAX_REPORT_CHARS = 4_000

        const val SPILL_DIR = ".spindle/subagents"

        /** Anything outside this set in a session id is replaced for a safe path. */
        val UNSAFE_CHARS = Regex("[^A-Za-z0-9._-]")
    }
}
