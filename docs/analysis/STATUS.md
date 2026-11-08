# STATUS — the ranked work queue

**Start here after `HANDOFF-NEXT.md`.** This is the ordered list of real work.
Each item names the analysis file that has the detail. Mark items done with the
commit sha and a one-line result.

Legend: `[ ]` todo · `[~]` in progress · `[x]` done · `[—]` deferred

> **The full acceptance spec is [`OWNER-BRIEF.md`](OWNER-BRIEF.md).** It states
> what "finished" means in the owner's terms. This queue is the ordered
> implementation of it.

**Last CI-green sha: `e206d71`** (both jobs: `jvm backend`, `android app
(robolectric)`; 576 `:app` tests, 259 `:core`, 105 `:tools`).

---

## Tier 0 — ship-blockers (owner's core complaint)

### [x] MOTION-01 · Streaming animation must not strobe; honour system reduce-motion — FIXED `40bae2c`
**Detail:** `OWNER-BRIEF.md` §A · `docs/guide/06-ui.md` "Motion: the safety rule"

Done:
- `LumenMotion.pulse` was a **`val`** holding `infiniteRepeatable(tween(1400,
  Reverse))`; a `val` cannot enforce anything, so every call site inherited it.
  It is now `pulse(periodMs)` and `MotionPolicy.safeLoopPeriod` raises anything
  under 1 s to the 1 s floor.
- Streaming pulse is now **2000 ms, opacity 0.62→1.0** (was 1400 ms, 0.35→1.0).
- `AndroidSystemMotion` reads `ANIMATOR_DURATION_SCALE` +
  `TRANSITION_ANIMATION_SCALE`; `DurationScale.reduceMotion` takes the minimum.
- `KeyStore.reduceMotion` **seeds from the system value** on first read and
  persists, so an explicit user toggle still wins later.
- Under reduce-motion the `rememberInfiniteTransition` is never created, so no
  frame callback is scheduled at all.
- Rules live in `ui/MotionPolicy.kt` + `ui/SystemMotion.kt` as pure Kotlin →
  verified by `MotionPolicyTest` (15 JVM cases), not only by CI.

### [x] PERF-01 · The smoothness metric itself was broken — FIXED `ede1984`
**Detail:** `ui/CHAT-SCREEN.md`

Found by reading the CI artefact (as the protocol requires). `metrics.txt` from a
**green** run contained `hitchRatio=1797693134862315700…(309 digits)….00`:
`Double.MAX_VALUE` formatted with `%.2f`. Three bugs in one expression —
a sentinel for "median ≈ 0" (the normal case after a spring settles), printed as a
number, which then tripped its own `> 6.0` "possible hitch" warning on a healthy
animation. `MotionMetrics` now returns `Double?` and prints `n/a (median ~ 0)`.
Two checks were promoted from `println` warnings to real assertions: the expansion
must move, and it must come to rest.

**Remaining** PERF-01 work (the transcript decomposition, `CHAT-SCREEN.md`
#2/#10/#11/#14) is still open — see Tier 3.

---

## Tier 1 — visible bugs the owner reported

### [x] BUG-01 · Every model shows "free" — FIXED `c4408c6`
### [x] BUG-02 · Large tool output crashed the app — FIXED `57c3395`
### [x] BUG-03 · Subagents lose their entire report — FIXED `c391e20`
**Detail:** `docs/analysis/bugs/BUG-03-subagent-output-lost.md`
The child's last turn is normally a **tool call** (that is how `maxChildSteps`
ends), so `finalMessage.parts.filterIsInstance<Part.Text>()` was empty and the
parent received the literal string "(subagent produced no text)" after 250k
tokens of real work. Now falls back to the newest earlier assistant text; a
throwing child's findings are salvaged too.

### [x] GOAL-01 · Goal mode "keeps stopping" — FIXED `0481ef7`
The goal prompt promises "work until the goal is met"; the code gave it one
60-minute window and `break`ed. Now `renewsBudget` + `maxBudgetWindows`, gated on
outstanding todos, with the wall clock measured per **window**.
Also: any budget exhaustion now takes one wind-down turn with `tools = []` so the
run always ends with a written report.

### [ ] FEAT-01 · GitHub surface is far too thin
### [ ] FEAT-02 · The agent's `git` tool is not good enough
**Partial:** `8181f73` added the `dir` parameter so nested repos are not mistaken
for the outer one (this was the "side-tracked by other projects" defect).

---

## Tier 2 — structural

### [x] ARCH-01 · Subagent concurrency cap = 7 — FIXED `c391e20`
`Gate` (Semaphore + high-water mark) with a process-wide `sharedGate`, acquired
around the RUN only and inside the caller's coroutine so Stop cancels queued
children and never leaks a permit. A queued child emits a Progress event.

### [ ] ARCH-02 · Context waste — `arch/CONTEXT.md`

---

## Tier 3 — UI quality

### [x] FILES-01 · Storage manager: inspect and delete precisely — FIXED `7084dd2`
**Detail:** `OWNER-BRIEF.md` §C (storage half)
`data/StorageScan` lists a directory's children with recursive sizes, largest
first, **never following symlinks** and reporting `truncated` when the deadline
hits. The storage screen is now drillable (tap any category → largest folders and
files → descend), with a per-entry Delete that confirms. Deletion is refused
outside the app's own roots, through a symlink, or on a root itself.
11 JVM tests in `StorageScanTest`.

### [x] FILES-02 · "New folder → bind the agent to it" — FIXED `144d108`
**Detail:** `OWNER-BRIEF.md` §C
`createDir` makes a *subdirectory*, so the agent still sees every sibling file and
every other `.git` — the exact way it gets side-tracked. New Files action
**"New project (bind agent)"** creates a real project root via
`ProjectStore.create` + `setActive` + `switchTo`, moving the shell cwd, path
tools, terminal and git runner with it. `ProjectStoreTest` pins that a bound
project is a **sibling**, never nested.

### [x] UI-01 · Context-limit bar fused into the composer — FIXED `efcea8f`
It was half-fused: a 3dp hairline with **no label**, draining right-to-left (so a
nearly-full context looked nearly empty), and hidden until the first turn had
already spent tokens. Now: "context" + "12.4k / 200k" above a 4dp fill that grows
left-to-right and warms water→accent→alert. The duplicate `ctx N` in the floating
usage line is gone. Formatting lives in `ui/ContextLabel` (12 JVM tests).

### [ ] UI-02 · `LumenChatScreen` decomposition + remaining perf items
**Detail:** `docs/analysis/ui/CHAT-SCREEN.md`

---

## Tier 3b — phone-developer file manager / terminal

### [ ] TERM-01 · Show the agent's own commands in the terminal view (`OWNER-BRIEF.md` §D)

## Tier 4 — new capability

### [ ] WEB-01 · Optional browser-control mode (`OWNER-BRIEF.md` §E)

---

## Test-infrastructure debt found and fixed (was silently red/flaky)

- `b74cfc9` `AndroidSessionStoreTest` shared ONE on-disk `lumen.db` across all 19
  tests, so `prune` saw rows it never created. Isolation is now a distinct DB
  **file** per test (`cbe6e07`); deleting the shared file instead causes
  `SQLiteDatabaseCorruptException` because Robolectric pools the handle.
- `e206d71` `ComposerCompletionTest` raced a `Dispatchers.IO` hop inside a
  `LaunchedEffect`; `waitForIdle()` alone does not cover it. Now polls.

## Done (do not redo)

- Runs C–F: resource limits, thermal hysteresis, UI recomposition fixes, docs,
  audit-tail triage, duplicate project names.
- `/goal` mode, `/goal` budget renewal.
- Released as **v0.2.8**; `0.2.12` on `main`.

---

## How to add an item

Append it under the right tier with: an id, one-line description, a pointer to
an analysis file. If it is non-trivial, write the analysis file first — an item
without a detail file forces the next agent to start from scratch, which is the
exact problem these docs exist to prevent.
