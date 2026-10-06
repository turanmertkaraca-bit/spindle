# Lumen — session handoff (READ FIRST)

Native Android agent app on the `spindle` engine. Repo
`turanmertkaraca-bit/spindle`, branch `main`, package `dev.lumen.app`.
Latest code HEAD = `7208586`, version **0.1.3** (`versionCode 4`). CI
(`.github/workflows/ci.yml`) is **green at `7208586`** on both jobs (`jvm
backend` + `android app (robolectric)`). A docs-only commit may sit on top of
it; always re-verify CI by sha with the `ci.sh` poll in §4 before trusting a
build. Current debug APK (0.1.3) is on device at
`/storage/emulated/0/Download/lumen-debug.apk`.

```
██  NEVER COMMIT tokens/secrets. The git remote already embeds the PAT.  ██
██  Never paste the PAT or an API key into chat or a doc. Rotate keys.    ██
██  Live network tests are manual only (live.yml); CI never spends tokens.██
```

## 0. Your task right now

This session finished the 0.1.3 native wave: composer send target, mention
taps, the live model catalogue + full-screen picker, actionable file mentions,
the sandbox guest cwd fix, and a non-chat UI rework (§1). No fresh owner
symptoms were pasted into this handoff. Next steps are the **open gaps in §2** —
chiefly external-URL mentions are still not tappable, Files does not hot-refresh
new scripts-created directories, and multi-session-from-UI is unbuilt. First
confirm CI by sha, then follow the workflow the owner expects:

1. **Reproduce visually first.** Every screen is rendered to PNGs by CI in
   light+dark. Fetch the `lumen-screenshots` artifact, and actually look at the
   images (§4). Do not guess from code alone.
2. **Analyse** with 2–4 read-only `explore` subagents (one per area) to map the
   exact seams and produce a plan. Review the plans.
3. **Fix** with `general` subagents, **strictly one owner per file** (see §3.2).
   `:app` cannot be built locally — CI/Robolectric is the verifier.
4. Commit, push, poll CI to green, fetch screenshots again, iterate.

### Likely residual issues (verify against screenshots before coding)
- **Dark-theme boundary contrast.** `outline` (dark `0xFF2E2E36`, light
  `0xFFD5D5DC`) and the raised `surface` (`0xFF141418`) were tuned; check panels
  still read as distinct layers, not one flat field. `LumenColors` lives in
  `LumenChatScreen.kt`, not `LumenTokens.kt`.
- **Connected tool runs** (`UiStep.linkedAbove` + `StepMapper.linkRuns` + 1dp
  rail) are still subtle; confirm the grouping reads on-device.
- **Streaming tail / usage-meter overlap** was fixed by a layout-keyed re-pin;
  re-verify on the device with a long streaming reply.

## 1. What's done (recent, in force)

### Last session — `7208586` wave (0.1.3)
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

- **External-URL mentions are still not tappable.** The `:core` resolver ignores
  any token containing `://` (`ReferenceResolver.kt:151` rejects `//` and
  `://`), so `https://…` in assistant text never becomes a link.
- **Files does not hot-refresh new *directories* created by scripts while the
  tab is open.** Entries refresh on direct/indirect writes, but the parent list
  only re-lists when the tab is re-entered. New files show; new directories need
  a re-list.
- **Multi-session from the UI** — engine allows it (per-session mutex); UI still
  drives one chat at a time.
- **APK install requires "install unknown apps"** granted at runtime; the first
  tap routes to `ACTION_MANAGE_UNKNOWN_APP_SOURCES` and the user must tap again
  (`WorkspaceActions.installApk`).
- **`m15` on-device perf/ANR sweep** — JVM stress is PASS; on-device
  (ART/ANR/thermal, long-session heap/fd/DB/WAL) is **pending/unverified**.
- **Host opt-in for `json_schema`** and **`external-directory`** — engine works,
  but no host (`:cli`/`:app`/`:server`) sets `responseFormat` or granted roots,
  so neither is reachable from the UI yet.
- **Git commit/push automation is NOT built** (GitHub = PAT connect + status +
  clone/open only; no OAuth).
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
- Mentions: `:core` `refs/ReferenceResolver.kt`; app render/tap in
  `ui/MarkdownText.kt`; dispatch in `MainActivity.kt`; system actions in
  `platform/WorkspaceActions.kt`.
- Files/editor/changes: `ui/FilesScreen.kt`, `platform/WorkspaceWatcher.kt`,
  `ChatViewModel.kt` (`fileKind`, `openFileInFiles`, `editFile`, `scheduleSurfaceRefresh`).
- Sandbox guest cwd: `platform/DebianEnvironment.kt` (`prootArgv`, `guestProcess`)
  — the guest starts in the bound session cwd.
- Tests that pin behavior live under `app/src/test/kotlin/dev/lumen/app/**`
  (`LumenChatScreenTest`, `StepMapper*`, `KeyScreenTest`, `ScreenshotTest`,
  `ModelCatalogueTest`, `ModelPickerScreenTest`, `MentionActionTest`,
  `WorkspaceActionsTest`, `DebianEnvironmentTest`, …).

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
