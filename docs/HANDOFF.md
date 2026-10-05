# Lumen — session handoff (read me first)

Native Android agent app. `spindle` repo, branch `main`, package `dev.lumen.app`.
`HEAD = 47fd30b` (`refactor(app): lighter, calmer transcript and controls`). The
docs-sync + version-bump commit rides **on top of** `47fd30b` (nothing else is
committed after it as of this writing). CI (`.github/workflows/ci.yml`) runs both
a `jvm backend` job and an `android app (robolectric)` job; CI status for
`47fd30b` + the docs-sync commit is **not** asserted here — re-check the run.
`:cli` is gated in the jvm job (`:cli:classes`), so its run path compiles in CI
even though it never spends tokens. Working tree after this session: the
docs-sync edits + `app/build.gradle.kts` version bump only (left uncommitted, per
instructions).

**Version: `versionCode = 2`, `versionName = "0.1.1"`** — release discipline:
never reuse a version number, every release updates in place. Bump `versionCode`
(and `versionName`) in `app/build.gradle.kts` for every release.

```
██  NEVER COMMIT tokens/secrets. The git remote already embeds the PAT.  ██
██  Never paste the PAT or an API key into chat or into a doc. Rotate keys  ██
██  when dev is finished. Live tests are manual only (`live.yml`).          ██
```

## 1. What's done (this session)

1. **Zen/Go per-model wire routing (`1845a77`, refined `a5f36b9`).** Pure routing
   table (`provider-openai/OpenCodeRoutes.kt`) + a `:cli` composite
   (`OpenCodeRoutingProvider`): Claude-family/Qwen → `/messages` via the existing
   Anthropic adapter (`x-api-key` + `anthropic-version`; Go also threads
   `x-opencode-session` + a custom `User-Agent`). GPT/Grok `/responses` targets
   return an explicit terminal `Failure` — `/responses` is **deliberately not
   implemented** rather than silently mis-routed. Committed fixtures
   (`opencode_go_claude.sse`) + routing/header assertions. `/messages` is
   **fixture-tested**; live Claude/GPT is **out of scope by owner decision** (see
   §2 — no paid API access).
2. **`skill` + `external-directory` tools (`15635e8`), in `:tools`.** `skill` is
   a closed local `SKILL.md` discovery/loader clamped under the session cwd;
   `external-directory` is read-only over an explicit user-granted allow-list
   (canonicalized + containment-checked) and denies everything when the list is
   empty. Both registered in `DefaultTools` with source-compatible defaulted
   params (`skillsRoot`, `externalRoots`).
3. **Opt-in structured output (`bf86874`).** `ResponseFormat(name, schemaJson,
   strict)` + defaulted `ChatRequest.responseFormat` and
   `AgentConfig.responseFormat`, threaded `AgentConfig → Wire → request`. The
   OpenAI-compatible adapter emits `response_format:json_schema` only when set
   (malformed schema JSON omits the key); Anthropic ignores it. **No host opts
   in**, so it is unreachable from `:cli`/`:app`/`:server` today.
4. **UI polish — three passes.**
   - `ea2e38e` + `189b695`: shared `LumenTokens` (`LumenShapes`/`LumenElevation`),
     `LumenMotion`, `LumenState`; calm empty/loading/error states across Home,
     Files, Terminal, Canvas, Settings, Diagnostics, Storage; unified motion
     without `animateContentSize`; Robolectric screenshot sweep extended to every
     screen in light **and** dark (CI artifact `lumen-screenshots`). `189b695`
     was the follow-up Canvas import compile fix.
   - `fa9ff1a` + `dab4f39` + `1422eb1`: chat timeline (one-line tool cards with
     plain-language failures, subtle thinking line, agent-name label instead of
     hash, prose off monospace) and shared screen chrome (`LumenTopBar`/ops
     overflow, 44dp targets, centered empty states, destructive wording);
     `1422eb1` restored the FilesScreen new-file/folder actions.
   - `47fd30b`: lighter/calmer pass — quiet surface fills instead of borders,
     slimmer status spine, fainter pills/tags, low-key composer secondary
     controls with send as the sole primary, press feedback.
   - Light+dark sweep is clean: **~85 PNGs (59 screens + 26 animation frames),
     0 render errors** (`docs/UI-POLISH.md`).
5. **`m15` long-session stress re-sweep PASS (`a4807fd`).**
   `docs/PERF-RE-SWEEP.md`: 25 live OpenRouter turns, retained heap flat ~6 MB
   (0.00 MB/turn), fds 38→39, DB+WAL ≤192 KB, 0 errors. This is the **JVM**
   harness; the on-device re-sweep is still pending.
6. **Flaky-test hardening (`6e62605`).** `SessionRetentionTest` teardown now
   tracks the test-owned run scopes, cancels + joins them before `resetMain()`,
   then drains the Robolectric main looper — removing the intermittent
   `Dispatchers.Main is used concurrently with setting it` failure without
   weakening any assertion. Fix is committed; the other sampler test was not
   known to flake.
7. **Version bump (this commit).** `app/build.gradle.kts` → `versionCode 2`,
   `versionName "0.1.1"` (only version lines changed).
8. **Prior-session wins still in force:** background-run fix (`87ebe96`),
   core-engine correctness, provider streaming hardening, long-session
   stability, retry `PartReset` (`e1d773f`), per-session prompt serialization
   (`06b113c`), and the M9 live smoke — OpenRouter (paid) real text/reasoning/
   usage + multi-step tool loop, and OpenCode Go (free key, `space-bunny-free`)
   real write→read loop. Committed fixtures under
   `provider-openai/src/test/resources/openrouter_recorded_{text,tool}.sse`.

## 2. What's left (honest)

- **Zen/Go live Claude/GPT parity — deliberately closed / deprioritized, NOT
  blocked pending a paid key.** The owner only has an OpenCode Go subscription
  and will **not** add API credits. `/messages` routing is implemented and
  fixture-tested; the free tier 403s Claude/GPT on every surface. This is a
  **product/owner decision to not pursue paid live Claude/GPT**, so future
  sessions should **not** chase a paid key or re-open it as a dangling task.
  `/responses` likewise stays intentionally dropped.
- **Host opt-in for structured output** — the engine supports
  `ResponseFormat`/`responseFormat`, but no host (`:cli`, `:app`, `:server`)
  constructs one, so `json_schema` output is unreachable from any entry point.
- **Host opt-in for `external-directory`** — the tool denies everything until a
  host supplies granted roots via `DefaultTools.registry(externalRoots = ...)`;
  no host does yet. `skill` defaults to `.opencode/skills` under the session cwd.
  Neither is reachable from a host today.
- **`m15` on-device perf/ANR re-sweep** — the JVM stress re-sweep is PASS
  (`a4807fd`); the **on-device** sweep (ART/ANR/frame timing, battery/thermal,
  long-session heap/fd/DB/WAL) is still pending and **unverified**. Do not claim
  on-device perf.
- **Multi-session concurrency from the UI** — the engine allows it (per-session
  locks); the UI does not drive N chats at once yet.
- Explicit non-goals: OAuth, local models, MCP, LSP, plugins, marketplace
  skills, git-commit UI.

## 3. Keys / credentials — the traps

- **NEVER commit or paste a key.** Rotate when dev is finished. Live tests are
  manual (`live.yml`, `workflow_dispatch`, secret-gated), never on push/PR.
- **OpenRouter (paid) key works.** Fine to use for the live smoke + stress.
- **OpenCode free-tier key works for FREE models on `/chat/completions` only.**
  `space-bunny-free` is the known-good free model id. It does **not** unlock
  Claude/GPT.
- **`/models` on opencode.ai is PUBLIC (no auth).** A `/models` 200 is **not**
  proof a key is valid — always verify with a real inference call.
- **Auth surfaces differ:** Zen/Go `/chat/completions` and `/responses` use
  `Authorization: Bearer`; Zen/Go `/messages` (Anthropic surface) uses
  `x-api-key` + `anthropic-version` — a Bearer there returns 401 "Missing API
  key". Go additionally requires `x-opencode-session` and a custom User-Agent.
- Free tier returns **403 "Model access is disabled"** for Claude/GPT on
  `/messages`, `/responses` and `/chat/completions`. Routing is implemented +
  fixture-tested (`1845a77`); **live** Claude/GPT is **not pursued** (no paid
  OpenCode access — owner decision, see §2). DeepSeek and OpenRouter live paths
  are unaffected.

## 4. Build / test / stress (exact)

Local JVM fast loop (`:app` is CI-only; no Android SDK in the guest):

```
bash -lc '. /root/env.sh && cd /data/user/0/ai.opencode.app/files/projects/playground \
  && ./gradlew :core:test :sandbox:test :tools:test :provider-openai:test \
     :provider-anthropic:test :store-sqlite:test :server:test :cli:classes \
     --no-daemon --console=plain'
```

Long-session stress harness (manual, env-key only, **not** run by CI; measures
heap/fd/DB/WAL growth and asserts stability):

```
OPENROUTER_API_KEY=... ./gradlew :cli:stress --console=plain --args="--turns 25 --max-steps 4"
```

CI polling must match `sha=$SHA` (`ci.sh` can print a stale run first):

```
SHA=$(git rev-parse --short=7 HEAD)
for i in $(seq 1 30); do out=$(bash /root/ci.sh); line=$(echo "$out" | grep "sha=$SHA" | head -1)
  echo "${line:-$(echo "$out" | head -1)}"
  echo "$line" | grep -qE "conclusion=(success|failure|cancelled)" && { echo "$out"; break; }
  sleep 30; done
```

APK/screenshots: `bash /root/apk.sh` can grab a **stale** run — prefer fetching
the artifact for the exact run id via the GitHub API. Screenshots via
`/root/shots.sh` (read from `/storage/emulated/0/Download/lumen-shots/`).

## 5. Architecture (quick map)

`:core` (pure JVM: model, agent loop, events, SPIs, markdown, references,
revert, UI math), `:sandbox` (TarGz + InAppProxy), `:tools`, `:provider-openai`,
`:provider-anthropic`, `:store-sqlite`, `:cli`, `:server`, `:app`. `:core` has no
Android deps.

Working on device: agent loop (approval policy, agent modes, AGENTS.md rules,
structured `FileEdit` + snapshots, usage/budget events, session
search/fork/rewind/tags); bundled static busybox + Alpine rootfs + opt-in Debian
proot (`targetSdk 28` exec exemption); chat (think→answer, markdown, compact tool
cards, subagent tree, live todo board, changes card w/ per-file revert, usage
meter, image thumbnails); files cockpit, terminal, sandboxed canvas, vision,
websearch, storage manager, diagnostics; `RunService` foreground keep-alive;
models.dev catalogue; on-device `PerfSampler`. Also in `:tools` now: `skill`
(local `SKILL.md`) and `external-directory` (read-only granted roots). The
`:cli` provider graph composes OpenAI/Anthropic adapters behind
`OpenCodeRoutingProvider`; `OpenCodeRouter` is the pure per-model table.

## 6. Known residuals / invariants (do not regress)

- **Retry keeps streaming** via `PartReset` (emitted per part at the start of a
  retried attempt). Do **not** buffer retries or reintroduce double-render.
- **Per-session serialization:** `AgentLoop.prompt` holds a per-session `Mutex`;
  same-session prompts queue, different sessions run concurrently.
- **Snapshot pinning:** `pruneBounded` always keeps the newest snapshot per
  `(session, path)` so revert has a pre-image even after pruning.
- `PartReset` must be mirrored in `:server` `WireEvent` and the app `StepMapper`
  (both already are; the exhaustive-`when` test guards this).
- Compaction is ratio-only overflow + no-op guard; it never wipes the tail.
- **Flaky test (`fixed`):** `SessionRetentionTest > stop tears down the frame
  sampler` is hardened at `6e62605` — test-owned run scopes are cancelled +
  joined before `resetMain()`, then the Robolectric main looper is drained. Keep
  that teardown ordering; do not reintroduce a bare `resetMain()`. The other
  sampler test (`onCleared ...`) is not known to flake.
- **Structured output is engine-only:** `responseFormat` is honored solely by the
  OpenAI-compatible adapter and no host sets it. Do not describe it as a shipped
  surface.
- **`external-directory`/`skill` are engine-only:** no host supplies granted
  roots / a skills root beyond the default, so neither is reachable from a host.

## 7. Docs index

- `docs/PLAN.md` — milestones M0–M10 + merge track m11–m15 (statuses).
- `docs/CAPABILITIES.md` — capability model, parity checklist, app non-goals.
- `docs/SPEC.md` — engine contracts (loop, events, SPIs, wire protocol).
- `docs/PROVIDERS.md` — provider configs + Zen/Go auth surfaces.
- `docs/UI-POLISH.md` — polish passes (base + refinement 1 & 2; all items landed).
- `docs/PERF-RE-SWEEP.md` — m15 long-session JVM stress re-sweep (PASS; off-device).
- `docs/PARITY.md` — what was ported from opencode and what was dropped.
