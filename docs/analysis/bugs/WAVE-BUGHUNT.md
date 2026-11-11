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
| 1 | `Compaction`, `ContextPolicy`, `Retry`, `Wire`, `RunRecovery`, `EventBus` | see below |
| 2 | `AgentLoop` turn/step/tool-execution mechanics | pending |
| 3 | Cross-cutting: `ChatViewModel` → `RunEndPolicy` handoff, tools | pending |

Each wave appends: what was read, what was found, the fix, the test, the sha.
