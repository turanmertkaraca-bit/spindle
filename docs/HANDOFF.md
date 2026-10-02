# Lumen — session handoff (read me first)

Native Android agent app. `spindle` repo, package `dev.lumen.app`. Merged from
the old `opencode-android` (Java/Views + bundled server + Debian proot) into a
fully native Kotlin/Compose app on spindle's in-process engine. Function-first;
UI polish is the final pass.

```
██  NEVER COMMIT tokens/secrets. The git remote already embeds the PAT.  ██
██  Never paste the PAT into chat (auto-revokes).                        ██
```

## 1. Repo + build

- Repo: `/data/user/0/ai.opencode.app/files/projects/playground`
  (`turanmertkaraca-bit/spindle`), branch `main`.
- Push works from the guest: `git push origin main` (remote has the token).
- Local fast loop (pure JVM only):
  `bash -lc '. /root/env.sh && cd /data/user/0/ai.opencode.app/files/projects/playground && ./gradlew :core:test :sandbox:test :tools:test :provider-openai:test :provider-anthropic:test :store-sqlite:test :server:test --no-daemon --console=plain'`
- `:app` is **CI-only** (no Android SDK in the guest).
- Helpers (guest `/root/`): `ci.sh` (run/job status), `log.sh` (failing job log
  → `/tmp/log.txt`), `apk.sh` (download `lumen-debug-apk` → Downloads),
  `shots.sh` (download `lumen-screenshots` → Downloads).
- `.ref/getart.pl` (in-repo) extracts an artifact id from artifacts JSON.
- Read screenshots directly with `read` at
  `/storage/emulated/0/Download/lumen-shots/<name>.png`.

## 2. CI

`.github/workflows/ci.yml`: jobs `jvm backend` + `android app (robolectric)`
(`:app:testDebugUnitTest` + `assembleDebug`, artifacts `lumen-debug-apk`,
`lumen-screenshots`, `unit-test-reports`). Poll by sha:
```
SHA=$(git rev-parse --short=7 HEAD)
for i in $(seq 1 30); do out=$(bash /root/ci.sh); echo "$out" | head -1
  echo "$out" | grep -qE "conclusion=(success|failure|cancelled)" && { echo "$out"; break; }
  sleep 30; done
```
`ci.sh` can print a stale run first — always match `sha=$SHA`.

## 3. Architecture (all green up to `973a63b`)

Modules: `:core` (pure JVM: model, agent loop, events, SPIs, markdown parser,
references resolver, revert engine, UI math), `:sandbox` (TarGz + InAppProxy),
`:tools`, `:provider-openai`, `:provider-anthropic`, `:store-sqlite`, `:cli`,
`:server`, `:app`.

Key capabilities already working on device:
- Agent loop: approval policy (ALLOW/ASK/DENY), agent modes (build/plan/explore/
  general), AGENTS.md rules, structured `FileEdit` + pre-edit snapshots, usage
  events, session search/fork/rewind.
- Linux userland: bundled **static busybox** applets first on PATH (cures
  SIGSYS "Bad system call"), Alpine rootfs wrappers, opt-in Debian proot layer
  (`DebianEnvironment`, not auto-downloaded). `targetSdk 28` exec exemption.
- Chat: merged think→answer, markdown rendering, distinct compact tool cards
  (collapsed; expand for bounded scroll output), subagent call tree, live todo
  board, changes card w/ per-file revert, usage meter, sent-image thumbnails,
  `↓ new` cue + haptic, reading-friendly scroll, no spine rail.
- Files cockpit, interactive terminal, sandboxed HTML canvas, vision attach,
  websearch tool, session search/fork UI, storage manager, diagnostics log.
- Tool inspector: `durationMs` + tool metadata carried into `UiStep.toolMetadata`
  and shown in the tool card body.
- Background resilience: `RunService` (ongoing notification + partial wake lock)
  keeps long runs alive; `Session.state` is persisted across RUNNING/IDLE/ERROR
  and orphaned RUNNING sessions are reconciled to IDLE on cold start.
- Indirect changes: `WorkspaceWatcher` polls the workspace during a run and folds
  script-made writes into the Changes view.
- Budget: per-session cost ceiling is configurable in Settings (off / $0.50 /
  $2 / $5, persisted in KeyStore). The loop emits `AgentEvent.BudgetWarning` at
  `ContextBudget.warnAtFraction`, stops at the ceiling, and the usage meter shows
  spend against the limit.
- Adaptive two-pane: files cockpit shows list + editor side by side on >=600dp.
- On-device perf probe: `PerfSampler` samples frame jank + heap during a run and
  appends a `perf:` line to diagnostics (copyable). First sweep is clean; a
  stopped run no longer logs a cancellation error.
- Search: the app's `AndroidSessionStore` now maintains an FTS5 `message_fts`
  index (kept in sync on insert/update/rewind/delete) and falls back to the old
  linear scan when the platform SQLite lacks FTS5.

## !!! TOP PRIORITY — app kills the run when backgrounded

User report: "give it a job, go back into the app, it looks like the app
immediately killed itself as soon as I got out." A run does **not** survive the
user leaving the app, which makes the app useless for real jobs. This is the
foreground-service/lifecycle gap, and it is the first thing to fix.

What exists already (verify it actually works on device):
- `RunService` (foreground, `dataSync`, ongoing notification + `PARTIAL_WAKE_LOCK`)
  is started in `ChatViewModel.send()` and stopped in `stop()`/finally.
- `ChatViewModel.onCleared()` was changed to `runJob?.cancel()` +
  `RunService.stop(...)` — correct only if the VM is truly being destroyed.
  Unverified whether a backgrounded Activity destroys the VM or the process is
  being killed outright.

Prime suspects, in order (assign one agent each):
1. **Process death on background.** Look for an OOM/`killProcess`, `finish()`
   in `onStop`, `android:noHistory`, or a stray `System.exit`/`Runtime.halt`.
   Check `MainActivity` (`onStop`/`onDestroy`/`onTrimMemory`) and anything that
   calls `closeChat()`/`finish()` when the task leaves foreground.
2. **`onCleared` cancelling the run.** If the Activity is recreated/the VM is
   cleared while backgrounded, `runJob?.cancel()` kills the run. Move the run off
   `viewModelScope` into a process-scoped owner (`RunService` or an
   Application-scoped `CoroutineScope`) so leaving the UI cannot cancel it.
3. **Missing `<service>` runtime behavior.** Confirm `startForeground` actually
   runs (notification appears) and the FGS type/permissions are satisfiable at
   `targetSdk 28`. Android 12+ can throw on a background FGS start; 14+ caps
   `dataSync` FGS in the background.
4. **Run coroutine tied to the UI.** Even with the service alive, the loop runs
   in `viewModelScope`; the service is only a keep-alive shell. The robust fix is
   to host the loop in the service/`Application` scope and have the VM observe it.
5. **`cannot open <file>` after a build task.** The agent narrates "I'll build X"
   then no next bubble; `openFile` then errors. Check whether a mutating tool
   emits a resolvable path, whether the model narrated without calling a tool,
   and relative-vs-absolute resolution in `ChatViewModel.openFile`/`inWorkspace`.

On-device evidence per repro (user can run & paste):
- `Settings → diagnostics → copy` (has `run start`, `tool:`, `perf:`, errors).
- Does a `perf:` line appear after backgrounding (run survived) or never (died)?
- `adb shell dumpsys activity processes | grep -i lumen` right after backgrounding;
  `adb logcat -b crash` for a native/ANR kill;
  `adb shell dumpsys batterystats dev.lumen.app` tail for FGS/wakelock.

## 3a. Pending on-device retest (lower priority)

A stop-mid-run then immediate re-send used to crash with a duplicate
`LazyColumn` key. Fixed in `df63624` (send replaces the optimistic row; rebuild
on `StateChanged` idle; `StepMapper.dedupeById` last-wins; refuse to start a run
while the previous coroutine is alive). Not yet retested on device.

## 4. CI status — all green

Head `973a63b`: `jvm backend` + `android app (robolectric)` both success. The
old peek-backlinks KNOWN RED was fixed (`useUnmergedTree = true`).

## Docs index (read with this file)

- `docs/PLAN.md` — milestones M0–M10 + merge track m11–m15 (status checkboxes).
- `docs/CAPABILITIES.md` — capability model, parity checklist, app NON-GOALS.
- `docs/SPEC.md` — engine contracts (loop, events, SPIs, wire protocol).
- `docs/PROVIDERS.md` — provider configs and the OpenCode Go session header.
- `docs/UI-POLISH.md` — deferred final polish pass (do NOT regress fixed items).
- `docs/PARITY.md` — what was ported from opencode and what was dropped.

## 5. Deferred UI polish (user explicitly parked to the end)
See `docs/UI-POLISH.md`. Headline: composer/input-box feel, and the top-bar
`fork`/`files`/`shell` buttons look plain. Do NOT regress the already-fixed
items listed there.

## 6. Non-goals (do not build)

MCP, LSP, plugins, OAuth providers, keyless free tier, HTTP server/SSE bridge,
git-commit UI (v1), local models (v1). Provider routing parity (Zen/Go
`/responses` + `/messages`) is parked until we can record a real keyed SSE.

## 7. Tool-harness gotcha (not the app)

Occasionally a tool call is emitted as literal `<parameter name="bash">…` text
instead of a real call — nothing runs, and it looks like a network stall. It is
a model/harness artifact after long turns, not the app or the server; restarting
the session clears it. Keep commands short; avoid giant single-line blocks.
