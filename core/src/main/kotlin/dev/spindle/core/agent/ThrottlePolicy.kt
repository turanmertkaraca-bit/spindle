package dev.spindle.core.agent

/**
 * How much work this device can actually take, and where the limits come from.
 *
 * ## Why this exists
 *
 * Concurrency was two hardcoded numbers: `7` for subagents and `cores - 2` for
 * guest processes. Neither came from the device, so both were wrong in opposite
 * directions depending on the phone:
 *
 * - **Too high on a small phone.** Seven children each holding a conversation and
 *   a streamed answer is a lot of heap on a 2 GB device. Android then kills the
 *   *app*, which reads to the user as "it crashed on its own".
 * - **Too low on a big phone.** The owner asked for *more than ten* subagents at
 *   once. A modern phone can, but the constant said 7 regardless.
 *
 * ## Subagents are bounded by memory, not cores
 *
 * A child is mostly waiting on the network; its cost is heap (history, parts, a
 * report with tool results), not CPU. Android does not kill an app for using CPU
 * — it kills it for memory. So the subagent ceiling is derived from memory, with
 * **two independent bounds**, whichever is tighter:
 *
 *  1. **System free RAM** ([DeviceBudget.availMemMb]). This is the signal the
 *     low-memory killer actually uses, so it is the honest one.
 *  2. **App heap headroom** (`maxHeapMb - usedHeapMb`). Even with free system RAM,
 *     the app cannot exceed its own heap cap; crossing it is an `OutOfMemoryError`
 *     in the parent run, not a graceful degradation.
 *
 * `MemoryInfo.lowMemory` is deliberately *not* the primary signal: it is a coarse
 * threshold that flips at the point Android is already choosing whom to kill —
 * far too late to shed load. It is used only as a hard floor trigger.
 *
 * Guest processes (shell commands, the terminal) are the opposite: CPU-bound, so
 * they are core-derived. That is `ResourcePolicy`'s job; this object supplies it
 * the calibrated number.
 *
 * ## Pure on purpose
 *
 * Everything here is arithmetic on integers, so it is a JVM test rather than a
 * property of whatever the phone happened to report. The Android side gathers a
 * [DeviceBudget] and calls in; it makes no decisions.
 */
object ThrottlePolicy {

    /**
     * What the device reports. Every field has a default because a device that
     * cannot be read must be treated as modest — never as beefy.
     */
    data class DeviceBudget(
        /** `Runtime.availableProcessors()`. */
        val cores: Int = 4,
        /** Heap the app may grow to (`Runtime.maxMemory()`), in MiB. */
        val maxHeapMb: Int = 256,
        /** Heap currently in use (`total - free`), in MiB. */
        val usedHeapMb: Int = 0,
        /** System-wide free memory (`ActivityManager.MemoryInfo.availMem`), MiB. */
        val availMemMb: Int = 512,
        /** True once the platform reports memory pressure (advisory, see the class note). */
        val lowMemory: Boolean = false,
    ) {
        companion object {
            /**
             * The budget assumed when the device cannot be measured.
             *
             * Deliberately modest rather than generous: under-admitting costs
             * throughput, over-admitting costs the whole app. These are the floor
             * of the current Android population.
             */
            fun conservative(): DeviceBudget = DeviceBudget()
        }
    }

    // ---- subagents -------------------------------------------------------

    /**
     * Subagents that may run at once.
     *
     * The budget is a *permit*, not a measurement: a child is admitted before
     * anyone knows how much it will allocate, so each permit must be a
     * conservative reservation. Both reservations are deliberately generous
     * relative to a typical child:
     *
     * - [SUBAGENT_SYSTEM_MB] of system RAM per child, because the low-memory
     *   killer is what ends the app and it looks at system RAM.
     * - [SUBAGENT_HEAP_MB] of app heap per child — roughly a compacted history
     *   (~15k kept tokens) plus a streamed answer and attached tool results.
     *
     * Two hard bounds matter as much as the arithmetic:
     *  - [MIN_SUBAGENTS]: below this fan-out is useless, and four children that
     *    finish beat twelve that queue forever.
     *  - [MAX_SUBAGENTS]: above this, provider rate limits and the parent's own
     *    context are the bottleneck, and the UI has nothing readable to show.
     */
    fun maxSubagents(budget: DeviceBudget): Int {
        if (budget.lowMemory) return MIN_SUBAGENTS

        val heapHeadroomMb = (budget.maxHeapMb - budget.usedHeapMb).coerceAtLeast(0)
        val byHeap = heapHeadroomMb / SUBAGENT_HEAP_MB
        val bySystemRam = budget.availMemMb / SUBAGENT_SYSTEM_MB
        // A CPU sanity bound: a child doing real tool work is not free on CPU
        // either, and a busy phone stops being usable long before this trips.
        val byCores = budget.cores * SUBAGENTS_PER_CORE

        val raw = minOf(byHeap, bySystemRam, byCores)
        return raw.coerceIn(MIN_SUBAGENTS, MAX_SUBAGENTS)
    }

    /** App heap reserved per live subagent, in MiB. */
    const val SUBAGENT_HEAP_MB = 8

    /** System RAM reserved per live subagent, in MiB (the low-memory-killer signal). */
    const val SUBAGENT_SYSTEM_MB = 64

    /** Children per core, as a CPU sanity bound. */
    const val SUBAGENTS_PER_CORE = 4

    const val MIN_SUBAGENTS = 4

    /** Ceiling regardless of how much memory the device has. */
    const val MAX_SUBAGENTS = 24

    // ---- guest processes -------------------------------------------------

    /**
     * Guest processes (shell commands, the terminal) that may run at once.
     *
     * CPU-bound, so this is core-derived. [GUEST_RESERVED_CORES] are held back for
     * the UI thread, the provider stream and the foreground service: a guest that
     * owns every core makes the app itself janky, which is what "it feels slow"
     * was. The result is never below [MIN_GUEST_SLOTS] (a one-core device must
     * still run a command) and never above [MAX_GUEST_SLOTS] (past that it is not
     * concurrency the user wants, it is a queue).
     */
    fun maxGuestProcesses(budget: DeviceBudget): Int {
        if (budget.lowMemory) return MIN_GUEST_SLOTS
        val byCores = budget.cores - GUEST_RESERVED_CORES
        val byMemory = budget.availMemMb / GUEST_HEAP_MB
        return minOf(byCores, byMemory).coerceIn(MIN_GUEST_SLOTS, MAX_GUEST_SLOTS)
    }

    const val GUEST_RESERVED_CORES = 2
    const val MIN_GUEST_SLOTS = 1
    const val MAX_GUEST_SLOTS = 8

    /** System RAM assumed available to one guest process, in MiB. */
    const val GUEST_HEAP_MB = 128

    /**
     * Slots that non-interactive commands can never take.
     *
     * **This is the terminal-lock fix.** Agent commands and the interactive
     * terminal shared one flat pool, so a handful of concurrent `bash` calls could
     * take every permit; `openPty` then waited out its timeout and the screen said
     * "terminal unavailable". Restarting the app did not help, because the new run
     * immediately took the slots again — which is exactly why "restarting doesn't
     * fix it" was reported.
     *
     * Reserving one slot means the terminal can always open while the agent is
     * hammering the shell. Zero on a device that admits only one process, since
     * reserving the only slot would starve commands completely.
     */
    fun reservedForTerminal(budget: DeviceBudget): Int =
        if (maxGuestProcesses(budget) > MIN_GUEST_SLOTS) 1 else 0

    // ---- explanation -----------------------------------------------------

    /**
     * One line naming the calibrated numbers and why, for Diagnostics.
     *
     * Calibration is only useful if the result is inspectable: a number nobody can
     * see is indistinguishable from a constant someone guessed.
     */
    fun describe(budget: DeviceBudget): String {
        val subs = maxSubagents(budget)
        val guests = maxGuestProcesses(budget)
        val reserve = reservedForTerminal(budget)
        return buildString {
            append("throttle: $subs subagents · $guests guest processes")
            if (reserve > 0) append(" ($reserve held for the terminal)")
            append(" · ")
            append(
                "cores ${budget.cores}, heap ${budget.usedHeapMb}/${budget.maxHeapMb} MB, " +
                    "free RAM ${budget.availMemMb} MB",
            )
            if (budget.lowMemory) append(" · LOW MEMORY: admitting the minimum")
        }
    }
}
