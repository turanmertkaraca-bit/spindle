# ARCH-01 · There is NO subagent concurrency cap (owner wants 7)

**Status:** `[ ]` open · **Owner-requested** · Confirmed by reading the spawn
path.

## The problem in one line

`SubagentSpawner.spawn()` creates and runs a child session immediately, with
**no semaphore and no queue anywhere**. A parent model that emits N `task` calls
in one turn runs N children concurrently, unbounded.

## Evidence

`core/src/main/kotlin/dev/spindle/core/agent/SubagentSpawner.kt`:

```kotlin
class SubagentSpawner(
    private val loop: AgentLoop,
    private val store: SessionStore,
    private val bus: EventBus,
    private val maxChildSteps: Int = 12,   // <-- only a step cap, NOT a concurrency cap
) {
    suspend fun spawn(parentId: SessionId, spec: SubagentSpec, parentAgent: AgentConfig): SubagentResult {
        ...
        store.createSession(child)
        // ... runs the child loop directly
    }
}
```

- `maxChildSteps` limits **how long one child runs**, not how many run at once.
- A search for `Semaphore` / `maxConcurrent` / `parallel` across
  `SubagentSpawner.kt` and `AgentLoop.kt` returns **nothing** relevant.
- `AgentLoop.runSubagent(...)` calls straight through to the spawner.

Meanwhile the **tool-level** admission control that does exist
(`ResourcePolicy`, `app/src/main/kotlin/dev/lumen/app/platform/ResourcePolicy.kt`)
governs **guest shell processes**, not agent subagents. Do not confuse the two.

## Why it matters

- N children × (their own tool calls) can exhaust memory, file descriptors,
  provider rate limits, and the device's CPU at once.
- Each child can itself spawn children (when `allowSubagents = true`), so the
  fan-out is **recursive** and unbounded.
- The owner explicitly wants a cap of **7**.

## Fix sketch

Put the gate at the **spawn boundary** so every caller is covered — the `task`
tool, and any future direct call.

```kotlin
class SubagentSpawner(
    private val loop: AgentLoop,
    private val store: SessionStore,
    private val bus: EventBus,
    private val maxChildSteps: Int = 12,
    /** Hard ceiling on concurrently RUNNING child runs. Owner default: 7. */
    private val maxConcurrent: Int = 7,
) {
    private val gate = Semaphore(maxConcurrent)
    // spawn(): gate.withPermit { ... } around the child run, NOT around
    // createSession, so the queue does not create rows it will not run.
}
```

Design points that matter (each is a real trap):

1. **Acquire around the run, not the whole spawn.** Creating the session row
   first and blocking on the permit leaves a `RUNNING` child in the store that
   is not actually running — the UI shows a spinner that never moves.
2. **Make the wait cancellable.** Use `withPermit` inside the caller's coroutine
   so a user Stop cancels queued children too; a `Semaphore.acquire()` that
   ignores cancellation wedges the run.
3. **Bound the wait or queue visibly.** If 20 tasks are requested with a cap of
   7, the other 13 must either wait (and say so via a tool progress event) or be
   refused with a clear tool error. Silently hanging looks like a freeze.
4. **Recursion.** A child with `allowSubagents = true` shares nothing with the
   parent's gate if you make the semaphore per-instance. Decide explicitly:
   either pass the **same** gate down (true global cap) or cap depth. A
   per-instance semaphore gives N×(cap), which likely defeats the intent.
5. **Expose it.** Make the cap configurable via `AgentConfig` so different
   agents can differ, defaulting to 7.

## Tests to add

`core/src/test/kotlin/dev/spindle/core/agent/SubagentTest.kt` already exists —
extend it:

- Emit 10 `task` calls with `maxConcurrent = 7`; assert the **observed maximum
  concurrency** never exceeds 7 (count entries via a `CompletableDeferred` or an
  `AtomicInteger` high-water mark in a fake provider/tool).
- Assert all 10 eventually complete (no lost work).
- Assert a cancellation while 3 are queued cancels them rather than hanging.
- Assert a nested child shares the same cap when the global-gate option is used.

Run with `./gradlew :core:test` — **authoritative locally**, no CI needed.

## Related

- `docs/analysis/arch/CONTEXT.md` — why 7 is also a context decision: each
  child returns a result that the parent must read.
- `docs/guide/01-agent-loop.md` — the subagent section of the loop guide.
