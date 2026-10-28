package dev.lumen.app.platform

import android.content.Context
import android.os.BatteryManager
import android.util.Log
import java.util.concurrent.Semaphore
import kotlin.math.max
import kotlin.math.min

/**
 * Admission control and priority for guest work, so a long agent run cannot
 * starve the phone it runs on.
 *
 * Everything here is deliberately root-free. proot is a ptrace shim: the guest
 * shares the host scheduler, so a cgroup cannot be created without privileges a
 * stock device does not grant. The levers that do work are:
 *
 *  - **Concurrency.** A semaphore caps how many guest processes exist at once.
 *    This is the only one that reliably matters on a low-core device: three
 *    concurrent compiles on four cores turns a phone into a hand warmer.
 *  - **Nice.** Lowering the child's scheduling priority keeps the UI thread
 *    responsive while the guest works. Set host-side, before exec.
 *  - **Battery/thermal pause.** A run parked while the device is hot or nearly
 *    flat is cheaper than a run that finishes and leaves the user without a
 *    phone.
 *
 * Every knob degrades cleanly: if a device refuses a flag or a call, that knob
 * is skipped and the rest still applies. Nothing here throws to the caller.
 */
object ResourcePolicy {

    private const val TAG = "ResourcePolicy"

    /**
     * How many guest processes may run at once. Cores minus a slice: the UI,
     * the provider stream and the notification service all need a core too. A
     * one- or two-core device gets 1, so the guest never owns the whole device.
     */
    fun defaultConcurrency(): Int {
        val cores = Runtime.getRuntime().availableProcessors()
        return when {
            cores <= 2 -> 1
            cores <= 4 -> max(1, cores - 2)
            else -> max(2, cores - 2)
        }
    }

    /**
     * Scheduling niceness for guest work. Positive is lower priority on Linux;
     * 10 is a polite background level that still leaves the guest usable. This
     * is a hint: if the platform ignores it, the run simply behaves as before.
     */
    const val DEFAULT_NICE: Int = 10

    /** Pause a run when the battery drops below this and is not charging. */
    const val LOW_BATTERY_PERCENT: Int = 15

    /** Pause a run when the battery temperature exceeds this, in tenths of °C. */
    const val THERMAL_PAUSE_TENTHS_C: Int = 420

    /**
     * Serialises guest work. Held around a single guest process's lifetime, not
     * across a whole run: a run that makes many short calls should interleave
     * with the UI rather than hold every slot for minutes.
     *
     * Sized lazily so a change to [defaultConcurrency] is picked up on the next
     * process rather than being frozen at class-load time.
     */
    private var gate: Semaphore? = null
    private var gateSize: Int = 0

    /**
     * Acquire a slot, blocking up to [timeoutMs]. Returns null when the wait is
     * exhausted, so the caller can report "busy" instead of queueing forever.
     */
    fun acquire(timeoutMs: Long = 60_000L): Semaphore? {
        val s = gateOrCreate()
        return try {
            if (s.tryAcquire(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) s else null
        } catch (t: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
    }

    /** Release a slot acquired from [acquire]. Idempotent-safe per call. */
    fun release(semaphore: Semaphore) {
        runCatching { semaphore.release() }
    }

    @Synchronized
    private fun gateOrCreate(): Semaphore {
        val size = defaultConcurrency()
        val existing = gate
        if (existing != null && gateSize == size) return existing
        // Resizing mid-flight would strand permits; create a fresh gate and let
        // in-flight holders release into the old one harmlessly.
        return Semaphore(size, true).also {
            gate = it
            gateSize = size
        }
    }

    /** Test hook: drop the cached gate so a changed policy takes effect. */
    @Synchronized
    internal fun resetForTest() {
        gate = null
        gateSize = 0
    }

    /** How many guest processes are running right now (for diagnostics). */
    fun availableSlots(): Int = runCatching { gateOrCreate().availablePermits() }.getOrDefault(0)

    /**
     * A reason the device cannot take more guest work right now, or null when it
     * can. This is advisory: callers should pause, not abort, because both
     * conditions clear on their own.
     */
    fun pauseReason(context: Context): String? {
        return pauseReasonFor(readBattery(context))
    }

    /**
     * The decision half of [pauseReason], split out so the thresholds can be
     * tested without an Android battery. A null [battery] means "could not read
     * the device", which must not pause anything: stalling every run on a device
     * that simply does not report a battery would be worse than running hot.
     */
    internal fun pauseReasonFor(battery: Battery?): String? {
        if (battery == null) return null
        if (battery.percent in 0..LOW_BATTERY_PERCENT && !battery.charging) {
            return "battery ${battery.percent}% and not charging"
        }
        if (battery.temperatureTenthsC >= THERMAL_PAUSE_TENTHS_C) {
            return "device is hot (${battery.temperatureTenthsC / 10}°C)"
        }
        return null
    }

    internal data class Battery(val percent: Int, val charging: Boolean, val temperatureTenthsC: Int)

    /**
     * Read the battery once. Returns null when the device has no battery (an
     * emulator, or a device that does not report one) so callers treat "unknown"
     * as "no reason to pause" rather than stalling forever.
     */
    internal fun readBattery(context: Context): Battery? = runCatching {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        // -1 means "no battery" (an emulator, or a device that does not report
        // one); the caller treats that as "no reason to pause".
        val percent = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        // Charge state and temperature both come from the sticky broadcast; the
        // BatteryManager property API exposes neither.
        val status = readBatteryStatus(context)
        Battery(percent, charging = status.charging, temperatureTenthsC = status.tenths)
    }.onFailure { Log.w(TAG, "battery read failed", it) }.getOrNull()

    private data class BatteryStatus(val tenths: Int, val charging: Boolean)

    /**
     * Battery temperature and charge state from the sticky ACTION_BATTERY_CHANGED
     * intent. Defaults to "cool and not charging" when the broadcast is
     * unavailable, which means the policy will not pause a device it cannot read.
     */
    private fun readBatteryStatus(context: Context): BatteryStatus = runCatching {
        val intent = context.registerReceiver(
            null,
            android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED),
        )
        if (intent == null) {
            BatteryStatus(tenths = 0, charging = false)
        } else {
            val tenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            BatteryStatus(tenths, charging)
        }
    }.getOrElse { BatteryStatus(tenths = 0, charging = false) }

    /**
     * Clamp a caller's requested concurrency to the device's capacity. A tool
     * that asks for 8 parallel commands on a 4-core phone gets 2, not 8.
     */
    fun clampConcurrency(requested: Int): Int {
        val ceiling = defaultConcurrency()
        return min(max(1, requested), ceiling)
    }
}
