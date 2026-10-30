package dev.spindle.core.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AgentConfigTest {

    @Test
    fun `goal is a resolvable primary agent`() {
        val cfg = AgentConfig.byName("goal")
        assertEquals("goal", cfg.name)
        assertNotNull(cfg.systemPrompt)
        assertTrue(cfg.systemPrompt.contains("goal".lowercase()), "system prompt must mention the goal")
        assertTrue(cfg.maxRetries > AgentConfig.BUILD.maxRetries, "goal gets a higher retry budget")
        assertNotNull(cfg.maxRunMillis, "goal needs a wall-clock backstop")
    }

    @Test
    fun `goal resolves case-insensitively and falls back to build`() {
        assertEquals("goal", AgentConfig.byName("GOAL").name)
        assertEquals("build", AgentConfig.byName("nope").name)
        assertEquals("build", AgentConfig.byName("").name)
    }

    @Test
    fun `goal differs from plan`() {
        assertNotEquals(AgentConfig.PRIMARY["goal"], AgentConfig.PRIMARY["plan"])
    }
}
