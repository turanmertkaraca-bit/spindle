package dev.spindle.core.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Wall-clock rules, including the distinction the owner asked for.
 *
 * The three cases `null` / `0` / `> 0` mean different things and were previously
 * handled inline as `agent.maxRunMillis ?: MAX` with a `>` comparison. That
 * conflated "unbounded" with "spent": `remainingBudgetMs` returned 0 to mean
 * unlimited, and `Retry.clampToDeadline` reads `<= 0` as spent, so every retry in
 * an unbounded run failed instantly instead of waiting out a 429.
 */
class RunClockTest {

    private fun agent(maxRunMillis: Long?) = AgentConfig(name = "t", maxRunMillis = maxRunMillis)

    @Test
    fun `null uses the ordinary backstop`() {
        assertEquals(AgentLoop.MAX_RUN_WALL_CLOCK_MS, RunClock.windowMs(agent(null)))
        assertFalse(RunClock.isSpent(agent(null), AgentLoop.MAX_RUN_WALL_CLOCK_MS))
        assertTrue(RunClock.isSpent(agent(null), AgentLoop.MAX_RUN_WALL_CLOCK_MS + 1))
    }

    @Test
    fun `zero means NO clock, and is never spent`() {
        assertNull(RunClock.windowMs(agent(0L)))
        // This is the whole point of goal mode: however long it has run, time
        // alone must not end it.
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
    fun `a normal build run still has a bounded window`() {
        assertEquals(
            AgentLoop.MAX_RUN_WALL_CLOCK_MS,
            RunClock.windowMs(AgentConfig.BUILD),
            "only goal mode is unbounded; the ordinary backstop must survive",
        )
    }
}
