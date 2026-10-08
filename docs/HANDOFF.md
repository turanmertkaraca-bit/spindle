# Lumen — session handoff (READ FIRST)

Native Android agent app on the `spindle` engine. Repo
`turanmertkaraca-bit/spindle`, branch `main`, package `dev.lumen.app`.
Latest code HEAD = `7c3dfd1`, version **0.2.1** (`versionCode 6`). CI
(`.github/workflows/ci.yml`) is **green at `7c3dfd1`** on both jobs
(`jvm backend` + `android app (robolectric)`). Releases **v0.2.0** and
**v0.2.1** are published, each with a `lumen-<ver>-debug.apk` asset. A fresh
debug APK is staged on device at
`/storage/emulated/0/Download/lumen-debug.apk`. Always re-verify CI by sha with
the `ci.sh` poll in §4 before trusting a build.

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
- **Streaming**: partial streamed output is checkpointed (~1s) so a killed run
  keeps the answer.
- **Runtime**: the device shell is cancellation-aware and kills the process
  group; per-file edit locks; bounded/cancellable glob; malformed tool args
  return a rewrite-requesting error; proot tears down SIGTERM-first.
- **Storage**: versioned transactional migrations (`user_version`); retention
  janitor (startup + every 6h); FTS partial-index repair; corrupt-DB
  quarantine+recreate; per-part upsert (`SessionStore.updatePart`);
  `RunRecovery.reconcile` + targeted `updateSessionState`.
- **UI**: palettes **prism / ember / phosphor / abyss** (default now **abyss**);
  adaptive "terminal window" launcher icon + cold-start `windowBackground`; Home
  **green LIVE badges** for running sessions; per-session completion/error
  notifications with a custom `res/raw/error_dong.wav` error chime, no body
  text, deep link to the session, Settings toggle; a11y content descriptions +
  minimum touch targets; composer is a wrapping multi-line field; the
  **delegate** mode chip was removed (UI only; the agent config still exists).
- **Features**: session export/import as redacted JSON (Home actions); guarded
  git commit/push from the GitHub screen (token via env only, never force).

## 2. What's left (honest open gaps)

- **True multi-session view**: only the visible session streams live and Home
  shows LIVE badges; watching/streaming several sessions in one view is not
  built.
- **Deferred reliability/features**: post-edit auto-format; custom commands /
  `/init`; `json_schema` host opt-in; 401 key re-read; W5 runtime remainder
  (per-tool timeout, bash output spill-to-file, wildcard permission rules /
  `external_directory` / doom-loop, watcher truncation notices); W6 storage
  remainder (connection refcount/close race, sha256 content-addressed snapshot
  dedup); durable server event log with `Last-Event-ID` catch-up (low value for
  the single-client app).
- **On-device verification** of the new flows (kill-survival, wifi→hotspot
  retry, notifications, deep link, export/import, git push) is still pending —
  CI/Robolectric only so far.
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
  `MainActivity.animatedColors`.
- Runtime/recovery: `ChatViewModel.kt`, `RunRecovery.kt`, `RunService.kt`,
  `platform/DebianEnvironment.kt` (`prootArgv`, `guestProcess`, `maintenance`).
- Storage: `AndroidDatabase.kt` (`user_version` migrations, quarantine),
  `SessionStore.updatePart`, `SessionArchive.kt` (redacted export/import).
- Git: `platform/GitRunner.kt` (`activity`, `commitDiff`, guarded commit/push),
  `ui/GitHubScreen.kt`.
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
