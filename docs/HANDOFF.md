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

## 3. Architecture (all green up to `ae6ee1d`)

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

## 3a. Pending on-device retest (user, next session)

A stop-mid-run then immediate re-send crashed with a duplicate `LazyColumn` key,
and a build task appeared to "do nothing" then `cannot open index.html`. Fixed
in `df63624` (replace the optimistic row on send; rebuild on StateChanged idle;
`StepMapper.dedupeById` last-wins; refuse to start a run while the previous
coroutine is still alive). The user has the new APK and will retest. When they
send the diagnostics log, check: is there a `tool:` line, and does a `perf:`
line appear? No `tool:`+`perf:` present = the model narrated without calling a
tool (UI needs progress feedback); no `perf:` at all = the run coroutine is
blocked. The first-use rootfs install running inline inside `prompt` is the
prime suspect for a long "working with no output" stall.

## 4. CI status — all green

The previous KNOWN RED (`ComposerCompletionTest` peek backlinks) is fixed:
`peek-backlinks`/`backlink-0` are merged away by the peek sheet's
`Modifier.clickable`, so the test asserts through `useUnmergedTree = true`.
Head `ae6ee1d`: `jvm backend` + `android app (robolectric)` both success.

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
