# Lumen — session handoff (READ FIRST)

Native Android agent app on the `spindle` engine. Repo
`turanmertkaraca-bit/spindle`, branch `main`, package `dev.lumen.app`.
Latest code HEAD = `8da240e`, version **0.1.3** (`versionCode 4`; not bumped
this wave). CI (`.github/workflows/ci.yml`) is **green at `8da240e`** on both
jobs (`jvm backend` + `android app (robolectric)`). A docs-only commit sits on
top of it; always re-verify CI by sha with the `ci.sh` poll in §4 before
trusting a build. Current debug APK (0.1.3) is on device at
`/storage/emulated/0/Download/lumen-debug.apk`.

```
██  NEVER COMMIT tokens/secrets. The git remote already embeds the PAT.  ██
██  Never paste the PAT or an API key into chat or a doc. Rotate keys.    ██
██  Live network tests are manual only (live.yml); CI never spends tokens.██
```

## 0. Your task right now

The 0.1.3 "resilience + onboarding" wave is landed and CI-green at `8da240e`
(§1). It closed the biggest owner complaints: chat scroll restoration, revert
undo, no-interrupt networking, first-run setup + launch maintenance, sandbox
hardening, tappable external URLs, and Files directory hot-refresh. No fresh
owner symptoms were pasted into this handoff. Next steps are the **open gaps in
§2** — chiefly multi-session-from-UI and the on-device perf sweep. First confirm
CI by sha, then follow the workflow the owner expects:

1. **Reproduce visually first.** Every screen is rendered to PNGs by CI in
   light+dark. Fetch the `lumen-screenshots` artifact, and actually look at the
   images (§4). Do not guess from code alone.
2. **Analyse** with 2–4 read-only `explore` subagents (one per area) to map the
   exact seams and produce a plan. Review the plans.
3. **Fix** with `general` subagents, **strictly one owner per file** (see §3.2).
   `:app` cannot be built locally — CI/Robolectric is the verifier.
4. Commit, push, poll CI to green, fetch screenshots again, iterate.

### Likely residual issues (verify against screenshots before coding)
- **Onboarding/boot on a real device.** The wizard and boot splash are verified
  only by Robolectric screenshots; check the Debian install progress and the
  battery/storage permission prompts on-device.
- **Network resilience timing.** `Retry.withResilientRetry` waits out a dropped
  link indefinitely; verify on-device with a real wifi→hotspot switch.
- **Dark-theme boundary contrast.** `outline` (dark `0xFF2E2E36`, light
  `0xFFD5D5DC`) and the raised `surface` (`0xFF141418`) were tuned; check panels
  still read as distinct layers, not one flat field. `LumenColors` lives in
  `LumenChatScreen.kt`, not `LumenTokens.kt`.
- **Connected tool runs** (`UiStep.linkedAbove` + `StepMapper.linkRuns` + 1dp
  rail) are still subtle; confirm the grouping reads on-device.
- **Streaming tail / usage-meter overlap** was fixed by a layout-keyed re-pin;
  re-verify on the device with a long streaming reply.

## 1. What's done (recent, in force)

### Last session — `8da240e` wave (0.1.3, unreleased)
- **Chat scroll restoration** (`MainActivity` + `LumenChatScreen`): the screen
  `when` is wrapped in a session-keyed `rememberSaveableStateHolder`, and
  `followTail` is `rememberSaveable`. Leaving to Files/Settings (or backgrounding)
  and returning restores the reader's position instead of snapping to the top;
  the streaming/viewport re-pins and the "don't yank a scrolled-up reader"
  behavior are intact.
- **No-interrupt networking** (`Retry.withResilientRetry`, `AgentLoop`): a
  connectivity failure (wifi drop, hotspot switch, DNS gone) is retried
  indefinitely with capped backoff and a "network unavailable — waiting to
  reconnect" notice; only a fatal provider rejection (auth/context/filter) or
  the user's stop ends a run. New `RetryTest` cases cover classification and the
  patient loop.
- **Revert safety** (`ChatViewModel.revert`/`undoRevert`/`dismissRevert`,
  `ChangesCard` + `UndoNotice`): a confirm dialog, then an 8s undo banner; after
  an undo, reverts are confirm-only for 2 minutes. The undo image is held in
  memory, never `SnapshotStore.record`ed, so it cannot poison the next revert.
- **First-run onboarding + launch maintenance** (`ui/OnboardingScreen.kt`,
  `BootScreen`, `DebianEnvironment.maintenance`): a per-launch boot splash
  prunes proot temp/partials; the wizard walks welcome → sandbox install →
  battery/storage permissions → API key last (the only skippable step). The hard
  API-key wall is relaxed: Files/Terminal/Settings work without a key; only chat
  asks for one (`onChat && state.needsKey -> KeyScreen`).
- **Sandbox hardening** (`DebianEnvironment`): shared Downloads is bound only
  when the runtime storage permission is granted; inherited process env is
  scrubbed of host secrets; the downloaded rootfs SHA-256 is verified before
  extraction.
- **Delegate agent mode + auto-compaction slider** (`AgentConfig.DELEGATE`,
  `ContextBudget.compactAtFraction`): build/plan/delegate chips in the composer
  and quick settings; a percentage slider (50–95) sets auto-compaction as a
  fraction of the model window; plus a "compact now" action.
- **GitHub activity view** (`platform/GitActivity.kt`,
  `GitRunner.activity`/`commitDiff`, `ChatState.gitActivity`): read-only local
  repo branch/remote/dirty/recent commits with tap-to-expand diffs; the token is
  never passed to the read commands.
- **Tappable external URLs** (`MarkdownText` `URL_TAG` + `onOpenUrl` →
  `WorkspaceActions.openUrl` → `ChatViewModel.openExternalUrl`): markdown links
  and bare http(s) URLs open in the browser; inline-code URLs stay inert.
- **Files directory hot-refresh** (`WorkspaceWatcher.snapshotDirs`,
  `ChatViewModel.filesDirAffectedBy`): a newly created (even empty) directory
  refreshes the open folder without re-entering the tab; directory paths are
  refresh signals only and never become change rows.
- **Settings/Quick-settings/Home grounding** (`LumenTokens.LumenSectionHeader`):
  one shared header treatment + captions across both settings surfaces; Home
  section labels; screenshot scenes for onboarding/boot.
- **App-wide fixes**: run notification opens the app and has a Stop action; the
  inert `external-directory` tool is no longer offered; image attach capped at
  8 MiB; `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` + an onboarding action.

### Previous session — `7208586` wave (0.1.3)
- **Composer send target** (`38692fa`): the composer's secondary controls
  (attach, build/plan, model chip, key/gear/theme) now live in a weighted,
  horizontally scrollable track, so the send/stop button is measured first and
  keeps its full 40dp target on narrow screens (it was squeezed to a sliver once
  the model chip rendered). The duplicate `attach-vision` control (which aliased
  `attach-image`) was removed.
- **Mention taps** (`38692fa` + `7208586`): a tapped file reference in
  `MarkdownText.kt` is resolved by **containment against the already-scanned
  reference ranges**; an offset annotation query could miss the tapped glyph (a
  zero-width range silently matched nothing on device).
- **Live model catalogue** (`4a2f248`): `data/ModelCatalogue.kt` fetches the
  provider's live `/models` on app start and on provider switch/refresh, merges
  it over the embedded `ProviderCatalogue` snapshot, and caches it in
  SharedPreferences (`lumen.models`) so it survives a restart; offline/corrupt
  cache falls back to the embedded floor. `ChatState` gained
  `models/modelsRefreshing/modelsError`; new full-screen route `"models"`
  (`ui/ModelPickerScreen.kt`) opened from the composer model chip and Settings
  ("Browse all models"). The composer chip calls `onOpenModels` when wired, else
  keeps the old quick-settings behavior.
- **Actionable mentions** (`7e31db6`): a tapped mention dispatches by extension
  in `MainActivity.kt` via `platform/WorkspaceActions.kt` — `.apk` → system
  installer (via FileProvider; routes to the unknown-sources setting when
  needed), `.html/.htm` → Canvas, images → external viewer, otherwise → Files
  viewer. Adds `REQUEST_INSTALL_PACKAGES` and a `${applicationId}.fileprovider`
  FileProvider scoped to `files-path workspace/` (`res/xml/file_paths.xml`).
- **Sandbox guest cwd** (`7208586`): `DebianEnvironment.prootArgv` now uses
  `--cwd=<session cwd>` (falling back to `/root` only when none is supplied), so
  a relative path in an agent `bash` command hits the same file the app's tools
  and the Files view see. Previously `--cwd=/root` sent relative writes into the
  rootfs, so agent-created files never appeared in Files and mention paths were
  not links. `DebianEnvironmentTest` asserts `--cwd=$cwd`.
- **Non-chat UI rework** (`baac664`): shared `LumenType`/`LumenSize`/
  `LumenSpacing.page` tokens; larger `StateHint`; reworked Home/Files/Key/
  Settings/GitHub/Storage/Diagnostics/Terminal/Canvas; gradient New-chat button;
  card-style session rows; tighter quick-settings model list. All pre-existing
  test tags preserved.

### Earlier, still in force
- **0.1.3 wave** `749df0d` + test fix `e4c1464`: opaque `outline` boundary role +
  `row` shape; connected tool runs; tool cards/chips never show raw JSON;
  streaming tail re-pinned on viewport shrink; API-key screen redesign; in-chat
  quick settings sheet. (See §2 of `docs/UI-POLISH.md`.)
- **0.1.2 wave** `3791ad2`/`cea4936`/`47d1ec4`(+`977d856`/`098a921`/`f6b92a8`):
  chat composer redesign (single card + bottom control row + compact usage +
  "↓ latest"); mentions open the **Files viewer at the line** (dirs too, via
  `:core` `ReferenceResolver.resolveKinds`); Files live-refresh on agent writes +
  edited-line highlight (suppressed for newly created files); richer Home
  (status strip + quick controls); key screen (Test key, non-destructive edit);
  GitHub PAT connect/status/clone; websearch/webfetch fixed + live-verified.
- Zen/Go per-model routing (`1845a77`/`a5f36b9`): `/messages` fixture-tested,
  `/responses` intentionally rejected. **Live Claude/GPT is closed by owner
  decision** (Go subscription only, no API credits) — do not reopen.
- `skill` + `external-directory` tools (`15635e8`); opt-in `json_schema`
  (`bf86874`, engine-only); UI polish passes; `m15` JVM stress PASS
  (`docs/PERF-RE-SWEEP.md`); `SessionRetentionTest` flake hardened (`6e62605`).

## 2. What's left (open work / known gaps, honest)

- **Multi-session from the UI** — the engine allows it (per-session mutex) and a
  run keeps going when you leave its chat, but the UI drives one chat at a time
  and only the *current* session receives live streaming deltas; returning to a
  running session rebuilds from the store rather than resuming the live stream.
  Watching/streaming two sessions at once is unbuilt.
- **On-device verification of the new flows** — the boot splash, onboarding
  wizard, battery/storage permission prompts, the resilient-retry behavior on a
  real wifi→hotspot switch, and the revert undo banner are only CI/Robolectric
  verified so far.
- **APK install requires "install unknown apps"** granted at runtime; the first
  tap routes to `ACTION_MANAGE_UNKNOWN_APP_SOURCES` and the user must tap again
  (`WorkspaceActions.installApk`).
- **`m15` on-device perf/ANR sweep** — JVM stress is PASS; on-device
  (ART/ANR/thermal, long-session heap/fd/DB/WAL) is **pending/unverified**.
- **Host opt-in for `json_schema`** — engine works, but no host
  (`:cli`/`:app`/`:server`) sets `responseFormat`, so it is not reachable from the
  UI yet. (`external-directory` is now simply not offered by the app, since no
  roots are ever granted.)
- **Git commit/push automation is NOT built** (GitHub = PAT connect + status +
  clone/open + the new read-only local activity view; no OAuth).
- Non-goals: OAuth, local models, MCP, LSP, plugins/marketplace.

## 3. How to work here (saves tokens)

### 3.1 Environment
- **No Android SDK** in this guest: `:app` **cannot** build/test locally. CI
  (Robolectric) verifies it, and the screenshot sweep is the only way to *see*
  the UI. JVM modules build locally (see §4).
- Java: `bash -lc '. /root/env.sh && …'` sets `JAVA_HOME` + Gradle proxy props.
- **Keys** are pre-staged at `/root/live-keys.env` (mode 600, OUTSIDE the repo):
  `OPENROUTER_API_KEY` (paid, works) and `OPENCODE_API_KEY` (free-tier; only free
  models on Go `/chat/completions`, e.g. `space-bunny-free`). `source` it; never
  write a key into a file or log; never commit.
- Remote embeds a PAT, so `git push origin main` works. Keys must never be staged
  (they aren't, by location). Scan before every commit:
  `grep -rInE 'oc_sk_|sk-or-v1-|ghp_' --exclude-dir=.git --exclude-dir=build --exclude-dir=.gradle .`

### 3.2 Subagent discipline
- `:app` UI is concentrated in `app/src/main/kotlin/dev/lumen/app/ui/`. Files are
  shared heavily, so run **one writer per file per round**; sequence agents that
  need the same file. Use a **frozen interface contract** in the prompts when two
  agents must interoperate (exact param names/types), and give every agent the
  list of **test tags to preserve**.
- `LumenColors` lives in `LumenChatScreen.kt` (not `LumenTokens.kt`); if you add a
  color role, patch the data class + `Light`/`Dark` companions + the
  `animatedColors` block in `MainActivity.kt`.
- Private screenshot/analysis agents cannot collide if you use read-only
  `explore`; keep fix agents to disjoint files.

### 3.3 UI seams worth knowing (from prior analysis)
- Timeline: `ui/LumenChatScreen.kt` (rows, composer, overlays) +
  `ui/model/StepMapper.kt` (`fromMessages`, `mergeAdjacent`, `groupSteps`,
  `linkRuns`, `toolArg`, `toolBody`) + `ui/model/UiStep.kt`.
- Theme/tokens: `ui/LumenTokens.kt` (`LumenShapes/Spacing/Type/Size/Elevation`,
  `LumenAlert`), `LumenColors` in `LumenChatScreen.kt`,
  `MainActivity.animatedColors`.
- Key screen: `ui/KeyScreen.kt`. Quick settings sheet: `LumenChatScreen.kt` +
  `MainActivity.kt`.
- Model catalogue/picker: `data/ModelCatalogue.kt` (live fetch + merge + cache),
  `data/ProviderCatalogue.kt` (embedded snapshot), `ui/ModelPickerScreen.kt`
  (route `"models"`), wired in `ChatViewModel` (`ChatState.models`,
  `refreshModels`) and `LumenApp` (`applicationScope.refreshAll()`).
- Mentions + links: `:core` `refs/ReferenceResolver.kt` (file refs; still
  rejects `://`); app render/tap in `ui/MarkdownText.kt` (`FILE_TAG` for file
  refs, `URL_TAG` for external links; `onOpenFile` vs `onOpenUrl`); dispatch in
  `MainActivity.kt`; system actions in `platform/WorkspaceActions.kt`
  (`installApk`, `openExternally`, `openUrl`).
- Files/editor/changes: `ui/FilesScreen.kt`, `platform/WorkspaceWatcher.kt`
  (`snapshot` = files, `snapshotDirs` = directories),
  `ChatViewModel.kt` (`fileKind`, `openFileInFiles`, `editFile`,
  `filesDirAffectedBy` (folder + descendants), `scheduleSurfaceRefresh`).
- Resilience: `:core` `agent/Retry.kt` (`isRetryable`, `isNetwork`,
  `withResilientRetry`), used in `AgentLoop` around `provider.stream`.
- Onboarding/maintenance: `ui/OnboardingScreen.kt` (`OnboardingScreen`,
  `BootScreen`), `ChatViewModel.runStartupMaintenance`/`completeOnboarding`,
  `DebianEnvironment.maintenance`, `KeyStore.onboarded`.
- GitHub activity: `platform/GitActivity.kt`, `GitRunner.activity`/`commitDiff`,
  `ui/GitHubScreen.kt` `ActivitySection`, `ChatViewModel.refreshGitActivity`.
- Sandbox guest cwd: `platform/DebianEnvironment.kt` (`prootArgv`, `guestProcess`)
  — the guest starts in the bound session cwd.
- Tests that pin behavior live under `app/src/test/kotlin/dev/lumen/app/**`
  (`LumenChatScreenTest`, `StepMapper*`, `KeyScreenTest`, `ScreenshotTest`,
  `ModelCatalogueTest`, `ModelPickerScreenTest`, `MentionActionTest`,
  `MarkdownMentionTest`, `RevertTest`, `SurfaceRefreshTest`,
  `WorkspaceWatcherTest`, `GitRunnerTest`, `GitHubScreenTest`,
  `GitActivityViewModelTest`, `WorkspaceActionsTest`, `DebianEnvironmentTest`,
  …) plus core `agent/RetryTest` for the resilient retry.

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
`/root/shots.sh` / `/root/apk.sh` default to the latest `main` run and can grab a
stale one). Pattern used this session: list
`.../actions/runs/$RUN/artifacts`, pick `lumen-screenshots`, `curl -L -H
"Authorization: Bearer $TOKEN" .../artifacts/$AID/zip`, resume with `-C -` if the
proxy truncates, then `jar xf`. `TOKEN=$(git remote get-url origin | sed -E
's#https://[^:]+:([^@]+)@.*#\1#')`; perl `JSON::PP` is at
`/usr/bin/perl5.36-aarch64-linux-gnu`.

Live provider smoke (manual, spends tokens):

```
bash -lc '. /root/env.sh && . /root/live-keys.env && \
  ./gradlew :cli:run --no-daemon --console=plain --args="--provider openrouter \
  --model openrouter/auto --yes --max-steps 6 --prompt \"...\""'
```

Stress: `OPENROUTER_API_KEY=… ./gradlew :cli:stress --args="--turns 25 --max-steps 4"`.

## 5. Invariants — do not regress

- **Retry keeps streaming** via `AgentEvent.PartReset` (per part, start of a
  retried attempt). Never buffer retries / reintroduce double-render.
- **Per-session serialization:** `AgentLoop.prompt` holds a per-session `Mutex`.
- **Snapshot pinning:** `pruneBounded` keeps the newest snapshot per
  `(session, path)`.
- **Compaction** is ratio-only overflow + no-op guard; never wipes the tail.
- **Streaming rendering is bounded** (tail window while streaming, prefix when
  finished); no `animateContentSize` on growing content.
- **The composer send/stop target stays a full 40dp** — secondary controls live
  in the weighted scrollable track, never after a `weight(1f)` spacer.
- **Mentions resolve by containment** against the scanned ranges, not a
  zero-width offset annotation query.
- **The sandbox guest cwd is the session workspace** (`--cwd=<session cwd>`),
  never `/root`, so relative paths match the app's tools and the Files view.
- **`SessionRetentionTest` teardown** must cancel+join test scopes before
  `resetMain()`, then drain the Robolectric main looper (`6e62605`).
- **`outline` vs `rule`:** `outline` is the opaque boundary; `rule` is a
  translucent wash/fill. Don't use `rule` for borders.
- **Runs are never interrupted by the app** — only the user's stop (or a fatal
  provider rejection) ends a run. Transient/connectivity failures go through
  `Retry.withResilientRetry`; never downgrade this to a bounded retry that gives
  up on a dropped link.
- **Revert undo images stay in memory** (`RevertUndo`), never
  `SnapshotStore.record`ed, or they become the newest `(session, path)` image and
  poison the next revert.
- **External URLs are annotated separately** (`URL_TAG`) from file references, so
  `ReferenceResolver` keeps rejecting `://` and file refs stay cwd-gated.
- **Watcher directory paths are refresh signals only** — `absorbIndirect` must
  filter them out of change rows (`File.isFile` gate).
- `PartReset` must stay mirrored in `:server` `WireEvent` + app `StepMapper`.

## 6. Docs index

- `docs/PLAN.md` — milestones M0–M10 + merge track m11–m15.
- `docs/CAPABILITIES.md` — capability model + non-goals (GitHub connect in scope,
  git commit/push deferred).
- `docs/SPEC.md` — engine contracts (loop, events, SPIs, wire).
- `docs/PROVIDERS.md` — provider configs + Zen/Go auth surfaces.
- `docs/UI-POLISH.md` — polish passes 1–5 (all landed incl. non-chat rework and
  the model picker).
- `docs/PERF-RE-SWEEP.md` — m15 JVM stress re-sweep (PASS, off-device).
- `docs/PARITY.md` — what was ported vs dropped.
