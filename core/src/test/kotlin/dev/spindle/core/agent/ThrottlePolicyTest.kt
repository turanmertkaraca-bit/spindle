package dev.spindle.core.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Calibrated concurrency.
 *
 * Both caps used to be constants — `7` for subagents and `cores - 2` for guest
 * processes — so neither came from the device. A small phone over-admitted (and
 * the low-memory killer took the app down); a large phone under-admitted (the
 * owner asked for more than ten subagents and got seven).
 *
 * The expected values below are the *verified* arithmetic of
 * [ThrottlePolicy], not guesses: each case names the hardware it models.
 */
class ThrottlePolicyTest {

    private fun budget(
        cores: Int = 8,
        maxHeapMb: Int = 512,
        usedHeapMb: Int = 128,
        availMemMb: Int = 4_096,
        lowMemory: Boolean = false,
    ) = ThrottlePolicy.DeviceBudget(cores, maxHeapMb, usedHeapMb, availMemMb, lowMemory)

    // ---- subagents -------------------------------------------------------

    @Test
    fun `a big phone gets more than ten subagents`() {
        // The owner's headline request: an 8-core, 8 GB phone.
        val n = ThrottlePolicy.maxSubagents(budget())
        assertTrue(n > 10, "an 8 GB phone should allow more than 10 subagents, got $n")
    }

    @Test
    fun `a typical 4 GB phone also gets more than ten`() {
        val n = ThrottlePolicy.maxSubagents(budget(maxHeapMb = 256, usedHeapMb = 128, availMemMb = 2_048))
        assertTrue(n > 10, "a 4 GB phone should exceed ten, got $n")
    }

    @Test
    fun `a 2 GB phone gets the floor, because ten children there is how you get killed`() {
        // 192 MB heap with 160 in use, ~900 MB free system RAM.
        val tiny = ThrottlePolicy.maxSubagents(budget(cores = 2, maxHeapMb = 192, usedHeapMb = 160, availMemMb = 900))
        assertEquals(ThrottlePolicy.MIN_SUBAGENTS, tiny)
    }

    @Test
    fun `using more heap reduces the allowance`() {
        val idle = ThrottlePolicy.maxSubagents(budget(usedHeapMb = 64))
        val busy = ThrottlePolicy.maxSubagents(budget(usedHeapMb = 400))
        assertTrue(busy < idle, "a fuller heap must admit fewer children: busy=$busy idle=$idle")
    }

    @Test
    fun `less free system ram reduces the allowance`() {
        // The low-memory-killer signal, isolated from heap: same app heap, less
        // system RAM. This is the bound that decides whether Android kills the app.
        val roomy = ThrottlePolicy.maxSubagents(budget(availMemMb = 4_096))
        val tight = ThrottlePolicy.maxSubagents(budget(availMemMb = 900))
        assertTrue(tight < roomy, "less free system RAM must admit fewer: tight=$tight roomy=$roomy")
    }

    @Test
    fun `low memory pins the minimum`() {
        // Even a device reporting a huge heap must back off once the platform says
        // it is under pressure: that flag is the last warning before a kill.
        assertEquals(
            ThrottlePolicy.MIN_SUBAGENTS,
            ThrottlePolicy.maxSubagents(budget(maxHeapMb = 8_192, usedHeapMb = 0, availMemMb = 65_536, lowMemory = true)),
        )
    }

    @Test
    fun `the ceiling is enforced however large the device`() {
        val huge = ThrottlePolicy.maxSubagents(
            budget(cores = 64, maxHeapMb = 65_536, usedHeapMb = 0, availMemMb = 65_536),
        )
        assertEquals(ThrottlePolicy.MAX_SUBAGENTS, huge, "an absurd device must still respect the ceiling")
    }

    @Test
    fun `an unreadable device falls back to a usable number, never a crash`() {
        val n = ThrottlePolicy.maxSubagents(ThrottlePolicy.DeviceBudget.conservative())
        assertTrue(
            n in ThrottlePolicy.MIN_SUBAGENTS..ThrottlePolicy.MAX_SUBAGENTS,
            "unknown hardware must still produce a usable number, got $n",
        )
    }

    @Test
    fun `a heap already past its maximum clamps to the floor, never negative`() {
        // `usedHeapMb > maxHeapMb` is possible under accounting skew. It must not
        // go negative and silently disable fan-out.
        val n = ThrottlePolicy.maxSubagents(budget(maxHeapMb = 128, usedHeapMb = 400))
        assertEquals(ThrottlePolicy.MIN_SUBAGENTS, n)
    }

    @Test
    fun `the result is never zero or negative for any plausible device`() {
        // Exhaustive over a wide grid: a zero cap would deadlock every `task` call.
        for (cores in 1..32 step 3) {
            for (heap in listOf(96, 128, 192, 256, 512, 1_024)) {
                for (used in listOf(0, 64, 128, 200, 400)) {
                    for (avail in listOf(0, 128, 512, 2_048, 8_192)) {
                        val n = ThrottlePolicy.maxSubagents(budget(cores, heap, used, avail))
                        assertTrue(
                            n in ThrottlePolicy.MIN_SUBAGENTS..ThrottlePolicy.MAX_SUBAGENTS,
                            "cores=$cores heap=$used/$heap avail=$avail produced $n",
                        )
                    }
                }
            }
        }
    }

    // ---- guest processes / terminal --------------------------------------

    @Test
    fun `guest processes scale with cores but leave cores for the ui`() {
        assertEquals(2, ThrottlePolicy.maxGuestProcesses(budget(cores = 4)), "4 cores - 2 reserved")
        assertEquals(6, ThrottlePolicy.maxGuestProcesses(budget(cores = 8)))
    }

    @Test
    fun `a small-core device still gets one guest slot`() {
        assertEquals(ThrottlePolicy.MIN_GUEST_SLOTS, ThrottlePolicy.maxGuestProcesses(budget(cores = 1)))
        assertEquals(ThrottlePolicy.MIN_GUEST_SLOTS, ThrottlePolicy.maxGuestProcesses(budget(cores = 2)))
    }

    /**
     * The terminal-lock defect, stated as a property.
     *
     * Agent commands and the interactive terminal shared one flat pool, so enough
     * concurrent `bash` calls starved `openPty` and the screen said "terminal
     * unavailable". Restarting did not help, because the new run immediately took
     * the slots again.
     */
    @Test
    fun `the terminal always has a slot that commands cannot consume`() {
        for (cores in 1..16) {
            val b = budget(cores = cores)
            val total = ThrottlePolicy.maxGuestProcesses(b)
            val reserved = ThrottlePolicy.reservedForTerminal(b)
            assertTrue(reserved <= total, "reserved ($reserved) cannot exceed the pool ($total) on $cores cores")
            if (total > ThrottlePolicy.MIN_GUEST_SLOTS) {
                assertEquals(1, reserved, "a pool with room must hold one slot for the terminal")
                assertTrue(
                    total - reserved >= 1,
                    "commands must still get a slot on $cores cores (total=$total reserved=$reserved)",
                )
            } else {
                // Reserving the only slot would starve every command, so the
                // terminal shares it instead.
                assertEquals(0, reserved, "a single-slot device must not reserve its only slot")
            }
        }
    }

    @Test
    fun `low memory also throttles guest work`() {
        assertEquals(
            ThrottlePolicy.MIN_GUEST_SLOTS,
            ThrottlePolicy.maxGuestProcesses(budget(cores = 8, lowMemory = true)),
        )
    }

    @Test
    fun `guest count never exceeds the ceiling on a many-core device`() {
        assertEquals(
            ThrottlePolicy.MAX_GUEST_SLOTS,
            ThrottlePolicy.maxGuestProcesses(budget(cores = 64, availMemMb = 65_536)),
        )
    }

    @Test
    fun `guest slots are bounded by free memory as well as cores`() {
        // Plenty of cores, almost no RAM: memory must win.
        val starved = ThrottlePolicy.maxGuestProcesses(budget(cores = 32, availMemMb = 256))
        assertEquals(2, starved, "256 MB of free RAM supports two 128 MB guests")
    }

    // ---- the diagnostics line -------------------------------------------

    @Test
    fun `the calibration is explainable in one line`() {
        val line = ThrottlePolicy.describe(budget())
        assertTrue(line.contains("subagents"), line)
        assertTrue(line.contains("guest processes"), line)
        assertTrue(line.contains("cores 8"), "it must name the hardware it measured: $line")
        assertTrue(line.contains("free RAM"), line)
    }

    @Test
    fun `the diagnostics line reports the terminal reservation when there is one`() {
        val line = ThrottlePolicy.describe(budget(cores = 8))
        assertTrue(line.contains("held for the terminal"), "a user debugging a stuck terminal needs this: $line")
    }

    @Test
    fun `low memory is called out in the diagnostics line`() {
        val line = ThrottlePolicy.describe(budget(lowMemory = true))
        assertTrue(line.contains("LOW MEMORY"), "a user debugging a kill needs to see this: $line")
    }
}
