# Commands, the guest shell and proot (`:app/platform`)

Verified against HEAD `4ff37c0`, which added the resource limits described below.

## The three execution paths

`AndroidShellExecutor` (`app/src/main/kotlin/dev/lumen/app/platform/`) picks a
path per call:

| path | when | entry point |
|---|---|---|
| **Debian / proot** | `debian.active()` — installed *and* probe passed | `runInDebian()` → `guestProcess()` |
| **Alpine fallback** | installed userland, Debian not active | `ProcessBuilder(listOf(systemSh, "-c", …))` |
| **Host `sh`** | nothing installed | same fallback, no `PATH` override |

The interactive terminal (`openPty`) prefers Debian once active; otherwise it
opens a plain pipe shell. It is a *pipe*, not a real TTY — don't assume prompt
behaviour in tests.

Debian is **never auto-downloaded**; it is opt-in and `active()` requires a
passing probe.

## Admission control (`ResourcePolicy`)

`ResourcePolicy` is the single gate. Two knobs, both in one object:

1. **Concurrency** — a `Semaphore` sized by `defaultConcurrency()` (cores − 2,
   floor 1, capped at 2+). Held around *one guest process's lifetime*, not a
   whole run, so many short calls interleave with the UI.
2. **Pause** — `pauseReason(context)` returns non-null when the battery is low
   and discharging, or the device is hot. `acquire(timeoutMs, context)` refuses a
   slot when paused, so the policy is enforced, not merely advisory.

Hardening that exists because it was a real bug:

- **Permit accounting.** `Semaphore.release()` is unbounded and a double release
  inflated permits above the cap, defeating the concurrency limit. `outstanding`
  counts permits per gate so `release` cannot over-release.
- **No main-thread blocking.** `acquire` detects the main looper and only
  `tryAcquire`s; blocking the UI thread for the 60 s default is an ANR.
- **Thermal hysteresis.** The pause threshold is latched: it resumes only
  *strictly below* `THERMAL_PAUSE_TENTHS_C - RESUME_HYSTERESIS_TENTHS_C`. The
  band is `[T-H, T)`, so the boundary is unambiguous. Without this the device
  flapped paused/running on every poll.

## Resource limits (new in `4ff37c0`)

cgroups need root, so the only privilege-free memory lever is the shell's own
`ulimit`. `ResourcePolicy.limitScript(command)` prepends:

```
ulimit -v 2097152 2>/dev/null; ulimit -n 4096 2>/dev/null; <command>
```

- `GUEST_MEMORY_LIMIT_KB` = 2 GiB (`ulimit -v`) — a runaway compiler dies with
  ENOMEM *inside the guest* instead of being reaped by Android's low-memory
  killer, which would take the app down with it.
- `GUEST_OPEN_FILES_LIMIT` = 4096 (`ulimit -n`) — a descriptor leak cannot
  exhaust the app's own limit and start failing unrelated I/O.

Two deliberate design points:

- The guards are joined with `;`, **not `&&`**, and each tolerates its own
  failure. A shell that refuses one knob must still run the command.
- The cap is applied to **the script the guest shell runs**, not to `proot`. A
  `ulimit` on proot would limit the shim's address space, not the guest tree's.

Applied on all three spawn points: Debian command path, Alpine fallback, and the
interactive pty. The pty's bare `exec /bin/bash` login shell is intentionally
left unguarded.

**No CPU-time `ulimit` is set on purpose** — a real build can exceed any fixed
number, and the per-tool deadline bounds wall clock far more precisely.

Scheduling niceness still exists: `startUnderSetsid` prepends `nice -n 10`
*before* `setsid` so the whole guest tree inherits it (the reverse order would
nice only `setsid`).

## Environment scrubbing

`scrubHostEnv` removes the app process's ambient credentials before any guest
command — on both the Debian and Alpine paths, plus `JAVA_TOOL_OPTIONS` /
`_JAVA_OPTIONS`. The Debian path additionally exports `PROOT_*`, `HOME=/root`,
`TMPDIR=/tmp`, a generated `/etc/hosts` bind (localhost resolves instantly), and
the in-app proxy as `http_proxy`/`https_proxy`.

## Binds

`prootArgv` binds `/dev`, `/proc`, `/sys`, the session `cwd`, the app-private
home, plus Downloads **only** when legacy external storage was granted, and
`/etc/hosts` only when the file exists (a missing bind is fatal to proot).

## Testing

`ResourceLimitTest` (9 cases, plain JVM) is authoritative locally — it is a
plain JUnit test precisely because Robolectric cannot run in the dev sandbox.
`DebianEnvironmentTest` and `AndroidShellExecutorTest` are Robolectric and only
meaningful on CI.

When you change `limitScript`, run it against a real `sh` before trusting the
test; the failure mode (`dash` rejecting `ulimit -v`) is environment-specific.
