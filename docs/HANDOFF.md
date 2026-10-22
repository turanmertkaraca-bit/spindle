# Lumen — session handoff (READ FIRST)

Native Android agent app on the `spindle` engine. Repo
`turanmertkaraca-bit/spindle`, branch `main`, package `dev.lumen.app`.
Latest code HEAD = `c8f1ed6`, version **0.2.8** (`versionCode 13`); the 0.2.8
wave (project isolation, storage clearing, Files rework) lands on top of
`c8f1ed6`. CI
(`.github/workflows/ci.yml`) runs both jobs (`jvm backend` + `android app
(robolectric)`) — always re-verify CI by sha with the `ci.sh` poll in §4 before
trusting a build. Releases **v0.2.0**–**v0.2.6** are published, each with a
`lumen-<ver>-debug.apk` asset; **v0.2.8** is the current version. The debug APK
is signed with the committed `debug.keystore` so it installs over the previous
build. A fresh debug APK is staged on device at
`/storage/emulated/0/Download/lumen-debug.apk`.

```
██  NEVER COMMIT tokens/secrets. The git remote already embeds a PAT.      ██
██  NEVER paste the PAT or an API key into chat or a doc. Rotate keys.     ██
██  Live network tests are manual only; CI never spends tokens.            ██
```

## 0. Your task / how to work (obey this)

> **NEXT AGENT, READ THESE BEFORE TOUCHING ANYTHING.**

- **NEVER use the question tool.** If you must ask the user something, ask in
  plain text in your reply.
- **Use subagents as much as possible — heavy fan-out is explicitly wanted.**
  Prefer many parallel `explore` (read-only) and `general` (writer) subagents
  over doing work inline. **One writer per file per round**; sequence agents
  that must touch the same file; give each a **frozen interface contract**
  (exact param names/types) and the **test tags to preserve**.
- **The UI is FROZEN.** The owner has signed off on the current look. Do NOT
  make UI/visual changes unless explicitly asked.
- `:app` **cannot build locally** (no Android SDK) — CI/Robolectric is the
  verifier. JVM modules build locally.
- Java: `bash -lc '. /root/env.sh && …'`. Keys pre-staged at
  `/root/live-keys.env` (mode 600, outside the repo); source it, never write a
  key to a file or log.
- Workflow: implement with subagents → local JVM tests → commit/push → poll CI
  to green by sha → fetch the screenshot artifact and **look** → iterate. Never
  land a red commit on `main`.
- Release flow: bump `versionCode`/`versionName` in `app/build.gradle.kts` → CI
  green → download the `lumen-debug-apk` artifact → `git tag` / push → create a
  GitHub Release via the API and upload the APK asset → refresh
  `/storage/emulated/0/Download/lumen-debug.apk`.

## 1. What's done (in force)

The 0.2.x waves landed since the last handoff; all are CI-green.

- **Provider resilience**: `ProviderEvent.Failure` is typed
  (`statusCode`/`retryAfterMs`/`retryable`/`contextOverflow`); context overflow
  now compacts-and-retries instead of hard-erroring; retry hygiene (`maxRetries
  5`, saturating backoff, `Retry-After` honored); `max_tokens` set from the
  model; OpenAI/Anthropic parse in-band error frames.
- **Token/cache accounting**: OpenAI/DeepSeek usage normalized to uncached-miss
  `inputTokens`; DeepSeek `prompt_cache_hit/miss_tokens` parsed; `Usage.totalTokens`
  counts cache read/write; overflow estimate is provider-consistent, handles
  fully-cached turns, and includes the tool-result tail; provider-reported cost
  (OpenRouter) preserved; duplicate usage frames deduped; empty/reasoning-only
  wire turns skipped; estimator excludes reasoning/base64 and uses a flat
  per-image cost; live provider context window wins over the embedded snapshot;
  OpenAI `prompt_cache_key` sent from the session hint.
- **Streaming**: partial streamed output is checkpointed (~1s) so a killed run
  keeps the answer.
- **Durable event stream**: `EventBus` assigns a monotonic `seq` under one lock +
  bounded replay ring (`replayAfter`/`lastSeq`); server SSE frames carry `id:`;
  reconnect `Last-Event-ID` replay is atomic with broadcast and exactly-once per
  subscriber (drops frames `<= lastSent`); app gap detection + store resync;
  `onResume` re-reads the open session so a missed terminal cannot wedge `busy`.
- **Runtime**: the device shell is cancellation-aware and kills the process
  group; per-file edit locks; bounded/cancellable glob; malformed tool args
  return a rewrite-requesting error; proot tears down SIGTERM-first. New: per-tool
  timeout (`Tool.timeoutMs`, default 120s; web 60s, glob/grep/skill 45s;
  bash/task/question `0` = none) enforced once in `AgentLoop.executeTool` with a
  typed timeout ERROR that does not abort the run; doom-loop guard
  (`AgentConfig.maxRepeatedCalls = 3`) fails repeated identical calls and stops;
  wildcard permission matching (`ApprovalRules`: `*`/`**`/`?`) for remembered
  rules; `external_directory` surfaced when `downloadsAccess` is granted;
  proot/shell teardown waits on the target process between group-TERM and
  group-KILL so `--kill-on-exit` reaps tracees; watcher truncation notice
  surfaced in the Files view.
- **Terminal**: `ShellExecutor.runStreaming` + no-deadline mode (`timeoutMs <= 0`
  = run until the agent stops) with throttled live progress; bash keeps head AND
  tail; over-cap tool output spills to `<cwd>/.spindle/truncated/<tool>-<call>.txt`
  with a read hint.
- **Run reliability**: retry clears the persisted partial; process-wide
  per-session run locks; compaction folds long single-prompt runs and `trim`
  reports to the UI; `RunRecovery` runs at process startup; crash-proof event
  collector; deleting a live session cancels the run + sweeps orphan rows; prune
  spares RUNNING.
- **Storage**: versioned transactional migrations (`user_version`); retention
  janitor (startup + every 6h); FTS partial-index repair; corrupt-DB
  quarantine+recreate; per-part upsert (`SessionStore.updatePart`);
  `RunRecovery.reconcile` + targeted `updateSessionState`; content-addressed
  snapshots (`snapshot_blobs(sha256 PK, content)`; migration 4 on Android, 3 on
  store-sqlite; rows keep id/sequence; blank-sha legacy rows keyed by id so no
  body is lost; orphan blobs GC'd); root-only session prune + cascade delete of
  dropped subtrees + `sweepSubagentSessions` age sweep; DB connection
  lease/refcount (`AndroidDatabase.lease`, `closed` flag, no reopen after close).
- **UI**: palettes **prism / ember / phosphor / abyss** (default now **abyss**);
  adaptive "terminal window" launcher icon + cold-start `windowBackground`; Home
  **green LIVE badges** for running sessions; per-session completion/error
  notifications with a custom `res/raw/error_dong.wav` error chime, a session-title
  headline + `finished/failed · duration` body, deep link to the session, Settings
  toggle; a11y content descriptions +
  minimum touch targets; composer is a wrapping multi-line field; the
  **delegate** mode chip was removed (UI only; the agent config still exists);
  Settings → subagent model (inherit or a provider model); scroll-position-
  triggered `↓ latest` arrow; live model context window precedence fix.
- **Features**: session export/import as redacted JSON (Home actions); guarded
  git commit/push from the GitHub screen (token via env only, never force).
- **UI + notifications (0.2.3)**: Settings regrouped into tight labelled cards
  (**connection first; the API key is promoted to the top**; model/subagent model
  are single rows into the shared picker); new shared primitives `LumenFonts`
  (mono values / proportional labels), `LumenSegmented`, `LumenSwitch`,
  `LumenSlider` (**the default Material purple slider is gone**) and
  `LumenBottomSheet`; model **favourites** (pinned across providers, provider-
  labelled) + recents in `KeyStore`/`ChatViewModel`; a dedicated in-chat
  `ModelPickerSheet` off the composer chip; quick settings is now a minimal real
  sheet (model shortcut · agent · compact now · links); the question card scrolls
  and requires an explicit **answer** (a tap stages only); Home status pills
  de-duplicated. Run completion notifications now headline the **session title**
  with `finished/failed · 1m 35s` and the app icon (was bodyless "Run finished"
  rows with a download glyph).
- **0.2.4 wave**: **GitHub "Install git" fixed** — `GitRunner.installGit()`
  detects the active sandbox package manager (`apt-get` on Debian, `pkg` on
  Alpine) instead of always calling the Alpine `pkg` front-end (which failed on a
  Debian guest with `pkg: command not found`). **Palette-following launcher
  icon**: a monogram-L mark on a per-palette gradient, shipped as four
  `activity-alias` launchers (`LauncherPrism/Ember/Phosphor/Abyss`) switched from
  the chosen palette in `MainActivity.syncLauncherAlias`; the theme drives the
  icon, there is no icon picker. **Thinking-merge fix**:
  `StepMapper.groupSteps` folds a pending thinking into the next host regardless
  of the host body, so a just-started (blank-body) tool no longer strands the
  reasoning as expanded prose; a blank pending is dropped rather than emitted as a
  `thinking · 0 chars` pill. **Catalogue loading feedback**: new `LumenLoading`
  primitive + `StateHint.loading`; picker/sheet/settings/composer show progress
  instead of a silent or empty state. **Setup rework**: onboarding on the new
  grammar with a palette chooser, step dots, Back, the real version, and a
  loading/disabled Debian install; the boot screen shows a spinner.
  **Self-maintenance**: one staged pass on every boot (`runStartupMaintenance`)
  that recovers runs, prunes history, compacts the DB, clears caches + the
  `.spindle/truncated` spill + stale `.corrupt-*` quarantine copies, prunes
  sandbox temp, and refreshes the environment, narrating each stage.
  **Update-in-place**: committed non-secret `debug.keystore` + a debug signing
  config so CI APKs share a signature and install over the previous build.
- **0.2.5**: fixed the Settings `default model` row crushing its label on a long
  model id (single-line label + shared-width value) and gave the model picker a
  fixed-width metric column; added a **composer context gauge** — a thin bar at
  the top of the composer card that drains as the context fills, backed by
  `ChatState.contextUsedTokens` (newest turn's prompt size).
- **0.2.6**: fixed a **blank bubble** (a whitespace-only first text delta
  materialized a bodyless `ASSISTANT` row; `DeltaBuffer.append` now buffers a
  blank first chunk without creating a row and `groupSteps` drops contentless
  rows) and fixed the **tail pin** so a last row taller than the viewport is
  scrolled into view instead of being cut at the fold (the pin only handled
  positive gaps before).
- **0.2.7**: a big hardening/perf/QoL wave.
  - **Agent git access (the headline fix)**: the agent had NO way to
    authenticate git — the sandbox bash is deliberately stripped of
    `GITHUB_TOKEN`, so push/private-clone always failed. New `tools/GitTool.kt`
    (`git` tool) runs through the same `ShellExecutor` in the session cwd and
    injects the stored token **only** through the process environment with a
    one-shot `credential.helper` (never argv/URL, redacted from output, push
    never forced). Ops: `status diff log show branch remote add commit clone
    fetch pull push checkout`. Registered in `ChatViewModel` with the KeyStore
    token; denied for the read-only `explore`/`plan` agents. 11 JVM tests.
  - **Hardening**: API key / GitHub token no longer use `rememberSaveable`
    (they were serializable into the saved-instance Bundle); `ModelCatalogue`
    rethrows `CancellationException`; `ExternalDirectoryTool` bounds the dir
    stream before sort (no OOM); `AgentLoop` rethrows cancellation out of the
    pre-run finalize and `NonCancellable`-wraps the crash-path finalize; the
    doom-loop guard now counts signatures in a sliding window (catches
    A/B/A/B, not only consecutive); `parts(session_id)` index (Android
    migration 5, store-sqlite migration 3).
  - **Token savings (lossless)**: grep groups matches by file (path once, then
    indented `line: text`); read / external-read size the line-number gutter to
    the window; bash strips ANSI CSI colour codes from the agent-facing result.
    Opt-in **terse replies** (Settings → agent) appends a brevity rule.
  - **Perf**: `ToolRegistry.specs` precomputed (read every step); subagents
    pass `maintenance=false` so N children no longer trigger N full-DB sweeps;
    `KeyStore` memoizes the decrypted secret (Keystore decrypt ran on the main
    thread); `ModelPickerSheet` builds its map once per (models, provider).
  - **QoL**: Settings → agent gains a collapsible **remembered permissions**
    list (per-item + clear-all revocation — the `always allow` scopes were
    write-only); Settings → appearance gains **reduce motion** (disables the
    running pulse and the 420 ms theme cross-fade); markdown code blocks get a
    **copy** button; the composer gains a **mic** control (system speech
    recognizer, no `RECORD_AUDIO` needed); **regenerate** the last answer and
    **edit & resend** an earlier user message, backed by a new
    `SessionStore.rewindFrom` primitive (drops the target + tail; in-memory,
    sqlite and Android implementations).
  - **Deferred still**: search-result jump-to-message, and the broad move of
    the remaining synchronous `ChatViewModel` filesystem helpers off the main
    thread (many `:app` tests call them synchronously). Full stretchtext
    (cc-stretch) was assessed: it needs the model to author the levels and, per
    its own README, does **not** save tokens — only long-body windowing exists.
- **0.2.8**: project isolation, storage clearing, and the Files rework.
  - **Projects**: new `data/ProjectStore.kt` keeps one folder per project under
    `filesDir/projects/<slug>` (legacy `filesDir/workspace` migrates into
    `projects/playground`); the active project scopes the agent tool cwd, the
    terminal, the file watcher, the git runner, the Files view, the storage row,
    and the truncation spill dir, and a run re-pins its session cwd on every
    send. `ProjectsScreen` creates / switches / deletes (confirmed, recursive,
    last project protected); Home ⋯ → Projects and Settings → system → project.
  - **Storage clearing**: clearable areas are measured as their own rows
    (`app cache`, `logs`, `alpine caches`, `debian caches`, stale app-cache
    entries) instead of the whole rootfs, so a clear visibly moves the number;
    clearing empties directories in place (proot/apt need them to exist),
    returns the real freed bytes, and surfaces failures instead of swallowing
    them.
  - **Files rework**: one 18dp page gutter, project-aware breadcrumb, search,
    item/size columns, uniform 52dp rows with a per-row menu (Edit / Preview
    page / Open externally / Rename / Delete), editor meta line, HTML **View**
    action, and a discard-changes guard. The inline `View` button that made one
    row taller is gone.
  - **Canvas**: local HTML loads with its own folder as the base URL so relative
    CSS/JS/images resolve; remote network loads stay blocked on purpose.
  - **Spacing**: Files header/rows got the missing page inset (4dp → 18dp) and
    the collapsed thinking row no longer reserves dead space above its header.

## 2. What's left (honest open gaps)

- **True multi-session view**: only the visible session streams live and Home
  shows LIVE badges; watching/streaming several sessions in one view is not
  built.
- **Deferred features/reliability**: post-edit auto-format; custom commands /
  `/init`; `json_schema` host opt-in; 401 key re-read (owner ships static keys, so
  no credential refresh); incremental UI patching (parse/enrich already off-main
  and coalesced — not a clear win); real foreign keys/cascade (5-table rebuild +
  orphan purge for near-zero reward; manual cascade already enforces it); Anthropic
  `cache_control` (dropped deliberately — the app routes everything through the
  OpenAI-compatible adapter). `Last-Event-ID` catch-up is in-memory (the bounded
  replay ring), not a persisted DB event log.
- **On-device verification** of the new flows (kill-survival, wifi→hotspot
  retry, notifications, deep link, export/import, git push) is still pending —
  CI/Robolectric only so far. The 0.2.3 UI (new Settings cards, bottom sheets,
  in-chat model picker, redesigned completion notifications) is likewise only
  CI/Robolectric-verified; the screenshot sweep is the visual evidence.
- Non-goals (keep): MCP, LSP, plugins, share links, git worktrees, local models.

## 3. How to work here (saves tokens)

### 3.1 Environment + keys
- **No Android SDK** in this guest: `:app` **cannot** build/test locally. CI
  (Robolectric) verifies it; the screenshot sweep is the only way to *see* the
  UI. JVM modules build locally (§4).
- Java: `bash -lc '. /root/env.sh && …'` sets `JAVA_HOME` + Gradle proxy props.
- **Keys** pre-staged at `/root/live-keys.env` (mode 600, OUTSIDE the repo).
  `source` it; never write a key into a file or log; never commit. Remote
  embeds a PAT, so `git push origin main` works. Scan before every commit:
  `grep -rInE 'oc_sk_|sk-or-v1-|ghp_' --exclude-dir=.git --exclude-dir=build --exclude-dir=.gradle .`
- **Debug signing**: the repo commits a non-secret `debug.keystore` (store/key
  password `android`, alias `androiddebugkey`) wired into the `debug`
  signing config in `app/build.gradle.kts`, so CI APKs keep one signature and a
  new build installs over the previous one. Bump `versionCode`/`versionName` per
  release (`app/build.gradle.kts`); never reuse a version.

### 3.2 Subagent discipline
- `:app` UI is concentrated in `app/src/main/kotlin/dev/lumen/app/ui/`. Files are
  shared heavily, so run **one writer per file per round**; sequence agents that
  need the same file. Use a **frozen interface contract** when two agents must
  interoperate, and give every agent the list of **test tags to preserve**.
- Prefer read-only `explore` for analysis (cannot collide); keep `general`
  writers on disjoint files.

### 3.3 UI seams worth knowing
- Timeline: `ui/LumenChatScreen.kt` (rows, composer, overlays) +
  `ui/model/StepMapper.kt` (`fromMessages`, `mergeAdjacent`, `groupSteps`,
  `linkRuns`, `toolArg`, `toolBody`) + `ui/model/UiStep.kt`.
- Theme/tokens: `ui/LumenTokens.kt` (`LumenShapes/Spacing/Type/Size/Elevation`,
  `LumenSectionHeader`), `LumenColors` in `LumenChatScreen.kt`,
  `MainActivity.animatedColors`. Shared controls: `ui/LumenControls.kt`
  (`LumenFonts`, `LumenSegmented`, `LumenSwitch`, `LumenSlider`) and
  `ui/LumenSheet.kt` (`LumenBottomSheet`). Model UI: `ui/ModelPickerScreen.kt`
  (shared `ModelRow`/`ModelRowData`, favourites + recents) and
  `ui/ModelPickerSheet.kt` (the in-chat popup).
- Runtime/recovery: `ChatViewModel.kt` (`runStartupMaintenance` staged boot pass),
  `RunRecovery.kt`, `RunService.kt`,
  `platform/DebianEnvironment.kt` (`prootArgv`, `guestProcess`, `maintenance`).
- Launcher icon: four adaptive icons `res/mipmap-anydpi-v26/ic_launcher_<palette>.xml`
  over `res/drawable/ic_launcher_background_<palette>.xml` with the shared
  monogram `ic_launcher_foreground.xml`; the manifest declares four
  `activity-alias` launchers and `MainActivity.syncLauncherAlias(palette)` keeps
  exactly one enabled.
- Events/replay: `:core` `event/EventBus.kt` (monotonic `seq` under one lock,
  bounded replay ring via `replayAfter`/`lastSeq`); `:server` SSE `id:`/
  `Last-Event-ID` catch-up.
- Tools/exec: `AgentLoop.executeTool` (single per-tool timeout gate + doom-loop),
  `Tool.timeoutMs` (+ `AgentConfig.maxRepeatedCalls`), `ApprovalRules` wildcard
  matching, `ShellExecutor.runStreaming` (no-deadline `timeoutMs <= 0`); over-cap
  output spills to `<cwd>/.spindle/truncated/<tool>-<call>.txt`.
- Storage: `AndroidDatabase.kt` (`user_version` migrations, quarantine, `lease`
  refcount), `SessionStore.updatePart`, content-addressed `snapshot_blobs`
  (`sha256` PK), `SessionArchive.kt` (redacted export/import).
- Git: `platform/GitRunner.kt` (`activity`, `commitDiff`, guarded commit/push),
  `tools/GitTool.kt` (the agent's authenticated `git` tool), `ui/GitHubScreen.kt`.
- Mentions + links: `:core` `refs/ReferenceResolver.kt` (file refs; rejects
  `://`); render/tap in `ui/MarkdownText.kt` (`FILE_TAG`, `URL_TAG`;
  `onOpenFile` vs `onOpenUrl`); dispatch in `MainActivity.kt`; system actions in
  `platform/WorkspaceActions.kt` (`installApk`, `openExternally`, `openUrl`).
- Watcher: `platform/WorkspaceWatcher.kt` (`snapshot` = files,
  `snapshotDirs` = directories); `ChatViewModel.filesDirAffectedBy`.
- Tests pin behavior under `app/src/test/kotlin/dev/lumen/app/**`
  (`LumenChatScreenTest`, `StepMapper*`, `KeyScreenTest`, `ScreenshotTest`,
  `ModelCatalogueTest`, `RevertTest`, `SurfaceRefreshTest`, `WorkspaceWatcherTest`,
  `GitRunnerTest`, `GitHubScreenTest`, `WorkspaceActionsTest`,
  `DebianEnvironmentTest`, …) plus core `agent/RetryTest`.

## 4. Build / test / CI / artifacts (exact)

Local JVM fast loop (everything except `:app`):

```
bash -lc '. /root/env.sh && cd /data/user/0/ai.opencode.app/files/projects/playground \
  && ./gradlew :core:test :sandbox:test :tools:test :provider-openai:test \
     :provider-anthropic:test :store-sqlite:test :server:test :cli:classes \
     --no-daemon --console=plain'
```

CI poll (must match the sha; `ci.sh` prints a stale run first):

```
SHA=$(git rev-parse --short=7 HEAD)
for i in $(seq 1 50); do out=$(bash /root/ci.sh); line=$(echo "$out" | grep "sha=$SHA" | head -1)
  echo "${line:-$(echo "$out" | head -1)}"
  echo "$line" | grep -qE "conclusion=(success|failure|cancelled)" && { echo "$out"; break; }
  sleep 30; done
```

Fetch screenshots/APK for a **specific run id** via the GitHub API (scripts
`/root/shots.sh` / `/root/apk.sh` default to the latest `main` run and can grab
a stale one). Pattern: list `.../actions/runs/$RUN/artifacts`, pick
`lumen-screenshots` (or `lumen-debug-apk`), then
`curl -L -C - -H "Authorization: Bearer $TOKEN" .../artifacts/$AID/zip`, resume
with `-C -` if the proxy truncates, then `jar xf`.
`TOKEN=$(git remote get-url origin | sed -E 's#https://[^:]+:([^@]+)@.*#\1#')`;
perl `JSON::PP` is at `/usr/bin/perl5.36-aarch64-linux-gnu`.

## 5. Invariants — do not regress

- **Retry keeps streaming** via `AgentEvent.PartReset` (per part, start of a
  retried attempt). Never buffer retries / reintroduce double-render.
- **Per-session serialization:** `AgentLoop.prompt` holds a per-session `Mutex`.
- **Snapshot pinning:** `pruneBounded` keeps the newest snapshot per
  `(session, path)`.
- **Compaction** is ratio-only overflow + no-op guard; never wipes the tail.
- **Composer send/stop target stays a full 40dp** — secondary controls live in
  the weighted scrollable track, never after a `weight(1f)` spacer.
- **Mentions resolve by containment** against the scanned ranges, not a
  zero-width offset annotation query.
- **The sandbox guest cwd is the session workspace** (`--cwd=<session cwd>`),
  never `/root`, so relative paths match the app's tools and the Files view.
- **Runs are never interrupted by the app** — only the user's stop (or a fatal
  provider rejection) ends a run; transient failures go through resilient retry.
- **Revert undo images stay in memory**, never `SnapshotStore.record`ed.
- **External URLs are annotated separately** (`URL_TAG`) from file references.
- **Watcher directory paths are refresh signals only** (`File.isFile` gate).
- `PartReset` must stay mirrored in `:server` `WireEvent` + app `StepMapper`.

## 6. Docs index

- `docs/PLAN.md` — milestones M0–M10 + merge track m11–m15.
- `docs/CAPABILITIES.md` — capability model + non-goals.
- `docs/SPEC.md` — engine contracts (loop, events, SPIs, wire).
- `docs/PROVIDERS.md` — provider configs + auth surfaces.
- `docs/UI-POLISH.md` — polish passes 1–5.
- `docs/PERF-RE-SWEEP.md` — m15 JVM stress re-sweep (PASS, off-device).
- `docs/PARITY.md` — what was ported vs dropped.
- `docs/RELIABILITY-PARITY.md` — reliability parity notes.
