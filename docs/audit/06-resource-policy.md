# ResourcePolicy — CPU/priority/pause gate audit

`ResourcePolicy` is a root-free admission-control object for guest (proot) work: it computes a device-appropriate concurrency ceiling, hands out a slot from a lazily-sized fair `Semaphore` around each guest process lifetime, exposes the nice/battery/thermal thresholds as constants, and derives an advisory "pause" reason from the device's battery percentage, charge state and temperature. Its stated design position is that proot shares the host scheduler, so cgroups are unavailable without privileges and the only levers that work are concurrency, nice, and pausing when the device is hot or nearly flat.

## What exists today

Constants:

- `const val DEFAULT_NICE: Int = 10` (line 53)
- `const val LOW_BATTERY_PERCENT: Int = 15` (line 56)
- `const val THERMAL_PAUSE_TENTHS_C: Int = 420` (line 59)

Private gate state: `private var gate: Semaphore? = null` (line 69), `private var gateSize: Int = 0` (line 70).

Semaphore API:

- `fun acquire(timeoutMs: Long = 60_000L): Semaphore?` (line 76) — `tryAcquire(timeoutMs, MILLISECONDS)`; returns `null` on timeout and on `InterruptedException` after re-interrupting the thread (lines 79-83).
- `fun release(semaphore: Semaphore)` (line 87) — body is `runCatching { semaphore.release() }`.
- `fun availableSlots(): Int = runCatching { gateOrCreate().availablePermits() }.getOrDefault(0)` (line 112).

Sizing and policy:

- `fun defaultConcurrency(): Int` (line 39) — `Runtime.getRuntime().availableProcessors()`; `cores <= 2 -> 1`, `cores <= 4 -> max(1, cores - 2)`, `else -> max(2, cores - 2)`.
- `@Synchronized private fun gateOrCreate(): Semaphore` (line 92) — recreates `Semaphore(size, true)` (fair) when the computed size differs from `gateSize`, deliberately letting in-flight holders release into the old gate (lines 96-101).
- `fun clampConcurrency(requested: Int): Int` (line 185) — `min(max(1, requested), defaultConcurrency())`.
- `fun pauseReason(context: Context): String?` (line 119) — delegates to `pauseReasonFor(readBattery(context))`; internal `pauseReasonFor(battery: Battery?)` returns null for a null battery (line 130), pauses when `battery.percent in 0..LOW_BATTERY_PERCENT && !battery.charging` (line 131) or when `battery.temperatureTenthsC >= THERMAL_PAUSE_TENTHS_C` (line 134).

## Findings

| # | Severity | Finding | Line | Why it matters |
|---|---|---|---|---|
| 1 | P0 | `release(semaphore)` accepts any `Semaphore` and does no permit accounting — a double release, or a release without a matching `acquire`, silently inflates permits above `defaultConcurrency` | 87-89 | The concurrency cap is the one lever the file says "reliably matters"; an over-release defeats it from any call site and the object cannot detect or correct it. `Semaphore.release()` is unbounded and there is no `tryRelease`/owner token. |
| 2 | P1 | `DEFAULT_NICE = 10` is declared but never applied anywhere in this file — there is no priority-setting code path here | 53 | Nice is described in the header as one of the three levers that actually works. In this object it is only a constant, so its effectiveness is entirely unverified from this file. |
| 3 | P1 | `acquire()` blocks the calling thread for up to 60 s by default, with no main-thread guard or assertion | 76-79 | Called from a UI/foreground thread this is an ANR waiting to happen; the 60 s default is long enough to trip the 5 s/10 s input-dispatch and broadcast limits several times over. |
| 4 | P1 | `acquire()` never consults `pauseReason()` — a device that is hot or nearly flat still receives a slot | 76-84, 119-121 | The pause knob and the concurrency knob are completely disconnected inside this object; pausing is purely advisory and depends on every caller remembering to check. |
| 5 | P1 | A leaked slot is unrecoverable: no ownership token, no lease/expiry, no watchdog to rebuild the gate | 76-84, 87 | `Semaphore` cannot distinguish a live holder from a dead one. N forgotten `release()` calls permanently drive permits to 0, after which every `acquire` times out and the module reports "busy" forever — a soft deadlock with no self-healing path. |
| 6 | P2 | `THERMAL_PAUSE_TENTHS_C = 420` (42 °C) is at or below ordinary warm-charge/gaming battery temperatures, and the comparison is a bare `>=` with no hysteresis | 59, 134 | At the boundary the device flaps between pause and resume on every poll; a threshold this low will pause runs on healthy hardware and make the "advisory" path effectively always-on. |
| 7 | P2 | Thermal input is battery temperature only; `PowerManager` thermal-status APIs and power-save/doze state are not consulted | 147-156 | Battery temperature lags and reflects charging, not sustained SoC heat. No `isPowerSaveMode`/`isDeviceIdleMode` awareness means the policy misses the states where the OS is already throttling. |
| 8 | P2 | Two sources of truth for concurrency: `clampConcurrency()` computes a per-caller number the shared gate does not honour, and `defaultConcurrency()` gives 6 slots on an 8-core big.LITTLE device | 39-46, 185-188 | A caller that "clamped to 2" can still find 6 permits behind it; counting all logical cores (including little cores) as capacity makes the default aggressive for sustained compiles. |
| 9 | P3 | `acquire()` returns `null` for both "timed out" and "interrupted" — the two cases are indistinguishable to the caller | 79-83 | Callers cannot tell "busy, retry later" from "we were cancelled"; a cancel and a slow device get the same log line and possibly the same retry policy. |
| 10 | P3 | `availableSlots()` returns `0` on failure, and `release()` swallows all errors via `runCatching` | 88, 112 | Diagnostics cannot distinguish "no capacity" from "the gate is broken", and a failed release is invisible — which is exactly how finding #5 stays hidden until the run hangs. |

## Enforcement gaps

Within this file, **concurrency is the only knob with real enforcement**: `acquire()`/`release()` manipulate an actual `Semaphore` permit. Everything else is computed or advisory. `pauseReason()` and `pauseReasonFor()` are pure functions that return a string or null and are never referenced by `acquire()`, `gateOrCreate()`, or `availableSlots()`; nothing in this file schedules, polls, or blocks on them. `DEFAULT_NICE` is likewise never read here, and `clampConcurrency()` is never called by the gate — its return value is only meaningful to whichever caller invoked it.

Answers to the specific questions, limited to what this file shows:

- **Is `pauseReason` ever consulted?** Not here. It is exported for callers; whether any of them call it cannot be determined from this file. A follow-up must grep for `pauseReason(` / `pauseReasonFor(` at every guest-execution call site and check whether a non-null result actually delays the launch (and for how long, and with what re-poll interval).
- **Does `acquire()` block or spin?** It blocks, bounded: a single `tryAcquire` with the given timeout (default 60 s), not a spin loop. There is no backoff, no retry, and no notification when a permit frees up, so callers that get `null` must poll on their own — meaning the effective pause behaviour depends on caller retry policy, which is not visible here.
- **Can the semaphore deadlock if a slot leaks?** Yes, in the starvation sense: there is no reclamation, so once all permits leak the gate is permanently exhausted and every `acquire` returns `null` after its timeout. It never hard-blocks forever because the timeout bounds the wait, but the module also has no way to notice or recover — `resetForTest()` is `internal` and test-only. The opposite failure is also open: unbounded `release()` can push permits above the ceiling (finding #1). A follow-up must check that every `acquire` is paired with a `release` in a `finally`, that no call site releases a semaphore it did not acquire, and whether anything ever calls `resetForTest()` in production.

## Recommendation for real CPU limiting on Android without root

- **Set nice on children you own.** `Process.setThreadPriority` affects threads in your own process only; for guest processes use `nice`/`setpriority` on the spawned child or an `LD_PRELOAD`/exec-wrapper that calls `setpriority(PRIO_PROCESS, 0, DEFAULT_NICE)` before handing off to proot — this is the one place `DEFAULT_NICE` should actually land. Verify against the exec layer.
- **Use thread priority classes, not raw numbers.** `Process.setThreadPriority(THREAD_PRIORITY_BACKGROUND)` (or `Process.THREAD_PRIORITY_LESS_FAVORABLE`) on worker threads makes the scheduler deprioritize them against the UI without starving them; combine with `THREAD_PRIORITY_DISPLAY`/`FOREGROUND` for the interactive path so the split is explicit.
- **Gate on real thermal status where available.** Prefer `PowerManager.getCurrentThermalStatus()` (API 29+) and `addThermalStatusListener` over battery temperature; keep `THERMAL_PAUSE_TENTHS_C` as a fallback for old devices and add hysteresis (pause high, resume low) so runs do not flap at the threshold.
- **Respect OS-level throttling instead of duplicating it.** Check `PowerManager.isPowerSaveMode`, `isDeviceIdleMode`, `isIgnoringBatteryOptimizations`, and battery-saver state; when the OS is already throttling, reduce concurrency rather than fighting it. If work must continue, use a `dataSync`/`shortService` foreground service with the matching `foregroundServiceType` and an ongoing notification so the process is not frozen mid-run.
- **Not possible without root:** creating or joining a cgroup, setting CPU shares/quota, cpuset pinning, `sched_setaffinity` on foreign processes, or raising your own nice below 0 (negative nice needs `CAP_SYS_NICE`). proot runs as the same UID, so the guest cannot be placed in a cgroup or capped by CPU bandwidth — only cooperative concurrency plus priority hints are available.
