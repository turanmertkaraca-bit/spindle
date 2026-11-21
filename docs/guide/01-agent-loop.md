> **NOTE:** written against HEAD `4ff37c0`, which is older than current. The
> *structure* described here is still accurate and every change since is recorded in
> `docs/analysis/STATUS.md`; re-check any specific claim against the code. Doc index:
> `docs/analysis/DOC-MAP.md`.

# The agent loop (`:core/agent`)

Verified against HEAD `4ff37c0`. Last behavioural changes: `14aa84c` (run caps),
`fed3b0e`–`6abfd94` (stability wave).

`AgentLoop` (`core/src/main/kotlin/dev/spindle/core/agent/AgentLoop.kt`, ~1,190
lines) is the whole brain. Everything else in `:core` is a small collaborator:
`ContextPolicy` (trim/compact), `Compaction`, `Retry`, `RunRecovery`,
`SubagentSpawner`, `PermissionGate`, `ApprovalPolicy`, `Wire` (JSON), `AgentConfig`.

## Construction

Everything the loop needs is injected, which is why it is testable without a
device or a network:

```kotlin
AgentLoop(
    providers, tools, store, bus,
    permissions, questions, clock, maxToolOutputChars,
    subagents, approval, snapshots, defaultSubagentModel,
)
```

Notable defaults: `permissions = AllowAll`, `subagents = null` (spawns child
loops on demand), `snapshots = null` (record-and-revert disabled),
`maxToolOutputChars = 60_000`.

`AgentConfig` (`AgentConfig.kt`) is the per-agent knob set: `maxSteps`,
`maxRunMillis`, `maxRepeatedCalls`, `denyTools`, `allowSubagents`,
`allowedSubagents`, `maxRetries`, `reasoningEffort`, `temperature`,
`responseFormat`.

## The outer loop

`while (true)` at roughly line 205. **Four** independent ways out, and this
matters when you change anything here:

1. The model returns no tool calls → the turn is done.
2. **Doom-loop guard** — the same `(tool name + arguments)` signature repeats
   `maxRepeatedCalls` times (default `3`) inside a sliding window of
   `max(2, 2 × maxRepeatedCalls)`. A window, not a consecutive run, so `A/B/A/B`
   is caught too. Gated on `maxRepeatedCalls != null`: setting it to `null`
   disables the guard entirely, so only do that when `maxRunMillis` or
   `maxSteps` is set.
3. **Step budget** — `agent.maxSteps`, only when non-null. **No shipped agent sets
   it**, and subagents no longer get one either (`SubagentSpawner` used to give
   every child 24).
4. **Run caps** — the backstop for the default `maxSteps == null`:
   - `maxRunMillis` — a wall clock, **opt-in only**. `null` (the default) and `0`
     both mean *no limit*; only `> 0` imposes one. Until 0.2.21 the default was
     `MAX_RUN_WALL_CLOCK_MS` = 30 min for every agent, which is what the owner
     reported as "the 30 minute clock on build mode". When a window *is* set it is
     checked at the **top** of the loop, before the next provider request, so an
     over-budget run never starts one more call. See `RunClock.kt`.
   - `MAX_TOTAL_TOOL_CALLS` = 250, counted once per `executeTool`. **This is what
     actually bounds a run now**: it responds to a run going nowhere rather than to
     elapsed time.
   - The doom-loop guard (2) above.

**Invariant: never `break` leaving a tool part `PENDING`.** Every exit path that
aborts mid-turn first walks the turn's tool parts and rewrites them to
`ToolState.ERROR` with an explanatory `ToolResult(..., isError = true)`. The UI
renders `PENDING` forever if you miss one.

## Executing one tool call

`executeTool` (~line 738) is defensive by design:

- Tool bodies do blocking I/O, so the call runs on `Dispatchers.IO`.
- The loop owns the deadline. `tool.timeoutMs <= 0` means "the tool manages its
  own timeout" and is **not** double-wrapped in `withTimeout`.
- `TimeoutCancellationException` is re-checked against
  `currentCoroutineContext().ensureActive()` before being reported as a timeout,
  so a user Stop propagates as cancellation instead of being mislabelled.
- Output is spilled/clipped (`maxToolOutputChars`) before being persisted,
  because the result is re-sent to the provider on every later turn.

## Context: trim, compact, overflow retry

Before each step the loop loads history once and reuses that snapshot for the
overflow decision, the budget check and the request build. `trim`/`compact` can
rewrite history, so the loop **reloads** when the decision is not `NONE`.

On a context-overflow error it retries with a reset: `PartReset` is emitted for
both the text and reasoning parts and fresh part ids are allocated, so attempt-1
and attempt-2 text are not concatenated in the UI.

## Subagents

`SubagentSpawner`/`runSubagent` create a child `AgentLoop`. **A child has no step
budget and no wall clock of its own** (0.2.21+): `maxSteps` is null and
`maxRunMillis` is inherited from the parent *exactly*, so a bounded parent still
bounds its children. A child is stopped by `MAX_TOTAL_TOOL_CALLS`, the doom-loop
guard, or the parent's cancellation — never by a fixed step count. Concurrency is
capped at 7 by a process-wide gate. `allowSubagents = false` removes the `task`
tool entirely, which is the safe setting for a subagent that must not fan out
further.

## Testing this area

`core/src/test/.../agent/` — `AgentLoopTest`, `CompactionTest`, `OverflowTest`,
`RetryTest`, `RunRecoveryTest`, `SubagentTest`, `WireTest`,
`AgentCapabilitiesTest`. These run on a plain JVM and are **authoritative
locally** (`./gradlew :core:test`, 242 tests green at this commit).

When adding a cap or guard, test the boundary in both directions: at the limit
and one step past it.
