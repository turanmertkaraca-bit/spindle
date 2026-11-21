package dev.lumen.app.platform

import android.content.Context
import android.os.BatteryManager
import android.util.Log
import dev.spindle.core.agent.ThrottlePolicy
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
     * How many guest processes may run at once.
     *
     * **Calibrated, not hardcoded.** This used to be `cores - 2` inline, which
     * ignored memory entirely; [ThrottlePolicy] now derives it from the measured
     * device (cores *and* free RAM), so a phone with many cores and no memory does
     * not get handed eight compilers.
     *
     * Falls back to the core-only formula when no [Context] is available (tests,
     * and any caller that cannot supply one), so behaviour there is unchanged.
     */
    fun defaultConcurrency(): Int = defaultConcurrency(measuredBudget)

    /** The same decision for an explicit measurement; pure enough to test. */
    fun defaultConcurrency(budget: ThrottlePolicy.DeviceBudget?): Int =
        if (budget != null) {
            ThrottlePolicy.maxGuestProcesses(budget).coerceAtLeast(1)
        } else {
            // Core-only fallback. Kept identical to the old formula so a caller
            // without a device reading sees no behaviour change.
            val cores = Runtime.getRuntime().availableProcessors()
            when {
                cores <= 2 -> 1
                cores <= 4 -> max(1, cores - 2)
                else -> max(2, cores - 2)
            }
        }

    /**
     * The last measured device budget, or null before the first measurement.
     *
     * Set by [adoptBudget] whenever a caller supplies a [Context]. Held here rather
     * than recomputed so the gate size and the terminal reservation always agree —
     * two separate readings could disagree and reserve the wrong number of slots.
     */
    @Volatile
    private var measuredBudget: ThrottlePolicy.DeviceBudget? = null

    /** Record a measurement so [defaultConcurrency] and the reservation use it. */
    fun adoptBudget(budget: ThrottlePolicy.DeviceBudget) {
        measuredBudget = budget
    }

    /**
     * Slots that non-interactive [acquire] calls may not take, so the interactive
     * terminal can always open.
     *
     * **The terminal-lock fix.** Commands and the terminal used one flat pool, so
     * a few concurrent `bash` calls could consume every permit; `openPty` blocked
     * for its timeout and the screen reported "terminal unavailable". Because the
     * next run immediately re-took the slots, restarting the app did not help —
     * which is exactly how the owner described it.
     *
     * Zero when the pool has a single slot: reserving the only permit would starve
     * commands entirely.
     */
    fun reservedForTerminal(): Int {
        val budget = measuredBudget ?: return 0
        return ThrottlePolicy.reservedForTerminal(budget)
    }

    /**
     * Scheduling niceness for guest work. Positive is lower priority on Linux;
     * 10 is a polite background level that still leaves the guest usable. This
     * is a hint: if the platform ignores it, the run simply behaves as before.
     */
    const val DEFAULT_NICE: Int = 10

    /** How long an admitted waiter sleeps before re-checking the reserved slot. */
    private const val RESERVE_POLL_MS = 10L

    /** Pause a run when the battery drops below this and is not charging. */
    const val LOW_BATTERY_PERCENT: Int = 15

    /** Pause a run when the battery temperature exceeds this, in tenths of °C. */
    const val THERMAL_PAUSE_TENTHS_C: Int = 420

    /**
     * How far the device must cool below [THERMAL_PAUSE_TENTHS_C] before a
     * paused run is admitted again. Without this the device flaps between
     * paused and running on every poll while sitting at the threshold.
     */
    const val RESUME_HYSTERESIS_TENTHS_C: Int = 30

    /**
     * Address-space cap applied to guest commands, in kilobytes — **except**
     * for commands that are known to need far more (see [MEMORY_HUNGRY]).
     *
     * cgroups need root, so the only memory lever available without privileges
     * is the shell's own `ulimit -v`. A runaway compiler or a leaking build is
     * then killed by the kernel (ENOMEM inside the guest) instead of being
     * reaped by Android's low-memory killer, which would take the whole app
     * down with it. 0 disables the cap.
     *
     * **Why the exception exists:** a JVM reserves a large *virtual* address
     * space (heap + metaspace + code cache + thread stacks + direct buffers),
     * routinely several GB, even when its resident set is small. Capping `-v`
     * at 2 GiB therefore makes `./gradlew`, `mvn`, `javac` and `kotlinc` fail
     * with a native "insufficient memory" error that looks like real OOM but is
     * purely the address-space ceiling. Measured on this project: applying the
     * cap to `./gradlew` broke the build outright.
     */
    const val GUEST_MEMORY_LIMIT_KB: Int = 2_097_152

    /**
     * Commands whose first token means "this needs real address space", so the
     * `ulimit -v` cap must be skipped. Matched on the **basename** of the first
     * word of the script, which covers `./gradlew`, `/usr/bin/java`, `sh -c
     * "java …"`-free forms, and the common build launchers.
     *
     * The open-file cap is still applied to these: a JVM that leaks descriptors
     * is a real problem, and 4096 is ample for a build.
     *
     * Kept deliberately short. Adding to it is a judgement call: the cap exists
     * to stop one runaway command taking the whole app down, so an entry here
     * trades that protection for the command actually working.
     */
    private val MEMORY_HUNGRY = setOf(
        "java", "javac", "javaw", "kotlinc", "kotlin",
        "gradle", "gradlew", "mvn", "maven", "sbt",
        "node", "npm", "npx", "yarn", "pnpm", "tsc", "webpack", "vite", "jest",
        "python3", "python", "pip", "pip3", "conda",
        "dotnet", "go", "cargo", "rustc", "swift", "dart", "flutter",
        "clang", "clang++", "gcc", "g++", "ld", "cmake",
        "apt", "apt-get", "dpkg",
        "docker", "podman",
    )

    /**
     * Open-file cap for guest commands. A build that leaks descriptors would
     * otherwise hit the app's own limit and start failing unrelated I/O.
     */
    const val GUEST_OPEN_FILES_LIMIT: Int = 4_096

    /**
     * Wrap [script] in the resource limits that need no privileges, for
     * execution by a POSIX shell. Returns [script] unchanged when no limit is
     * configured.
     *
     * `ulimit` is a shell builtin, so this must be prepended to the shell
     * command rather than applied to the process. Each limit is applied
     * independently and failures are tolerated with `2>/dev/null ||`, because
     * a shell that refuses one knob (a reduced `dash`, a busybox without `-v`)
     * must not fail the whole command. "unlimited" is always accepted.
     *
     * The CPU-time cap is deliberately NOT set: a legitimate build can run far
     * longer than any fixed number, and killing it on wall clock is what the
     * per-tool timeout already does far more precisely.
     */
    fun limitScript(script: String): String {
        val memoryCapped = !isMemoryHungry(script)
        val guards = buildList {
            if (GUEST_MEMORY_LIMIT_KB > 0 && memoryCapped) {
                add("ulimit -v $GUEST_MEMORY_LIMIT_KB 2>/dev/null")
            }
            if (GUEST_OPEN_FILES_LIMIT > 0) add("ulimit -n $GUEST_OPEN_FILES_LIMIT 2>/dev/null")
        }
        if (guards.isEmpty()) return script
        // `;` not `&&`: a refused limit must not stop the command from running.
        return guards.joinToString("; ") + "; " + script
    }

    /**
     * True when [script] starts with a tool that needs more address space than
     * the cap allows. Looks only at the first word (skipping leading
     * environment assignments and `sudo`/`exec`/`time` prefixes), so a build
     * invoked as `cd x && ./gradlew` is *not* detected — deliberately: a
     * composite script is the user's own composition and gets the cap, which
     * is the conservative direction. The single-command form is by far the
     * common one for a build.
     */
    internal fun isMemoryHungry(script: String): Boolean {
        val first = script.trim()
            .substringBefore(' ')
            .substringBefore('\n')
            .trim()
        if (first.isEmpty()) return false
        val base = first.substringAfterLast('/').lowercase()
        return base in MEMORY_HUNGRY
    }

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
     * Permits currently outstanding per gate, so [release] cannot inflate the
     * cap. [Semaphore] itself happily accepts an unmatched or duplicate release,
     * which would quietly raise the effective concurrency above the device's
     * capacity; this counter makes release accounting exact instead.
     */
    private val outstanding = java.util.concurrent.ConcurrentHashMap<Semaphore, java.util.concurrent.atomic.AtomicInteger>()

    /**
     * Acquire a slot, blocking up to [timeoutMs]. Returns null when the wait is
     * exhausted, so the caller can report "busy" instead of queueing forever.
     *
     * Pass [context] to enable the device-state gate: when [pauseReason] reports
     * a hot or nearly-flat device, no slot is granted. The context-less overload
     * is kept for tests and for callers that cannot supply one.
     */
    fun acquire(timeoutMs: Long = 60_000L, context: Context? = null): Semaphore? {
        // What the UI thread must never do is *block* on admission control, which
        // freezes the app until the timeout. Taking a slot that is already free is
        // instant and harmless, so a main-thread caller still gets one; only a wait
        // is refused, and the caller retries from a worker instead. The Looper read
        // is wrapped because it is a stub on the plain JVM (where tests run), and
        // calling it there throws: an unknown thread is treated as a worker.
        val onMainThread = runCatching {
            Thread.currentThread() === android.os.Looper.getMainLooper()?.thread
        }.getOrDefault(false)
        if (context != null) {
            val reason = pauseReason(context)
            if (reason != null) {
                Log.w(TAG, "not admitting guest work: $reason")
                return null
            }
        }
        val s = gateOrCreate()
        // A caller that asked for the device state is a real command: it must
        // leave the terminal's reserved slot alone. A context-less caller (tests,
        // and any path with no device reading) sees the whole pool, so its
        // behaviour is exactly what it was before the reservation existed.
        val reserve = if (context != null) reservedForTerminal() else 0
        val granted = try {
            if (onMainThread) {
                // Non-blocking only: never park the UI thread waiting for a slot.
                val immediate = tryAcquireLeaving(s, reserve, 0L)
                if (!immediate) {
                    Log.w(TAG, "no slot free and the caller is the main thread; refusing to block")
                }
                immediate
            } else {
                tryAcquireLeaving(s, reserve, timeoutMs)
            }
        } catch (t: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!granted) return null
        outstanding.computeIfAbsent(s) { java.util.concurrent.atomic.AtomicInteger(0) }.incrementAndGet()
        return s
    }

    /**
     * Take a permit, never consuming the last [reserve] of them.
     *
     * The reservation is expressed as "the gate must have MORE than [reserve]
     * permits free before a command may take one", which keeps a single semaphore
     * — and therefore the exact release accounting in [release] — intact. A second
     * semaphore would have meant two release paths and two ways to leak a permit.
     *
     * Polling rather than a blocking `tryAcquire` is deliberate: `Semaphore` has no
     * "acquire unless only a few are left" primitive, and the alternative (acquire
     * then hand the permit back) is racy in exactly the case the reservation exists
     * to prevent. The poll interval is small relative to the admission timeout
     * (which is already tens of seconds), so the cost is negligible.
     */
    private fun tryAcquireLeaving(s: Semaphore, reserve: Int, timeoutMs: Long): Boolean {
        // [s] is the gate `acquire` already resolved, and the one `outstanding`
        // will be keyed by on success. Re-deriving it here would be a real bug:
        // if the calibrated size changed between the two calls, this would take a
        // permit from the NEW gate while `release` later returned it to the OLD
        // one — a permanently lost permit, i.e. exactly the "the terminal is
        // locked and restarting doesn't fix it" class of defect.
        if (reserve <= 0) {
            return s.tryAcquire(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
        val deadline = System.nanoTime() + timeoutMs.coerceAtLeast(0) * 1_000_000L
        while (true) {
            // `availablePermits()` is only a hint, so the acquire below is still the
            // authority: if it succeeds we hold a real permit either way.
            if (s.availablePermits() > reserve && s.tryAcquire()) return true
            if (System.nanoTime() >= deadline) return false
            Thread.sleep(RESERVE_POLL_MS)
        }
    }

    /**
     * Test seam: take a permit through the **reserving** path without a [Context].
     *
     * The production callers all pass a context, but the Robolectric-free JVM suite
     * cannot construct one for the battery/thermal gate. This exposes the exact
     * same `tryAcquireLeaving` decision the real command path makes, so the
     * reserved-slot behaviour is genuinely under test rather than reimplemented in
     * the test.
     */
    internal fun acquireReservingForTest(timeoutMs: Long): Semaphore? {
        val s = gateOrCreate()
        if (!tryAcquireLeaving(s, reservedForTerminal(), timeoutMs)) return null
        outstanding.computeIfAbsent(s) { java.util.concurrent.atomic.AtomicInteger(0) }.incrementAndGet()
        return s
    }

    /**
     * Release a slot acquired from [acquire]. Releasing more times than the slot
     * was acquired is a no-op, so a double release (a session closing twice, a
     * failed launch path racing a close) cannot raise the permit count past the
     * device's cap.
     */
    fun release(semaphore: Semaphore) {
        val counter = outstanding[semaphore] ?: return
        if (counter.decrementAndGet() < 0) {
            counter.incrementAndGet()
            return
        }
        runCatching { semaphore.release() }
    }

    @Synchronized
    private fun gateOrCreate(): Semaphore {
        val size = defaultConcurrency()
        val existing = gate
        if (existing != null && gateSize == size) return existing
        // Resizing mid-flight would strand permits; create a fresh gate and let
        // in-flight holders release into the old one harmlessly. Their release
        // still finds its own counter entry, so the old gate drains correctly.
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
        outstanding.clear()
        thermalPaused = false
        measuredBudget = null
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
        // Hysteresis: a device sitting on the threshold would otherwise flap
        // between paused and running on every poll. Once paused, it stays paused
        // until it cools RESUME_HYSTERESIS_TENTHS_C below the pause threshold.
        // Resume is STRICTLY below the band, so `pause at T, resume at T - H`
        // and `pause at T - H` are unambiguous rather than both true at the edge.
        val resumeBelow = THERMAL_PAUSE_TENTHS_C - RESUME_HYSTERESIS_TENTHS_C
        if (thermalPaused && battery.temperatureTenthsC < resumeBelow) {
            thermalPaused = false
            return null
        }
        val thermalLimit = if (thermalPaused) resumeBelow else THERMAL_PAUSE_TENTHS_C
        if (battery.temperatureTenthsC >= thermalLimit) {
            thermalPaused = true
            return "device is hot (${battery.temperatureTenthsC / 10}°C)"
        }
        thermalPaused = false
        return null
    }

    /**
     * Whether the last thermal decision was "too hot". Latches so the resume
     * threshold can sit below the pause threshold; guarded by the object monitor
     * because admission can be called from several worker threads.
     */
    @Volatile
    private var thermalPaused: Boolean = false

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
