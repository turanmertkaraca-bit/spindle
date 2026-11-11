package dev.spindle.core.agent

/**
 * Wall-clock rules for a run, as pure functions.
 *
 * These exist as their own object because the three values involved — an
 * unbounded run (`null`), a disabled clock (`0`), and a spent window (`<= 0`
 * remaining) — were previously handled with `?:` and `>` in three separate
 * places inside the loop, and they mean different things:
 *
 * | `maxRunMillis` | meaning |
 * |---|---|
 * | `null` | use [AgentLoop.MAX_RUN_WALL_CLOCK_MS] (the ordinary backstop) |
 * | `0` | **no wall clock at all** — goal mode, which runs until the user stops it |
 * | `> 0` | that many milliseconds |
 *
 * Conflating "unbounded" with "spent" is not hypothetical: `remainingBudgetMs`
 * returned `0` to mean unlimited, and `Retry.clampToDeadline` reads `<= 0` as
 * "spent", so every retry in an unbounded run failed instantly instead of
 * waiting out a 429. Keeping the decision here makes it a JVM test instead of a
 * property of two expressions that happen to agree.
 */
object RunClock {

    /** The effective wall clock for [agent], or null when it has none. */
    fun windowMs(agent: AgentConfig): Long? = when (val m = agent.maxRunMillis) {
        null -> AgentLoop.MAX_RUN_WALL_CLOCK_MS
        0L -> null
        else -> m.takeIf { it > 0 }
    }

    /**
     * True when [elapsedMs] of wall clock has spent [agent]'s window.
     *
     * An agent with no wall clock is never spent, so a goal run cannot be ended by
     * time — only by the user, by the model answering, or by a safety backstop.
     */
    fun isSpent(agent: AgentConfig, elapsedMs: Long): Boolean {
        val window = windowMs(agent) ?: return false
        return elapsedMs > window
    }

    /** Milliseconds left, `0` when spent, or null when there is no clock. */
    fun remainingMs(agent: AgentConfig, elapsedMs: Long): Long? {
        val window = windowMs(agent) ?: return null
        return (window - elapsedMs).coerceAtLeast(0)
    }

    /**
     * The budget name to show the user when a run stops on time, e.g.
     * `"the 30-minute wall-clock budget"`. Null when there is no clock, so a
     * caller cannot accidentally report a limit that does not exist.
     */
    fun describe(agent: AgentConfig): String? {
        val window = windowMs(agent) ?: return null
        val minutes = window / 60_000
        return "the $minutes-minute wall-clock budget"
    }
}
