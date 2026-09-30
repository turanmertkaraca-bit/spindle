package dev.spindle.core.agent

import dev.spindle.core.model.Usage
import dev.spindle.core.provider.ChatRequest
import dev.spindle.core.provider.ModelInfo
import dev.spindle.core.provider.WireImage
import dev.spindle.core.provider.WireMessage
import dev.spindle.core.model.Message
import dev.spindle.core.model.Part
import dev.spindle.core.model.Role

object Wire {
    /** Compute cost for a usage record given model pricing. */
    fun cost(model: ModelInfo, usage: Usage): Double {
        val miss = (usage.inputTokens).coerceAtLeast(0) / 1_000_000.0
        val out = usage.outputTokens / 1_000_000.0
        val read = usage.cacheReadTokens / 1_000_000.0
        val write = usage.cacheWriteTokens / 1_000_000.0
        return miss * model.inputCostPerM +
            out * model.outputCostPerM +
            read * model.cacheReadCostPerM +
            write * model.cacheWriteCostPerM
    }

    /** Expand stored messages into provider wire format (assistant + tool results). */
    fun toWire(messages: List<Message>): List<WireMessage> {
        val out = ArrayList<WireMessage>(messages.size * 2)
        for (m in messages) {
            when (m.role) {
                Role.SYSTEM -> out.add(WireMessage(role = "system", text = m.parts.text()))
                Role.USER -> out.add(
                    WireMessage(role = "user", text = m.parts.text(), images = m.parts.images()),
                )
                Role.ASSISTANT -> {
                    val text = m.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }
                    val reasoning = m.parts.filterIsInstance<Part.Reasoning>().joinToString("") { it.text }
                    val calls = m.parts.filterIsInstance<Part.Tool>().map { it.call }
                    out.add(
                        WireMessage(
                            role = "assistant",
                            text = text.ifEmpty { null },
                            reasoning = reasoning.ifEmpty { null },
                            toolCalls = calls,
                        ),
                    )
                    for (t in m.parts.filterIsInstance<Part.Tool>()) {
                        val r = t.result ?: continue
                        out.add(
                            WireMessage(
                                role = "tool",
                                text = r.output,
                                toolCallId = t.call.id,
                                toolName = t.call.name,
                            ),
                        )
                    }
                }
                Role.TOOL -> out.add(
                    WireMessage(role = "tool", text = m.parts.text()),
                )
            }
        }
        return out
    }

    private fun List<Part>.text(): String =
        filterIsInstance<Part.Text>().joinToString("") { it.text }

    /** Inline base64 image parts (mime starting with image/) become wire images, in order. */
    private fun List<Part>.images(): List<WireImage> =
        filterIsInstance<Part.File>().mapNotNull { f ->
            val base64 = f.dataBase64 ?: return@mapNotNull null
            val mime = f.mime ?: return@mapNotNull null
            if (!mime.startsWith("image/")) return@mapNotNull null
            WireImage(mime = mime, base64 = base64)
        }

    fun request(
        model: String,
        system: String,
        messages: List<WireMessage>,
        tools: List<dev.spindle.core.provider.ToolSpec>,
        agent: AgentConfig,
        sessionHint: String?,
    ) = ChatRequest(
        model = model,
        system = system,
        messages = messages,
        tools = tools,
        temperature = agent.temperature,
        reasoningEffort = agent.reasoningEffort,
        sessionHint = sessionHint,
    )
}
