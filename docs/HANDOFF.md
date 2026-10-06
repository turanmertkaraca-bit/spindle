# Lumen — session handoff (READ FIRST)

Native Android agent app on the `spindle` engine. Repo
`turanmertkaraca-bit/spindle`, branch `main`, package `dev.lumen.app`.
**HEAD = `14d0ea5`**, version **0.1.3** (`versionCode 4`). CI
(`.github/workflows/ci.yml`) is **green** at `14d0ea5` on both jobs
(`jvm backend` + `android app (robolectric)`). Current debug APK (0.1.3) is on
device at `/storage/emulated/0/Download/lumen-debug.apk`.

```
██  NEVER COMMIT tokens/secrets. The git remote already embeds the PAT.  ██
██  Never paste the PAT or an API key into chat or a doc. Rotate keys.    ██
██  Live network tests are manual only (live.yml); CI never spends tokens.██
```

## 0. Your task right now

The owner likes the UI but says **"there are still some problems"** (the exact
list was not pasted into this handoff). Treat this as a UI correctness/readability
pass. Recommended loop (this is the workflow the owner expects):

1. **Reproduce visually first.** Every screen is rendered to PNGs by CI in
   light+dark. Push (or re-run) CI, fetch the `lumen-screenshots` artifact, and
   actually look at the images (see §4). Do not guess from code alone.
2. **Analyse** with 2–4 read-only `explore` subagents (one per area) to map the
   exact seams and produce a plan. Review the plans.
3. **Fix** with `general` subagents, **strictly one owner per file** (see §3.2).
   `:app` cannot be built locally — CI/Robolectric is the verifier.
4. Commit, push, poll CI to green, fetch screenshots again, iterate.

Ask the owner for the exact symptoms if the screenshots don't make them obvious —
one short question is cheaper than a wrong 3-agent wave.

### Likely residual UI issues (from the last review; verify against screenshots)
- **Connected tool runs still read as separate cards.** `749df0d` added
  `UiStep.linkedAbove` + `StepMapper.linkRuns` + a 1dp rail with 3dp spacing, but
  the rail may be too subtle. Consider a stronger visual group (shared container /
  spine / joined corners) for consecutive tool-ish rows only.
- **Thinking → tool → answer linkage.** `linkRuns` links tool-ish rows
  (`TOOL`/`SUBAGENT`/`THINKING`); `ASSISTANT`/`YOU` break the run. Confirm the
  owner wants thinking joined to the following tool card too, and that an answer
  bubble starting a new unit looks right.
- **Dark-theme boundary contrast.** New `outline` (dark `0xFF2E2E36`, light
  `0xFFD5D5DC`) may be too faint/heavy in places; `surface` was raised to
  `0xFF141418`. Tune `LumenColors` (defined in `LumenChatScreen.kt`, not
  `LumenTokens.kt`).
- **API-key screen** was redesigned twice; owner has complained twice. Inspect
  `KeyScreen.kt` and its screenshot (`key_light`/`key_dark`).
- **Streaming tail / usage meter overlap** was fixed by a layout-keyed re-pin;
  re-verify on the device with a long streaming reply.
- **In-chat quick settings sheet** (`749df0d`, screenshots
  `light_quick_settings`/`dark_quick_settings`) — verify it opens from the model
  chip, the gear (`chat-settings`) and the top-bar ⚙, and that back/scrim close it.

## 1. What's done (recent, in force)

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

## 2. What's left (non-UI backlog, honest)

- **`m15` on-device perf/ANR sweep** — JVM stress is PASS; on-device
  (ART/ANR/thermal, long-session heap/fd/DB/WAL) is **pending/unverified**.
- **Host opt-in for `json_schema`** and **`external-directory`** — engine works,
  but no host (`:cli`/`:app`/`:server`) sets `responseFormat` or granted roots,
  so neither is reachable from the UI yet.
- **Multi-session from the UI** — engine allows it; UI drives one chat at a time.
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
- Theme: `ui/LumenTokens.kt` (`LumenShapes/Spacing/Elevation`, `LumenAlert`),
  `LumenColors` in `LumenChatScreen.kt`, `MainActivity.animatedColors`.
- Key screen: `ui/KeyScreen.kt`. Quick settings sheet: `LumenChatScreen.kt` +
  `MainActivity.kt`.
- Mentions: `:core` `refs/ReferenceResolver.kt`; app render in `ui/MarkdownText.kt`.
- Files/editor/changes: `ui/FilesScreen.kt`, `platform/WorkspaceWatcher.kt`,
  `ChatViewModel.kt` (`fileKind`, `openFileInFiles`, `editFile`, `scheduleSurfaceRefresh`).
- Tests that pin behavior live under `app/src/test/kotlin/dev/lumen/app/**`
  (`LumenChatScreenTest`, `StepMapper*`, `KeyScreenTest`, `ScreenshotTest`, …).

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
- `docs/UI-POLISH.md` — polish passes 1–4 (all landed incl. quick settings).
- `docs/PERF-RE-SWEEP.md` — m15 JVM stress re-sweep (PASS, off-device).
- `docs/PARITY.md` — what was ported vs dropped.
