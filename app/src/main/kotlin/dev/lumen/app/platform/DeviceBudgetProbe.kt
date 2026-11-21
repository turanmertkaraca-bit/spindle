package dev.lumen.app.platform

import android.app.ActivityManager
import android.content.Context
import dev.spindle.core.agent.ThrottlePolicy

/**
 * Measures the device and hands the numbers to [ThrottlePolicy].
 *
 * This is the only Android-aware part of the calibration: everything that
 * *decides* a limit is pure Kotlin in `:core` (and therefore a JVM test). Here we
 * read, clamp to sane ranges, and cache briefly.
 *
 * ## Why it is cached
 *
 * `ActivityManager.getMemoryInfo` is a binder call, and the admission path is on
 * the hot path of every guest command and every subagent spawn. A short TTL keeps
 * the cost negligible while still reacting to a device that starts to fill up —
 * the alternative (measure on every spawn) makes the limiter itself a source of
 * jank, which is the thing it is supposed to prevent.
 *
 * `lowMemory` is cached only very briefly (see [LOW_MEMORY_TTL_MS]): it is the
 * signal that Android is about to kill someone, so it must take effect at once,
 * but it must also stop applying as soon as the pressure passes.
 */
internal object DeviceBudgetProbe {

    /** How long a cached reading is trusted. Short enough to react, long enough to be free. */
    const val CACHE_TTL_MS = 2_000L

    /**
     * How long a *low-memory* reading is trusted.
     *
     * Much shorter, because the escape from memory pressure must be noticed
     * quickly: under-calibrating forever after one bad moment would leave the
     * owner with four subagents on a phone that can run twenty.
     */
    const val LOW_MEMORY_TTL_MS = 250L

    private var cached: ThrottlePolicy.DeviceBudget? = null
    private var cachedAtMs = 0L

    private const val MIB = 1024L * 1024L

    /**
     * The current budget. Never throws and never returns null: an unreadable
     * device yields [ThrottlePolicy.DeviceBudget.conservative], because refusing
     * to admit any work because a sensor failed would be worse than admitting a
     * modest amount.
     */
    fun read(context: Context, nowMs: Long = System.currentTimeMillis()): ThrottlePolicy.DeviceBudget {
        val fresh = cached
        // A cached reading is reused only inside the TTL. `lowMemory` gets a much
        // shorter TTL rather than "never cache": caching a low-memory reading
        // would pin the app at the minimum allowance forever, so it would never
        // recover after the pressure passed. Refusing to cache it at all is the
        // opposite bug and costs a binder call on every spawn during a spike.
        val ttl = if (fresh?.lowMemory == true) LOW_MEMORY_TTL_MS else CACHE_TTL_MS
        if (fresh != null && nowMs - cachedAtMs in 0 until ttl) {
            return fresh
        }
        val reading = measure(context)
        cached = reading
        cachedAtMs = nowMs
        return reading
    }

    /** Drop the cache (tests, or after a trim-memory callback). */
    fun invalidate() {
        cached = null
        cachedAtMs = 0L
    }

    private fun measure(context: Context): ThrottlePolicy.DeviceBudget {
        val runtime = Runtime.getRuntime()
        // `maxMemory` is the heap ceiling this process may reach; `totalMemory -
        // freeMemory` is what is actually live. Both are exact for the JVM.
        val maxHeapMb = (runtime.maxMemory() / MIB).toInt()
        val usedHeapMb = ((runtime.totalMemory() - runtime.freeMemory()) / MIB).toInt()
        val cores = runtime.availableProcessors()

        val memory = memoryInfo(context)
        return ThrottlePolicy.DeviceBudget(
            cores = cores.coerceIn(1, 64),
            maxHeapMb = maxHeapMb.coerceIn(32, 4_096),
            usedHeapMb = usedHeapMb.coerceIn(0, 4_096),
            // `availMem` is system-wide free RAM — the number the low-memory
            // killer works from, and therefore the honest bound on how many
            // children will fit *without* the process being reaped.
            availMemMb = ((memory?.availMem ?: 0L) / MIB).toInt().coerceIn(0, 65_536),
            lowMemory = memory?.lowMemory ?: false,
        )
    }

    /** `getMemoryInfo` can throw on an unusual or heavily-restricted device. */
    private fun memoryInfo(context: Context): ActivityManager.MemoryInfo? {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return null
        return runCatching { ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) } }.getOrNull()
    }

    /**
     * The calibrated subagent ceiling for this device.
     *
     * Cached indirectly through [read], so the agent loop can call this on every
     * spawn without paying for a binder round trip each time.
     */
    fun maxSubagents(context: Context): Int =
        ThrottlePolicy.maxSubagents(read(context))

    /** The calibrated guest-process ceiling for this device. */
    fun maxGuestProcesses(context: Context): Int =
        ThrottlePolicy.maxGuestProcesses(read(context))

    /** One-line explanation for Diagnostics. */
    fun describe(context: Context): String = ThrottlePolicy.describe(read(context))
}
