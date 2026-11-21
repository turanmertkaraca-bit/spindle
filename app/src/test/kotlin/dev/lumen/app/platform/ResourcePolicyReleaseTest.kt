package dev.lumen.app.platform

import org.junit.Before
import org.junit.Test
import java.util.concurrent.Semaphore
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Release accounting for the admission gate.
 *
 * Plain JVM only: these use the context-less [ResourcePolicy.acquire] overload,
 * which never touches the battery, and the main-thread guard already degrades to
 * "worker thread" when Looper is a stub. That keeps the suite runnable without
 * Robolectric, whose conscrypt natives are unavailable here.
 *
 * The property under test: a double or unmatched release must never lift the
 * gate's permit count above the device cap.
 */
class ResourcePolicyReleaseTest {

    @Before
    fun setUp() {
        ResourcePolicy.resetForTest()
    }

    @org.junit.After
    fun tearDown() {
        // The adopted budget is process-wide (like the gate), so a test that
        // installs one must not change the calibration another test observes.
        ResourcePolicy.resetForTest()
    }

    /** Acquire every slot the gate has, returning them so the caller can release. */
    private fun fillGate(): List<Semaphore> {
        val cap = ResourcePolicy.defaultConcurrency()
        val held = ArrayList<Semaphore>(cap)
        repeat(cap) {
            val s = ResourcePolicy.acquire(timeoutMs = 0L)
            assertNotNull(s, "expected slot ${it + 1} of $cap to be granted")
            held.add(s)
        }
        return held
    }

    @Test
    fun acquiringFillsGateToExactlyDefaultConcurrency() {
        val cap = ResourcePolicy.defaultConcurrency()
        assertTrue(cap >= 1, "defaultConcurrency must allow at least one slot")

        val held = fillGate()
        try {
            assertEquals(cap, held.size)
            assertNull(
                ResourcePolicy.acquire(timeoutMs = 100L),
                "a gate already at cap must time out rather than over-admit",
            )
        } finally {
            held.forEach { ResourcePolicy.release(it) }
        }
    }

    @Test
    fun availableSlotsIsZeroWhenAllSlotsAreHeld() {
        val held = fillGate()
        try {
            assertEquals(0, ResourcePolicy.availableSlots())
        } finally {
            held.forEach { ResourcePolicy.release(it) }
        }
    }

    @Test
    fun doubleReleaseDoesNotInflateGateAboveCap() {
        val cap = ResourcePolicy.defaultConcurrency()

        val slot = ResourcePolicy.acquire(timeoutMs = 0L)
        assertNotNull(slot)

        ResourcePolicy.release(slot)
        // The regression: a second release of the same slot used to add a permit.
        ResourcePolicy.release(slot)

        val slots = ResourcePolicy.availableSlots()
        assertTrue(
            slots <= cap,
            "double release inflated the gate: $slots permits available, cap is $cap",
        )

        val again = ResourcePolicy.acquire(timeoutMs = 0L)
        assertNotNull(again, "a fresh acquire must still succeed after an over-release")
        assertTrue(ResourcePolicy.availableSlots() <= cap)
        ResourcePolicy.release(again)
    }

    @Test
    fun unmatchedReleaseOfForeignSemaphoreIsANoOp() {
        // Materialise the gate so there is a count to compare against.
        val cap = ResourcePolicy.defaultConcurrency()
        assertEquals(cap, ResourcePolicy.availableSlots())

        val foreign = Semaphore(1)
        ResourcePolicy.release(foreign)

        assertEquals(
            cap,
            ResourcePolicy.availableSlots(),
            "releasing a semaphore never acquired from this gate must not change the count",
        )
        assertEquals(1, foreign.availablePermits(), "the foreign semaphore must not be released")
    }

    /**
     * The terminal can always open, even when the agent has taken every slot it is
     * allowed to take.
     *
     * This is the regression for "the terminal gets locked and restarting doesn't
     * fix it": `openPty` and agent commands draw from one gate, so without a
     * reservation concurrent commands starved the terminal permanently — a restart
     * just re-took the slots.
     *
     * The reservation is enforced on the *context-carrying* path (a real command),
     * so that is what this drives: `context = null` deliberately sees the whole
     * pool, which is also why the pre-existing accounting tests still pass.
     */
    @Test
    fun commandsCannotConsumeTheTerminalsReservedSlot() {
        val budget = dev.spindle.core.agent.ThrottlePolicy.DeviceBudget(
            cores = 8,
            maxHeapMb = 512,
            usedHeapMb = 128,
            availMemMb = 4_096,
        )
        ResourcePolicy.adoptBudget(budget)
        val total = ResourcePolicy.defaultConcurrency()
        val reserve = ResourcePolicy.reservedForTerminal()
        assertTrue(reserve > 0, "an 8-core device with free RAM must reserve a terminal slot")
        assertTrue(total > reserve, "commands must still get slots here (total=$total reserve=$reserve)")

        // Take the whole command-visible capacity through the reserving path.
        val held = ArrayList<Semaphore>()
        try {
            repeat(total - reserve) {
                val slot = ResourcePolicy.acquireReservingForTest(timeoutMs = 1_000L)
                assertNotNull(slot, "command slot ${it + 1} of ${total - reserve} should be granted")
                held.add(slot)
            }
            // One more command must NOT take the terminal's last permit.
            assertNull(
                ResourcePolicy.acquireReservingForTest(timeoutMs = 50L),
                "a command consumed the slot reserved for the terminal",
            )
            // ...and the reserved permit is genuinely still free.
            assertTrue(
                ResourcePolicy.availableSlots() >= reserve,
                "the terminal's $reserve permit(s) were consumed: ${ResourcePolicy.availableSlots()} left",
            )
        } finally {
            held.forEach { ResourcePolicy.release(it) }
        }
    }

    @Test
    fun aSingleSlotDeviceReservesNothingSoCommandsAreNotDeadlocked() {
        // One core, little free RAM: one guest process total. Reserving it would
        // starve every command, so the terminal shares instead.
        val budget = dev.spindle.core.agent.ThrottlePolicy.DeviceBudget(
            cores = 1,
            maxHeapMb = 192,
            usedHeapMb = 160,
            availMemMb = 900,
        )
        ResourcePolicy.adoptBudget(budget)
        assertEquals(1, ResourcePolicy.defaultConcurrency())
        assertEquals(0, ResourcePolicy.reservedForTerminal())

        val slot = ResourcePolicy.acquire(timeoutMs = 0L)
        assertNotNull(slot, "a single-slot device must still admit a command")
        ResourcePolicy.release(slot)
    }

    @Test
    fun releasingAllHeldSlotsRestoresFullCapacity() {
        val cap = ResourcePolicy.defaultConcurrency()
        val held = fillGate()
        assertEquals(0, ResourcePolicy.availableSlots())

        held.forEach { ResourcePolicy.release(it) }
        assertEquals(cap, ResourcePolicy.availableSlots())

        val reacquired = fillGate()
        assertEquals(cap, reacquired.size)
        assertEquals(0, ResourcePolicy.availableSlots())
        reacquired.forEach { ResourcePolicy.release(it) }
    }
}
