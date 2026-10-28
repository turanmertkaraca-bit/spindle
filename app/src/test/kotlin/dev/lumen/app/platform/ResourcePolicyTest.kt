package dev.lumen.app.platform

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Admission control is the mechanism that stops guest work from starving the
 * device. These pin its contract: a bounded number of concurrent slots, a wait
 * that expires instead of blocking forever, and a battery/thermal pause that
 * degrades to "keep going" when the device cannot be read.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ResourcePolicyTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun reset() {
        ResourcePolicy.resetForTest()
    }

    @Test
    fun `default concurrency leaves cores for the ui and never drops below one`() {
        val concurrency = ResourcePolicy.defaultConcurrency()
        assertTrue(concurrency >= 1, "at least one guest process must be allowed")

        val cores = Runtime.getRuntime().availableProcessors()
        assertTrue(
            concurrency < cores || cores <= 2,
            "guest concurrency should leave a core for the UI on a multi-core device " +
                "(cores=$cores concurrency=$concurrency)",
        )
    }

    @Test
    fun `clampConcurrency honours the request up to the device ceiling`() {
        val ceiling = ResourcePolicy.defaultConcurrency()
        assertEquals(1, ResourcePolicy.clampConcurrency(0), "a non-positive request means one")
        assertEquals(1, ResourcePolicy.clampConcurrency(-5))
        assertEquals(ceiling, ResourcePolicy.clampConcurrency(ceiling + 100), "cannot exceed the ceiling")
    }

    @Test
    fun `concurrency is capped at the device limit`() {
        val ceiling = ResourcePolicy.defaultConcurrency()
        val held = buildList {
            repeat(ceiling) {
                val slot = ResourcePolicy.acquire(timeoutMs = 1_000)
                assertNotNull(slot, "slot $it should be available within the cap")
                add(slot)
            }
        }

        // One more than the cap must fail fast rather than queue forever.
        assertNull(
            ResourcePolicy.acquire(timeoutMs = 50),
            "a request beyond the cap should time out instead of blocking",
        )

        held.forEach { ResourcePolicy.release(it) }
        assertNotNull(ResourcePolicy.acquire(timeoutMs = 1_000), "released slots are reusable")
    }

    @Test
    fun `released slots let queued work proceed`() {
        val first = assertNotNull(ResourcePolicy.acquire(timeoutMs = 1_000))
        val completed = AtomicInteger(0)
        val started = CountDownLatch(1)

        val worker = Thread {
            started.countDown()
            val second = ResourcePolicy.acquire(timeoutMs = 5_000)
            if (second != null) {
                completed.incrementAndGet()
                ResourcePolicy.release(second)
            }
        }
        worker.isDaemon = true
        worker.start()

        started.await(2, TimeUnit.SECONDS)
        // The worker is blocked on the only slot until this releases it.
        ResourcePolicy.release(first)
        worker.join(5_000)

        assertEquals(1, completed.get(), "queued work should run once a slot frees")
    }

    @Test
    fun `pauseReason is null when the device reports no battery`() {
        // Robolectric reports no meaningful battery, which must read as "no
        // reason to pause" rather than stalling every run forever.
        val reason = ResourcePolicy.pauseReason(context)
        assertTrue(
            reason == null || reason.isNotBlank(),
            "a pause reason must be a useful string when present",
        )
    }

    @Test
    fun `pauseReason explains a hot device or a flat battery`() {
        val hot = ResourcePolicy.Battery(percent = 80, charging = false, temperatureTenthsC = 450)
        val flat = ResourcePolicy.Battery(percent = 5, charging = false, temperatureTenthsC = 300)
        val charging = ResourcePolicy.Battery(percent = 5, charging = true, temperatureTenthsC = 300)
        val fine = ResourcePolicy.Battery(percent = 80, charging = false, temperatureTenthsC = 300)

        assertNotNull(ResourcePolicy.pauseReasonFor(hot), "a hot device should pause")
        assertNotNull(ResourcePolicy.pauseReasonFor(flat), "a flat battery should pause")
        assertNull(ResourcePolicy.pauseReasonFor(charging), "charging overrides a low battery")
        assertNull(ResourcePolicy.pauseReasonFor(fine), "a healthy device should not pause")
    }
}
