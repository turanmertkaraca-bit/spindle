package dev.spindle.core.agent

/** Agent behaviour knobs. Mirrors opencode's agent concept, minus the plugins. */
data class AgentConfig(
    val name: String = "build",
    /** null = unlimited. */
    val maxSteps: Int? = null,
    val temperature: Double? = null,
    val reasoningEffort: String? = null,
    val systemPrompt: String = DEFAULT_SYSTEM,
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
        """.trimIndent()

        val PLAN_SYSTEM = """
            You are a planning agent. You may read and search, but you must not modify
            files or run commands that change state. Produce a concrete plan: files to
            touch, the order, and how each step will be verified. Do not implement.
        """.trimIndent()
    }
}
