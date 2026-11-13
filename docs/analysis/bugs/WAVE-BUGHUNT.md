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
| 2 | `AgentLoop` turn/step/tool-execution mechanics | **DONE `240e51c`**, CI green, 322 tests |
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


---

## Wave 2 — `AgentLoop` turn / step / tool execution (`240e51c`)

Two explore agents read the loop end to end. The **tool-execution path came back
clean** across all eight failure classes (stuck PENDING, cancellation-vs-timeout,
two-clock mixing, deadline clamping, malformed args, approval denial, double
execution, unknown tool) — that is a real result, not a gap: those paths are
already pinned by `ses_timeout`, `ses_cancel_tool`, `ses_max_err`, `ses_windup`,
`ses_window`, `ses_greedy`.

### Wave 2 defects fixed

| id | defect | site |
|---|---|---|
| W2-1 | A wind-down turn cut off at `LENGTH` left the caller **no prose**. The `toolCalls.isEmpty()` exit is evaluated before the wind-down branch and its `break` was unconditional, so a reasoning-only model that spent its output budget on reasoning and was cut at LENGTH ended the run with `parts == []` — the empty report the wind-down exists to prevent, and what a subagent's parent reads as "the child produced nothing" | `AgentLoop.ensureWindUpProse` |
| W2-2 | A doom-loop stop explained itself only in refused tool results and a dispatched event. Anything reading the run's final **text** saw an empty answer for a run that stopped for a clear reason | `AgentLoop.ensureStopProse` |

Both are pinned by tests that **failed first**. W2-1 was reproduced by CI with the
literal message `parts were []` before the fix path was complete — recorded because
that is the evidence the test is real.

### A bug in my own Wave 1 work, found here

`remainingBudgetMs` returned `0` to mean "unlimited", and `Retry.clampToDeadline`
(added in Wave 1) reads `<= 0` as "spent". Every retry in an unbounded run would
have failed instantly instead of waiting out a 429. `RunClock` now owns the
three-way distinction as a pure object, and `RunClockTest` pins it.

### Product decisions taken from the owner (not bug fixes)

- **Goal mode has no clock at all.** Owner: *"i made goal mode so that the agent
  never stops till i come and interrupt it.. its just a 60 minute timer instead of
  a 30, cheaply implemented."* Correct. `maxRunMillis = 0` now means **no wall
  clock**; `maxSteps` is null; it ends only on user stop, on the model answering
  without a tool call, or on a real safety backstop that writes its reason.
- **The cost ceiling is advisory.** Owner: *"change this budget thing too i dont
  know why i added it."* It used to END the run. For a mode meant to run until
  interrupted, discarding the work because a forgotten spend limit tripped is
  wrong. It now warns once and continues; Stop is how you end a run.
  `maxInputTokens` keeps its hard stop — an oversized request is rejected by the
  provider anyway, and failing early yields a report instead of a raw 400.


---

## Wave 3 — subagents: prompt caching was never enabled (`82c13d3`)

Owner: *"they are taking an unnatural amount of time and i dont know if cache hits
are even working."* That doubt was correct, and the cause was worse than slow.

**Anthropic caches only where a `cache_control` breakpoint says to, and we sent
none.** Every turn re-processed and re-billed the whole system prompt, the tool
schema and the entire conversation from scratch. Nothing surfaces that — no error,
no warning — it just makes long runs and 24-step children far slower and far more
expensive than they should be. Exactly the reported symptom.

Fixed with two breakpoints (the API allows four total):

- the top-level `system` block — large and stable for the whole session;
- the FINAL content block of the newest turn — so the next step, which appends to
  the conversation, reuses everything before it.

Only the last message is marked; marking every turn would spend the breakpoint
budget for no benefit. A block that cannot carry the marker is left untouched, so
a request Anthropic would reject is never built.

`system` had to change from a bare string to a content-block array to carry the
marker. Anthropic accepts both, and an existing test asserted the string form —
updated rather than deleted, because the text-still-present assertion is still
worth having.

The OpenAI-compatible side needed nothing: it already sends `prompt_cache_key`
derived from the session hint, which is the correct mechanism there (caching is
automatic; the key only pins routing).

Two tests against the MockWebServer harness pin this, asserting the breakpoints
exist AND that earlier turns are not marked. This class of bug is invisible
without an assertion.

### Also checked, no defect found

The child spawn path: the 7-permit gate is taken around the RUN only and inside the
caller's coroutine, so a queued child is cancelled by Stop rather than wedged; the
`EvidenceCollector` subscribes with `UNDISPATCHED` so it cannot miss the child's
early events; child budget is 24 steps; `ok` deliberately means "the run
completed", not "prose was written". No change made.


---

## Feature wave — one file-type router, real git ops (`807b056`)

Seven explore agents were dispatched; two reported. One report was **wrong** and is
recorded here so nobody acts on it: it claimed `GitTool` "is registered nowhere —
the whole tool is dead code". It is registered, in
`ChatViewModel.kt:3899`, with the token factory wired (`token = { keys.githubToken }`).
It is simply not in `DefaultTools.registry()`, which is correct — `DefaultTools`
cannot know a token. Verification caught it; had the report been trusted, the
"fix" would have moved a working tool for no reason.

### Verified and fixed

| id | finding | fix |
|---|---|---|
| FEAT-1 | **Four** independent file-extension tables disagreed: the Files row tap, the Files menu, `openFileExternally`'s mime map, and the chat-mention router. A built `.apk` opened in the **text editor** from Files (binary mojibake) while the same file in a chat message offered install; `.pdf`/`.zip`/`.tar.gz` fell into the editor too; the mime map knew only images, so every other type went to the system as `application/octet-stream` and "open" failed silently | `ui/FileTypeRouting` — one total map, 8 routes, used by all four call sites. Files gains an **Install** item for APKs (menu = the confirmation) and a labelled "open with" for PDFs/archives |
| FEAT-2 | `merge`, `rebase`, `stash`, `tag`, `blame`, `cherry-pick`, `config`, `pull --rebase`, `push --tags` all missing. The sandbox shell has no `GITHUB_TOKEN`, so a manual `git pull --rebase` after a rejected push **cannot authenticate** — a diverged branch was a dead end | added, each validated (no `-`-prefixed refs), no force, no `reset --hard` |

Tests: `FileTypeRoutingTest` (15 plain-JVM cases, one per original defect) and
three new `GitToolTest` cases (exact argv, no token in argv, refusal of bad input).

### Two of my own tests were wrong

Both were expectation errors, not product bugs, and CI caught them:
`GitTool.quote()` single-quotes every argument so the expected strings need that
quoting; and `blame` resolves its path through `insideCwd`, so it is absolute. A
third assertion I wrote was vacuous (`length.coerceAtMost(2).coerceAtLeast(1)`
can never fail) and is now a real check.

### Not done

Four of the seven feature agents' areas (terminal, models, storage/projects,
settings) were not reported on. The full UI rework — Settings as the reference
standard, chat included — has not started.


---

## UI rework, pass 1 — speak Settings' vocabulary (`801f553` … `bd40586`)

Owner: *"take the settings screen as reference … the settings screen is what i want
from every other ui element you should even change the chat ui aswell."*

The root cause of the drift was structural: `SettingsCard`, `RowDivider`, `NavRow`,
`ToggleRow` and `FieldRow` were **private to `SettingsScreen.kt`**. No other screen
could call Settings' vocabulary, so each approximated it — Home chose mono
everywhere, Files drew one bordered card per row on a 6dp gap, GitHub grew its own
`SectionLabel`. Alignment was impossible until those helpers were shared.

| sha | what |
|---|---|
| `801f553` | Hoist the five helpers to `LumenControls.kt` as `internal`, bodies unchanged. Enabling change only |
| `e1c5edd` | **Home**: 26 prose nodes mono -> `LumenFonts.label`; removed the invented 10sp/1.2-tracking third type tier |
| `15395fd` | **Files**: 25 prose nodes converted; breadcrumb ROOT gained `maxLines`/ellipsis/`widthIn(150dp)` — a long project name used to scroll the whole trail off-screen |
| `b01dd5b` | **GitHub/Projects/Storage/Diagnostics**: 50 prose nodes converted; killed the wide-tracked mono caption idiom (0.4/0.6/1/2sp), which Settings never uses |
| `bd40586` | **Chat**: furniture only (EmptyState, ErrorNotice, HintNotice, UndoNotice, ChangesCard, TodoBoard, UsageMeter). Transcript CONTENT stays mono on purpose |

### The rule now recorded in code

`LumenFonts.label` (proportional) for anything a human reads; `LumenFonts.value`
(mono) only for machine values — paths, ids, sizes, timestamps, model refs. Terminal
and Canvas are exempt: a terminal is supposed to be mono, and the canvas renders
the user's own HTML.

### Verified by looking at the artefact, not by assuming

Fetched the CI screenshots before and after and diffed them pixel-wise. Every
touched screen changed: projects 10.2%, github 4.0%, home 2.6%, storage 2.3%,
files 0.6%. A source-only change that never reached the render would have shown 0%.

### Still open in the UI rework

- **Files is still structurally different**: one bordered card per entry on a 6dp
  gap, two-line rows. Settings groups siblings in ONE card with hairline dividers
  and single-line rows with a trailing mono value. That is a real layout change on a
  screen with a 935-line tag-asserting suite, so it gets its own commit.
- Screens not yet structurally converted (type only so far): Projects, Storage,
  Diagnostics, ModelPicker.
- No judgement made on whether the result actually *looks* better — that needs eyes
  on the PNGs, which this environment cannot do.


---

## Where this session stopped (`4daa70f`, released as `v0.2.20`)

### Shipped and CI-verified this session

| wave | result |
|---|---|
| 1 | Seven context/retry defects (retained tail vs window, zero context window, non-converging folds, clipped summary, compaction mutex, retry vs wall clock, `Retry-After` HTTP-date) |
| 2 | Two loop defects (empty answer on a `LENGTH` wind-down; doom-loop stop with no prose) + the owner's two decisions: **goal mode has no clock at all**, **the cost ceiling is advisory only** |
| 3 | Anthropic prompt caching was **never enabled** — no `cache_control` breakpoints, so every turn re-billed the whole prompt. Fixed with two breakpoints |
| feature | `FileTypeRouting` (one map replacing four disagreeing tables; APK install now reachable from Files), and the git ops a workflow needs (`merge`/`rebase`/`stash`/`tag`/`blame`/`cherry-pick`/`config` + `pull --rebase` + `push --tags`) |
| bugs | New chats were never filed under a project; model search matched the id not the visible label; a hard "vision" block was added and **reverted** because no shipped model declares `supportsVision` |
| UI pass 1 | Hoisted Settings' five row/card helpers to `LumenControls` (they were `private`, which is *why* every screen drifted), then aligned Home / Files / GitHub / Projects / Storage / Diagnostics / Chat-furniture to Settings' type roles |
| screenshots | Three pairs of screenshot scenarios rendered byte-identical PNGs (the "merged tools" shot showed the plain chat, the "subagent" shot had no subagent). Fixed, plus a guard test that fails on any duplicate |

### Explicitly NOT done — the queue for next session

1. **PERF-02 chat-open latency** (owner-reported, `STATUS.md` Tier 2). Measure first.
2. **Storage controller for 20 GB installs.** Verified gaps, all in code:
   - `MAX_SCAN_NODES = 200_000` (`ChatViewModel.kt:4228`) makes `sizeOf` return **0**
     past the cap, and `StorageCategory` has no `truncated` flag → **category totals
     under-report silently**. Worst of the set.
   - `SCAN_BUDGET_MS = 20_000` is shared across all trees, caches measured first.
   - `StorageScan.DEFAULT_LIMIT = 40` — entries past 40 are unreachable at any depth.
   - `StorageScan.DEFAULT_BUDGET_MS = 4_000` per level.
   - No free/total device space anywhere (`usableSpace`/`freeSpace` unused).
   - No "largest files anywhere" view, no multi-select delete, no delete progress or
     cancel, no undo/trash.
   - Deletion *safety* is correct today (inside-app-roots, symlink-refused,
     root-refused, confirmed) — do not regress it.
3. **Files structural alignment** — one `SettingsCard` per group with `RowDivider`,
   single-line rows with a trailing mono value. Deferred because `FilesScreenTest`
   is 935 lines of tag assertions.
4. **TERM-01** — no agent-command visibility exists at all (`TerminalState` has no
   command/exit fields; nothing bridges `BashTool` to it).
5. **Screens with type-only treatment** — Projects, Storage, Diagnostics,
   ModelPicker got typography, not structure.
6. **Chat top bar** — the owner is happy with it; left alone deliberately.

### Two open decisions the owner should make

- **Vision**: `supportsVision` is parsed, merged and persisted but never enforced,
  because **no catalogue entry declares it**. Enforcing it (as a hard block) breaks
  every image send. Either populate the catalogue truthfully, or leave it advisory.
- **Chat transcript typography**: transcript *content* is deliberately still mono
  (tool args, paths, markdown are code being read). If the owner wants prose answers
  in the label font, that is a design call, not a bug fix.

### Environment facts re-confirmed this session

- Local Gradle **cannot run** (three distinct failures: malloc, `pthread_create
  EAGAIN`, then `UnsatisfiedLinkError` on `libzip.so`). CI is the build server.
- **The built-in `grep` tool returns false negatives.** It failed to find
  `fun installApk`, `wrapUpReason`, `textChunks`, `MAX_SCAN_NODES`, `messages` and
  even `package dev.lumen.app.ui` — all present. Use shell `grep -rn`.
- Subagent reports were **wrong four times** in ways that looked authoritative
  (`GitTool` "registered nowhere" — it is, at `ChatViewModel.kt:3899`;
  `terseReplies` "never read" — `:3931`; `notifyOnComplete` "never read" — `:3960`;
  `textChunks` "undefined" — `MarkdownText.kt:91`). **Verify every claim by reading
  the code** before acting on it.
- `out.txt` (contents `hi`) is untracked in the repo root and NOT gitignored. It was
  kept out of every commit. Delete it.
