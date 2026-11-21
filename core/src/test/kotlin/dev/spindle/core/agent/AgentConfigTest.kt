package dev.spindle.core.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentConfigTest {

    @Test
    fun `goal is a resolvable primary agent`() {
        val cfg = AgentConfig.byName("goal")
        assertEquals("goal", cfg.name)
        assertNotNull(cfg.systemPrompt)
        assertTrue(cfg.systemPrompt.contains("goal".lowercase()), "system prompt must mention the goal")
        assertTrue(cfg.maxRetries > AgentConfig.BUILD.maxRetries, "goal gets a higher retry budget")
        // Goal mode states "no clock" explicitly (`0`); so does every other agent
        // now, via the `null` default. Either way, it must not be a 30-minute timer.
        assertNull(
            RunClock.windowMs(cfg),
            "goal must run until interrupted; a wall clock is the bug, not the backstop",
        )
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
