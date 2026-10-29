# AndroidShellExecutor — process & concurrency audit

`AndroidShellExecutor` is the Android implementation of the `ShellExecutor` interface: it runs agent commands through `sh -c` inside the Alpine userland from `AndroidEnvironment` (or a plain `/system/bin/sh` fallback when the rootfs is absent), preferring the Debian/proot guest whenever `DebianEnvironment` reports active. It launches every child under `nice` + `setsid` so a process-group signal can reap the whole tree, merges stderr into stdout, drains output on a daemon thread into a head+tail bounded buffer, and enforces timeouts by polling `Process.waitFor(50ms)` plus `ensureActive()`. It also exposes `openPty`, a pipe-backed pseudo-terminal (`ProcessPtySession`) for the interactive terminal panel, and takes an admission slot from `ResourcePolicy` for both paths so guest work cannot starve the UI.

## Findings

| # | Severity | Finding | Line | Why it matters |
|---|---|---|---|---|
| 1 | P2 | Admission wait is a hardcoded 120 s and is *not* bounded by the caller's `timeoutMs`; total wall time is `acquire (≤120 s) + install + timeoutMs`. | L75 | A tool call with `timeoutMs = 30_000` can block ~2 min before the child even starts, so the timeout contract the agent reasons about is silently violated. |
| 2 | P2 | The comment claims "a slot is held for this command's lifetime only, so many short commands interleave instead of queueing behind one long one", but the slot is held for the *entire* command duration. | L69–75 | Misleading invariant: with the cap saturated by long builds, short commands do queue behind them; anyone tuning the cap from this comment will size it wrong. Cap value itself unverified (ResourcePolicy not read). |
| 3 | P1 | A non-`CancellationException` throw inside `collect` never kills the child; `execute`'s catch only formats a message and the `finally` still releases the slot. | L116–124, L257–278 | An orphaned guest process tree survives with no slot accounting it, so "cap how many guest processes exist at once" (L69–72) is no longer enforced — the leak is permanent for that process's life. |
| 4 | P2 | After a timeout `process.exitValue()` is called even though the kill may not have completed within the 1 s wait in `killProcessTree`. | L278–293, L467 | `exitValue()` throws `IllegalThreadStateException`, which surfaces as "failed to launch shell: …" for a command that launched fine — wrong diagnosis and a lost timed-out result. |
| 5 | P2 | Deadline is `System.nanoTime() + timeoutMs * 1_000_000L`; the multiply overflows for very large `timeoutMs`. | L258 | A caller using the idiomatic `timeoutMs = Long.MAX_VALUE` as "no timeout" gets a negative deadline and the command is killed on the first poll. |
| 6 | P1 | In `openPty` the slot is released **only** through `onClose`; there is no `finally`. If the panel abandons the session without calling `close()`, the slot is never returned. | L155–185, L364, L427 | The admission cap is permanently reduced by one per abandoned terminal; repeated abandonments can deadlock all shell work behind a gate nobody releases. |
| 7 | P2 | Cancellation between `ProcessPtySession` construction and `return@withContext` discards the session object. | L161–164, L185 | `withContext` has prompt-cancellation semantics: the started shell process and its slot are dropped with no holder to `close()` them — orphan process plus leaked slot. |
| 8 | P1 | The Debian path never calls `scrubHostEnv`; it only removes `JAVA_TOOL_OPTIONS`/`_JAVA_OPTIONS`, while the Alpine path scrubs `*_TOKEN`/`*_KEY`/`*_SECRET` etc. | L135–140 vs L104–105, L162 | Secret-hygiene asymmetry: guest commands under proot may inherit app-process credentials that the fallback path deliberately strips. Unverified whether `debian.guestProcess` starts from a clean env. |
| 9 | P2 | `installAttempted = true` is set *before* `environment.ensureInstalled()` and never reset; the failure is swallowed by `runCatching`. | L92–98 | One transient install failure (no network, disk full) disables the rootfs for the whole app-process lifetime with no error surfaced — the app silently runs degraded on the fallback shell. |
| 10 | P2 | `killProcessTree` blocks up to ~5–6 s on the IO dispatcher (`TERM` 1.5 s ×2 + 1 s) and is invoked from the cancellation path; it also targets the *pid-derived group* without guarding against pid reuse. | L272–275, L446–468 | Cancellation latency is seconds, not milliseconds, and a recycled pid means `kill -TERM -<pid>`/`kill -KILL -<pid>` can signal an unrelated process group (the app's own group on some builds). |
| 11 | P2 | `ProcessPtySession` publishes to `Channel<String>(Channel.UNLIMITED)`. | L370 | A chatty command (`yes`, a runaway build log) with a slow or paused UI consumer buffers without bound — an OOM in the app process, unlike the `run()` path which is bounded. |
| 12 | P3 | `reader.join(2000)` after the kill; when grandchildren keep the pipe fd open the join times out and the daemon thread lives on. | L274, L279 | Output is silently truncated to whatever was read before the deadline and the reader thread/pipe leak per invocation (the slot is already released, so nothing bounds the accumulation). |
| 13 | P3 | `onChunk` runs on the reader thread and its exceptions are swallowed by `runCatching`, including `CancellationException`. | L239–242 | The streaming contract is undocumented (off-dispatcher callback), and a consumer that throws to abort streaming cannot; it silently keeps receiving. |
| 14 | P3 | `putAll(env)` runs *after* `scrubHostEnv`, `TERM`, `PATH`, `HOME`, `TMPDIR` and proxy vars are set. | L113 | Agent-supplied env can override the guest `PATH`/`HOME` the executor just configured, defeating the sandbox intent; also allows reintroducing scrubbed keys. |
| 15 | P3 | `processPid` reflects the hidden `pid` field per call and returns -1 on failure; `killProcessTree` then degrades to `destroy()`/`destroyForcibly()` only. | L433–436, L448–466 | Under R8/obfuscation or a changed hidden field the process-group kill silently stops working, leaving grandchildren alive after a timeout or Stop — with no log to say so. |

## Concurrency-cap analysis

The cap lives in `ResourcePolicy.acquire/release`, and there are two structurally different call sites. `execute` uses a correct lease:

```kotlin
val slot = ResourcePolicy.acquire(timeoutMs = 120_000L)
    ?: return@withContext ShellResult(exitCode = -1, output = "another agent command is still running; …")
try {
    if (debian.active()) return@withContext runInDebian(command, cwd, timeoutMs, env, onChunk)
    …
    val process = startUnderSetsid(builder)
    collect(process, timeoutMs, onChunk)
} catch (e: CancellationException) {
    throw e
} catch (t: Throwable) {
    ShellResult(exitCode = -1, output = "failed to launch shell: ${t.message}")
} finally {
    ResourcePolicy.release(slot)
}
```

Every path releases exactly once: the null-slot early return happens *before* the `try`, so nothing is released that was never taken; the Debian fast path returns through the `finally`; cancellation is rethrown through the `finally`; any other `Throwable` is converted to a `ShellResult` and still hits the `finally`. So the answer to "is a slot released on every path including exception and cancellation?" is **yes for `execute`** — but "released" and "correct" diverge in two ways. First, a slot is indeed held for the *whole* duration of a long command (`collect` runs to the process's exit, timeout, or cancellation), so the docstring's "many short commands interleave instead of queueing behind one long one" only holds when the cap is larger than the number of long-running commands; otherwise short commands queue behind them. Second, the release in `finally` is unconditional even when the child process was never killed: on a non-`CancellationException` throw from `collect`, and on the `exitValue()`-throws path, the slot goes back to the pool while the guest process tree is still alive and consuming CPU — the cap then under-counts real guest processes and its whole purpose (bounding concurrent guest processes without cgroups) is defeated. Note also that `killProcessTree` spawns `/system/bin/kill` processes that are not counted against the cap, and that the acquisition timeout (120 s) is not derived from the caller's `timeoutMs`, so a call can spend its entire budget waiting for admission and then still run for `timeoutMs`.

`openPty` uses a *different, weaker* structure — there is no `finally`; ownership of the slot is transferred to the returned session's `onClose` callback:

```kotlin
val slot = ResourcePolicy.acquire(timeoutMs = 15_000L)
if (slot == null) return@withContext null
try {
    if (debian.active()) { … return@withContext ProcessPtySession(startUnderSetsid(…), onClose = { ResourcePolicy.release(slot) }) }
    …
    ProcessPtySession(startUnderSetsid(builder), onClose = { ResourcePolicy.release(slot) })
} catch (t: Throwable) {
    ResourcePolicy.release(slot)
    null
}
```

The `catch` covers a failure to *start*, but the success path's release depends entirely on the caller eventually calling `close()`, which is guarded by `closeOnce` (L368, L427) so double-close cannot double-release. Consequently the slot's lifetime is the *session object's* lifetime, not the command's: a session whose consumer drops the flow without closing, or whose coroutine is cancelled between construction (L161/L185) and the `return@withContext`, leaves both the slot and the shell process stranded forever. The 15 s admission timeout here also differs from the 120 s used by `execute`, so interactive opens fail faster than non-interactive ones for no stated reason.

## Highest-leverage fix

Replace the raw `acquire`/`release` pairs with a single-use "slot lease" object that owns the child process and can be released exactly once by exactly one owner, and hand it to `openPty`'s `finally` rather than to the session's callback. Concretely: in `execute`, move `killProcessTree(process)` into the same `finally` as the release (or into a `collect` `finally`) so a slot is never returned while its process is alive; in `openPty`, keep a local `var session: PtySession?` and close it in a `finally`, plus register `currentCoroutineContext().job.invokeOnCompletion { session?.close() }` and a process-exit watcher so cancellation or an abandoned panel still closes the session and returns the slot. That single change closes findings 3, 6 and 7 (the only paths that permanently leak the cap), while 4, 5 and 10 — the mislabeled error, the overflow deadline and the blocking/pid-reuse kill — are small local edits in the same region.
