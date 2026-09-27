package dev.spindle.tool

/**
 * The subagent kinds the `task` tool may delegate to. Kept in one place so the
 * tool's schema enum, its description, and any future picker stay in sync.
 * Mirrors the factories in [dev.spindle.core.agent.AgentConfig].
 */
object TaskAgents {
    data class Entry(val name: String, val description: String)

    val all: List<Entry> = listOf(
        Entry(
            name = "general",
            description = "Full-capability subagent that can read, write, edit and run commands",
        ),
        Entry(
            name = "explore",
            description = "Read-only subagent that answers one codebase question and cites paths",
        ),
    )

    val names: List<String> = all.map { it.name }

    /** Human-readable list used in the tool description. */
    fun described(): String = all.joinToString("; ") { "${it.name}: ${it.description}" }
}
