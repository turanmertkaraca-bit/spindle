package dev.spindle.core.agent

/** Agent behaviour knobs. Mirrors opencode's agent concept, minus the plugins. */
data class AgentConfig(
    val name: String = "build",
    /** null = unlimited. */
    val maxSteps: Int? = null,
    val temperature: Double? = null,
    val reasoningEffort: String? = null,
    val systemPrompt: String = DEFAULT_SYSTEM,
    /** Tool names this agent may not call. */
    val denyTools: Set<String> = emptySet(),
    /** When false, the `task` tool is not offered — prevents recursive fan-out. */
    val allowSubagents: Boolean = true,
    /** Subagents this agent may spawn (null = all registered). */
    val allowedSubagents: Set<String>? = null,
    /** Retry budget for transient provider failures. */
    val maxRetries: Int = 2,
) {
    companion object {
        val DEFAULT_SYSTEM = """
            You are a coding agent. You work inside a single project directory.

            Use the provided tools to inspect and change files. Prefer small, verifiable
            steps. When a task needs a decision only the user can make, ask with the
            question tool instead of guessing. When you are done, answer briefly.

            Guidelines:
            - Read before you write.
            - Keep edits minimal and targeted.
            - Run tests when the project has them.
            - Never fabricate file contents or command output.
            - For broad or independent research, delegate with the task tool.
        """.trimIndent()

        val PLAN_SYSTEM = """
            You are a planning agent. You may read and search, but you must not modify
            files or run commands that change state. Produce a concrete plan: files to
            touch, the order, and how each step will be verified. Do not implement.
        """.trimIndent()

        /** Read-only explorer subagent. */
        val EXPLORE_SYSTEM = """
            You are an exploration agent. Your job is to answer one question about the
            codebase quickly and precisely, then stop. You may read, search and list
            files. You must not write, edit or run state-changing commands.

            Return a short report: the direct answer first, then the file paths and
            line numbers that support it. Do not propose changes.
        """.trimIndent()

        /** Full-capability general subagent. */
        val GENERAL_SYSTEM = """
            You are a general-purpose subagent. Complete the single task you were given
            and report back concisely. You may read, write, edit and run commands inside
            the project directory.

            Do not ask the user questions — make the most reasonable choice, note it, and
            continue. Finish with a short summary of what you did and any files changed.
        """.trimIndent()

        fun explore() = AgentConfig(
            name = "explore",
            systemPrompt = EXPLORE_SYSTEM,
            denyTools = setOf("write", "edit", "apply_patch", "bash", "task"),
            allowSubagents = false,
            temperature = 0.1,
        )

        fun general() = AgentConfig(
            name = "general",
            systemPrompt = GENERAL_SYSTEM,
            allowSubagents = false,
        )
    }
}
