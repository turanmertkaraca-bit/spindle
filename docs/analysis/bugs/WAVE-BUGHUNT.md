# Bug-hunt waves — the app's MAIN FUNCTION (the agent loop)

**Owner instruction:** *"analyse, find bugs, find missing features or broken existing
features and fix them; get this app reliable enough. 3 bug-find waves for just the
main function of the app. Be fully autonomous."*

**Definition of "main function" for these waves:** what happens between the user
pressing Send and the assistant's answer landing and ending. That is:

| Area | File | Why it is the main function |
|---|---|---|
| Turn assembly + step loop | `core/agent/AgentLoop.kt` (1538) | The loop itself |
| Context trimming/compaction | `core/agent/ContextPolicy.kt` (98), `core/agent/Compaction.kt` (255) | Decides what the model sees |
| Failure handling | `core/agent/Retry.kt` (226) | What happens when a provider misbehaves |
| Wire format | `core/agent/Wire.kt` (111) | How messages become a request |
| Crash recovery | `core/agent/RunRecovery.kt` (98) | What happens when the process dies |
| Event delivery | `core/event/EventBus.kt` (209) | How the UI learns anything at all |
| Tool execution | `tools/**` | What the agent can actually do |

`OWNER-BRIEF.md` "Honest state" names **exactly** the gap these waves must close:

> *"The five deliberate bug-hunt waves still have not been run … The loop has been
> read start to finish once; `Compaction`, `ContextPolicy`, `Retry`, `Wire`,
> `RunRecovery` and the `ChatViewModel` → `RunEndPolicy` handoff have not."*

So the waves target the never-audited files first. This is not a re-check of
BUG-03/BUG-04.

---

## Verification reality on this host (re-measured, do not re-derive)

**Local Gradle cannot run. At all.** Three attempts, three distinct failures:

1. `./gradlew :core:compileKotlin` → `insufficient memory for the Java Runtime
   Environment … Native memory allocation (malloc) failed to allocate 36368 bytes
   … Chunk::new` (the 2 GiB `ulimit -v` + overcommitted box).
2. With `GRADLE_OPTS=-Xmx512m -XX:MaxMetaspaceSize=256m` →
   `pthread_create failed (EAGAIN) … unable to create native thread`.
3. With `-Xmx384m -Xss512k -XX:ParallelGCThreads=1` →
   `UnsatisfiedLinkError: libzip.so: failed to map segment from shared object`.

**CI is the only build server.** Every wave therefore follows:

1. Write the change + a JVM test that fails without it.
2. Push; wait for **both** jobs green on the new sha.
3. Read the CI log for the `jvm backend` job to confirm the new test actually ran
   and passed (a test that does not run is not a test).

**Do not commit memory tuning to `gradle.properties`** — CI reads it too, and
`MaxMetaspaceSize=224m` + in-process Kotlin previously killed
`:core:compileTestKotlin` with `OutOfMemoryError: Metaspace` (`c391e20`).

## Tooling defect found while setting this up (important)

**The `grep` tool in this environment returns false negatives.** It reported
`No matches` for `fun installApk`, `runWatchdog`, `wrapUpReason`, and even
`package dev.lumen.app.ui` — all of which exist. Verified with real `grep`:

```
$ grep -rn "wrapUpReason" core/src/main/kotlin/ | wc -l
13
```

**Consequence:** never conclude "this symbol does not exist" from the `grep` tool.
Use `glob` to find files and `read` (or shell `grep -rn`) to confirm content.
Every finding in these waves was confirmed by reading the file.

---

## Wave log

| Wave | Scope | Status |
|---|---|---|
| 1 | `Compaction`, `ContextPolicy`, `Retry`, `Wire`, `RunRecovery`, `EventBus` | **DONE `df71a91`**, CI green on both jobs |
| 2 | `AgentLoop` turn/step/tool-execution mechanics | pending |
| 3 | Cross-cutting: `ChatViewModel` → `RunEndPolicy` handoff, tools | pending |

---

## Wave 1 result — `df71a91`

CI run 339, **both jobs success**, **528 tests passed / 0 failed**. Every fix below
is pinned by a test that fails without it.

### Defects fixed

| id | defect | site |
|---|---|---|
| D1 | The 15k retained tail can exceed a small model's whole input budget, so `compact` found nothing foldable and `trim` could not touch the tail → request stayed over the window until the overflow retries wrote the turn as an error | `ContextPolicy.Overflow.keepTokens`, used by `AgentLoop` |
| D2 | `contextWindow == 0` (real: `OpenAiProvider` builds it for undescribed models) was divided by `coerceAtLeast(1)`, so every history looked infinite and the loop compacted on **every step** | `Overflow.UNKNOWN_WINDOW`, `Overflow.effectiveWindow` |
| D3 | A fold that changed nothing was retried forever; the loop could not tell "nothing new to fold" from "cannot shrink" | `Overflow.nextAction` / `StallDecision` |
| D4 | `trim` clipped the compaction summary — the only surviving copy of the folded head | `Compaction.clip` |
| D5 | No mutex around compaction; `compactNow` can reach it outside the run lock | `Compaction.lockFor` |
| R1 | Retries bounded by attempt count but not by the run's wall clock; a 429 storm near the end of the window could sleep ~60 min more | `Retry.clampToDeadline`, `withResilientRetry(deadlineInMs)` |
| R2 | `Retry-After` HTTP-date (legal, common from gateways) failed `toDoubleOrNull()` → null → ~500 ms backoff against an endpoint that asked us to wait | `ProviderErrors.parseRetryAfter`, shared by both adapters |

### Two of my own fixes were wrong, and the tests caught them

Worth recording because it is the whole point of writing the test first:

- `effectiveWindow(2000, 100_000)` reserved the entire window and returned a
  **1-token** budget. The reserve is now capped at half the window.
- `clampToDeadline` treated a **spent** budget (`remaining <= 0`) as "unlimited",
  which is precisely the bug it existed to fix. `null` means unlimited; `<= 0`
  means stop.

### The slicing false alarm — do not "fix" this

Compiling `:core` in per-package slices to work around this host produced:

```
SubagentSpawner.kt:108: error: smart cast to 'String' is impossible, because
'agent' is a public API property declared in different module
```

**That is an artifact of slicing, not a defect.** Slicing compiles `:core`'s
packages as separate modules, so `SubagentSpec.agent` arrives from the classpath
and loses the smart cast. CI (a single compilation of the whole module) is green,
which settles it. Do not bind `spec.agent` to a local here — there is nothing to
fix.

Each wave appends: what was read, what was found, the fix, the test, the sha.
