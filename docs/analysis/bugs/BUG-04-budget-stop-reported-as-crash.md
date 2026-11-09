# BUG-04 · A spent budget was reported as a crash

**Status:** `[x]` FIXED `69474f5` · tests corrected `b4ff11b` · released `v0.2.17`
**Found by:** reading `AgentLoop.promptLocked` end to end after the owner asked
*"are you sure it's done? no more bugs on our main loop?"*

---

## Symptom

Reaching your own **cost ceiling** or **input-token ceiling** looked like the app
crashing: the session went to `ERROR`, the transcript showed a raw exception
string, and the run produced **no report at all**.

## Root cause

`budgetCheck` signalled a spent budget by **throwing**:

```kotlin
throw IllegalStateException("session cost budget exceeded: $spent >= $max")
```

It is called at the top of each step, *outside* the provider-failure
`try`/`catch`. So the throw fell through to the outer generic handler:

```kotlin
} catch (e: Throwable) {
    ...
    terminalState = SessionState.ERROR
    bus.emit(AgentEvent.StateChanged(sessionId, SessionState.ERROR))
    persistState(store, sessionId, SessionState.ERROR)
    throw e
}
```

Three consequences, all bad:

1. A budget stop was indistinguishable from a genuine crash.
2. The **wind-down turn was skipped**, so the user got no answer — the exact
   "it just stopped" complaint, in a path nobody had looked at.
3. The caller saw an exception rather than a finished run, so the app-level
   `RunEndPolicy` classified it as `LOOP_ERROR`.

## Fix

A budget stop is a **normal end**, so it is modelled as a value:

- `StopReason` enum (`FINISHED`, `DOOM_LOOP`, `STEP_BUDGET`, `TOOL_CALL_BUDGET`,
  `WALL_CLOCK_BUDGET`, `COST_BUDGET`, `INPUT_TOKEN_BUDGET`, `PROVIDER_ERROR`).
- `budgetCheck` **returns** `StopReason?`; the loop sets `wrapUpReason` from its
  `.label` and takes the same wind-down turn as the step and tool-call caps.
- Modelled as an enum rather than a boolean so it cannot quietly become an
  exception again.

## The tests were wrong too

Both budget tests were named `"... then throws"` and asserted
`assertFailsWith<IllegalStateException>` **and** `SessionState.ERROR`. They encoded
the bug as the contract. They now assert: one budget warning, the run returns the
model's report, and the session ends **IDLE**.

That is worth remembering — a test can be green and still be asserting the wrong
thing, and a bug that tests have blessed is invisible until someone reads the code.

## Second defect, same read-through

`return store.latestMessage(sessionId)!!` could turn a **completed** run into an
NPE (deleted session, closed handle, transient read failure). The NPE then went
through the same crash handler and the caller lost the run's output. It now falls
back to the last assistant message built, then to a synthetic message explaining
the read failure.

## A correction I owe

While reading, I also "found" a third bug — that the doom-loop window persisted
across turns and so killed healthy runs. **I was wrong.** The window is
`maxRepeatedCalls * 2` = 6, so a signature trips only if it appears three times
within the last six calls; `read → edit → read` is fine. Clearing it per turn
would have made the guard near-useless, because a real cross-turn loop
(`grep, grep, grep`) would then only be caught by `MAX_TOTAL_TOOL_CALLS = 250`.
I reverted it and deleted the test I had written for it.

Recorded because "I read the code and found N bugs" is itself a claim that needs
checking, and one of my three was wrong.
