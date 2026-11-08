# OWNER BRIEF — what "finished" means

This is the acceptance spec for the goal-mode run. It is the owner's own
definition of done, written down so it does not have to be re-explained.

**Status as of `e206d71` (CI green, both jobs).** Update this file when a section lands.

The owner's words, condensed: *"a completely finished and polished bot, UI-wise
and backend-wise, optimised to be smooth as hell so it won't trigger some
people's seizures every time the AI writes something, and an actually usable app
that has all the features a phone developer will ever need."*

---

## A. Motion safety — "won't trigger seizures" (SHIP-BLOCKER)

**Current state, measured:**

- `LumenMotion` has **4 infinite/looping specs**. `pulse` is
  `infiniteRepeatable(1400ms, RepeatMode.Reverse)` and runs whenever a run is
  live — i.e. **for the whole duration of the AI writing**, which is exactly
  the complaint.
- `reduceMotion` exists and is honoured in **only 2 places**:
  `MainActivity.animatedColors` (skips the 420 ms theme cross-fade) and
  `ambient = !state.reduceMotion` (disables the pulse).
- It is **not** derived from the system setting. Android exposes
  `ANIMATOR_DURATION_SCALE`; when a user enables "Remove animations" they expect
  every app to respect it. We do not read it.

**Done means:** ✅ **LANDED `40bae2c`**

1. ✅ `reduceMotion` seeds from `ANIMATOR_DURATION_SCALE` + `TRANSITION_ANIMATION_SCALE`
   (`AndroidSystemMotion` → `DurationScale`, which takes the minimum because either
   channel being 0 means "no motion"). Seeded **once**, so an explicit user choice
   still wins on later launches.
2. ✅ Every looping animation stops under reduce-motion. Audit result: exactly ONE
   loop remained in the app (`LumenChatScreen` pulse); `Bloom` and `WaterShapes`
   are static geometry, not animations. Under reduce-motion the transition is
   never even created, so no frame callback is scheduled.
3. ✅ Streaming pulse is **2000 ms, opacity 0.62 → 1.0**. `LumenMotion.pulse` is
   now a *function* and `MotionPolicy.safeLoopPeriod` clamps to a 1 s floor, so a
   future caller cannot reintroduce a strobe by choosing a smaller number.
4. ✅ Documented in `docs/guide/06-ui.md` §"Motion: the safety rule".

## B. Smoothness while streaming (SHIP-BLOCKER)

The transcript re-renders per token. Findings and status live in
`ui/CHAT-SCREEN.md` (5/14 fixed). Remaining work there is the decomposition
(`#2`) and the cheap `remember`s (`#10/#11/#14`).

**Done means:** scrolling stays at frame rate while a long answer streams, and a
streaming token does not re-execute the top bar, notice cards or sheets.

**Partial — `ede1984`.** The measurement was itself broken and is now trustworthy:
the CI artefact reported `hitchRatio` as a 309-digit `Double.MAX_VALUE` on a
**green** run, then warned "possible hitch" on a smooth animation. `MotionMetrics`
fixes that and two checks are now real assertions (the expansion must move, and
must come to rest). The transcript decomposition itself (`CHAT-SCREEN.md`
#2/#10/#11/#14) is still open.

## C. File manager — "a phone developer will ever need"

**Current state, measured:**

- Files screen supports: browse, search, rename, delete, edit, preview, open
  externally. Good base.
- **APK install exists but is wired only for *mentions*, not from the Files
  screen**: `MainActivity` line ~353 routes `"apk" -> viewModel.installApk(p)`
  for a tapped mention. From Files, an `.apk` falls into the generic branch.
- **PDF: nothing.** No `pdf` handling anywhere in `app/src/main`.
- No mime map: file-type routing is a `substringAfterLast('.')` `when` in
  `MainActivity`, duplicated per call site.

**Done means:**

1. **One mime/action map** for file types, used by BOTH the Files screen and
   mention taps (no duplicated `when`).
2. **APK**: tapping an `.apk` in Files offers Install (existing
   `WorkspaceActions.installApk`). Never auto-installs — confirm first.
3. **PDF**: open via the system viewer with `application/pdf`, deliberately
   **no built-in PDF viewer** (owner's explicit choice). Same for any document
   type: hand off to the phone's app.
4. **Archive**: `.zip`/`.tar.gz` at least *identify*; extraction is optional.
5. Unknown types: show the type and offer "open with", never a dead tap.

**Storage half: LANDED `7084dd2`.** "The app uses 10GB, I need to inspect and
delete precisely" — the screen could only show named categories. It is now
drillable: tap any category to see its largest folders/files (with file counts),
descend, and delete a single entry after a confirm. `StorageScan` never follows
symlinks (a guest rootfs is full of them) and reports `truncated` when its
deadline hits rather than under-reporting. **The type-routing/APK/PDF half above is
still open.**

## D. Terminal — "I can see the terminal the AI uses"

`TerminalScreen` exists and is wired (`MainActivity:525`) as an interactive pty.
The owner additionally wants **visibility into what the agent itself is running**.

**Done means:** from a live run you can see the commands the agent executes, not
just open your own shell. The `bash` tool already streams output; surface a
read-only "agent terminal" view (or a filter in the existing terminal) showing
the current/last commands and their output.

**Related, LANDED `144d108`:** *"New folder → bind the agent to it"*. A new Files
action creates a real **project root** (join `ProjectStore.create` + `setActive` +
`switchTo`) rather than a subdirectory, so the agent's cwd — and therefore every
path tool, the shell, the terminal and the git runner — is confined to it. A
subdirectory could not do this: the agent still sees every sibling file and every
other `.git`, which is exactly how it gets side-tracked.

## E. Optional web mode — "the AI can control a web browser"

**Current state: nothing.** The only `WebView` is `CanvasScreen` (local HTML
preview, remote loads blocked). There is no browser automation of any kind.

**Done means:** an **optional, off-by-default** mode where the agent can drive a
`WebView`: navigate, read the DOM/text, click, type, screenshot. Constraints:

- Off by default; a Settings toggle, clearly labelled as powerful.
- Owned by a dedicated tool (e.g. `browser`) so it can be denied to `plan`/`explore`.
- Must respect the existing approval gate for anything state-changing.
- Local `file://` stays as-is; do not silently enable remote loads elsewhere.

This is the largest new feature here. Scope it as its own wave.

## F. GitHub — "almost everything on GitHub"

See `features/GITHUB.md` (2 API calls today) and `features/GIT-TOOL.md`.
Staged plan already written. Also the owner's stated pain: the git tool is not
good enough to actually do the work.

## G. "I added some cool ideas but they are just not executed right"

The owner has added UI ideas that did not land well. **Before restyling
anything**, fetch the current `lumen-screenshots` artifact and look at every
screen. Identify what is half-executed rather than inventing new design. Ask the
owner which specific screens they meant if it remains ambiguous — do not guess
and rebuild a whole screen.

---

## Non-negotiables

- Do not redesign **Settings**; the owner considers it good.
- Do not add a built-in PDF viewer or browser.
- No animation may strobe. See A.
- `:app` is CI-verified only (no local Robolectric). Always fetch screenshots.
- One writer per file per round (`docs/analysis/PROTOCOL.md`).

## Priority order

1. **A** (motion safety) and **B** (smoothness) — they are why the owner is
   unhappy every time the AI writes.
2. **C** (file manager) and **D** (terminal visibility) — cheap, visibly useful.
3. **F** (GitHub) — owner's most-complained-about feature.
4. **E** (web mode) — largest, do last, optional.
5. **G** — after the screenshots are reviewed with the owner.

---

## Execution log (append-only, with shas)

Every entry is CI-green on both jobs unless stated.

| sha | what |
|---|---|
| `c391e20` | Budget exhaustion takes a wind-down turn (never ends with no answer); subagent reports survive a final tool call; subagent concurrency capped at 7. |
| `8181f73` | `git` gains a `dir` param — nested repos are no longer mistaken for the outer one. Reverted a local-only `gradle.properties` tuning that broke CI metaspace. |
| `0481ef7` | Goal mode renews its budget window while todos remain (was one 60-min window and stop). |
| `40bae2c` | **§A** Motion safety: 1 Hz floor, 2 s / 0.62→1.0 pulse, system-derived reduce-motion. |
| `b74cfc9`, `9def27f`, `cbe6e07` | Test isolation: each store test gets its own DB **file** (deleting the shared one corrupts Robolectric's pooled handle). |
| `ede1984` | `MotionMetrics`: the smoothness artefact reported a 309-digit infinite "hitch" on green runs. Two warnings became assertions. |
| `7084dd2` | **§C storage** StorageScan drill-down + precise delete. |
| `144d108` | **§C files** "New project (bind agent)" — a real project root, not a subdirectory. |
| `efcea8f` | **Composer** context gauge fused with a readable `used / window` label, filling left-to-right. |
| `e206d71` | Test hygiene: context-label expectations; de-raced `ComposerCompletionTest`. |

### Verified facts worth keeping

- **Local Gradle is unreliable on this host.** The box is memory-overcommitted
  (`Committed_AS` ~116–125 GB against a `CommitLimit` of 7.6 GB) and the shell
  carries a hard `ulimit -v 2097152` that `prlimit` cannot raise. The Gradle
  daemon frequently dies during startup before running a single task. Use **CI as
  the build server**; that is also the project's own rule for `:app`.
- **Never commit local memory tuning to `gradle.properties`.** CI reads the same
  file; `MaxMetaspaceSize=224m` + `in-process` Kotlin made
  `:core:compileTestKotlin` die with `OutOfMemoryError: Metaspace` (`c391e20`).
  Keep sandbox tuning outside the repo.
- **A test that passes by ordering luck is a flake.** Two separate shared-state
  bugs hid for weeks in `:app` tests; both only surfaced as one-failure CI runs.
- Reading the `lumen-screenshots` artefact found a real bug (`ede1984`) that no
  test caught. Keep doing it.

---

## Wave 2 — silence and the release

| sha | what |
|---|---|
| `347270d` | A subagent's short mid-run narration is no longer passed off as its report; child step budget 12→24. |
| `495c83d` | **Silence:** `stop(reason)`, a run watchdog, and `RunEndPolicy` (14 JVM tests). |
| `8a75bea` | `stop()` reads the notification title from state (it cannot await the store). |
| `2e854fd` | **Root cause:** `EventBus` retried publishing, so a terminal `StateChanged` can no longer be dropped by a saturated buffer. |
| `1066e0a` | **Released `v0.2.13`** (versionCode 18) — `lumen-0.2.13-debug.apk`. CI + release both green. |

### The lesson from this wave

Every fix below `2e854fd` was correct and CI-green, and **the owner kept seeing
the old behaviour** — because the device was running 0.2.12. A fix that is not
released is not a fix. When a report says "it happened again", check the installed
`versionName` first: `git log -1 --format=%h` on `main` says nothing about what is
on the phone.

### What "never silent" now means

`RunEndPolicy` enumerates every way a run can end and decides what the user is
told. Only two causes may be silent — `USER_STOP` (they did it) and `SUPERSEDED`
(the new run's outcome is the news). Every other cause must produce a non-blank
message, and a JVM test asserts that for `Cause.entries`. The ViewModel cannot
end a run without going through it.
