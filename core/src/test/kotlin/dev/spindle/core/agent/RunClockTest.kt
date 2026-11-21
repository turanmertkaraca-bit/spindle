package dev.spindle.core.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Wall-clock rules.
 *
 * **No agent has a time limit by default** — the owner asked for the 30-minute
 * build clock to be removed because it ended work that was still progressing. This
 * test pins both halves of that: the default is unbounded, and an agent that
 * explicitly asks for a window still gets one (so the mechanism itself is not
 * dead code that rots).
 */
class RunClockTest {

    private fun agent(maxRunMillis: Long?) = AgentConfig(name = "t", maxRunMillis = maxRunMillis)

    @Test
    fun `the default is NO clock, and is never spent`() {
        // The regression this whole test exists for: `null` used to mean "30
        // minutes", so every build run carried a timer it never asked for.
        assertNull(RunClock.windowMs(agent(null)), "the default must impose no limit")
        assertFalse(RunClock.isSpent(agent(null), 0L))
        assertFalse(RunClock.isSpent(agent(null), 30L * 60L * 1_000L))
        assertFalse(RunClock.isSpent(agent(null), 365L * 24 * 60 * 60 * 1_000L))
        assertNull(RunClock.remainingMs(agent(null), 5_000L))
        assertNull(RunClock.describe(agent(null)), "there is no limit to report")
    }

    @Test
    fun `zero means NO clock, and is never spent`() {
        assertNull(RunClock.windowMs(agent(0L)))
        assertFalse(RunClock.isSpent(agent(0L), 0L))
        assertFalse(RunClock.isSpent(agent(0L), 1_000L))
        assertFalse(RunClock.isSpent(agent(0L), 365L * 24 * 60 * 60 * 1_000))
        assertNull(RunClock.remainingMs(agent(0L), 5_000L))
        assertNull(RunClock.describe(agent(0L)), "there is no limit to report")
    }

    @Test
    fun `a positive window is spent only past its end`() {
        val a = agent(60_000L)
        assertFalse(RunClock.isSpent(a, 59_999L))
        assertFalse(RunClock.isSpent(a, 60_000L), "exactly at the limit is not yet spent")
        assertTrue(RunClock.isSpent(a, 60_001L))
        assertEquals(1_000L, RunClock.remainingMs(a, 59_000L))
        assertEquals(0L, RunClock.remainingMs(a, 60_000L))
        assertEquals(0L, RunClock.remainingMs(a, 90_000L), "never negative")
    }

    @Test
    fun `goal mode is genuinely unbounded`() {
        val goal = AgentConfig.GOAL
        assertNull(
            RunClock.windowMs(goal),
            "the owner asked for goal mode to run until interrupted, not a 60-minute timer",
        )
        assertFalse(RunClock.isSpent(goal, 10L * 60L * 60L * 1_000L))
        assertNull(goal.maxSteps, "a step budget is a timer by another name")
        assertFalse(goal.renewsBudget, "nothing to renew without a window")
    }

    @Test
    fun `every shipped primary agent is unbounded`() {
        // Build included: this is the owner's report. A new agent added to PRIMARY
        // must not silently inherit a clock either, which the default covers.
        for ((name, cfg) in AgentConfig.PRIMARY) {
            assertNull(RunClock.windowMs(cfg), "$name must not have a wall clock by default")
        }
        assertNull(RunClock.windowMs(AgentConfig()), "a bare AgentConfig must impose no limit")
    }
}
