# Lumen — session handoff (read me first)

Native Android agent app. `spindle` repo, branch `main`, package `dev.lumen.app`.
`HEAD = e4c1464` (`fix(app): correct assertTrue(message, block) misuse in
question-tool test`) — the tip of the **0.1.3** UI/UX fix wave. The wave is
`749df0d` (readable boundaries, connected tool runs, cleaner key entry, in-chat
quick settings) plus `e4c1464` (a test-only `assertTrue` fix). It rides on the
**0.1.2** feature wave (`3791ad2` `:core` directory mentions, `cea4936` `:tools`
websearch/webfetch, `47d1ec4` app + fixes `977d856`/`098a921`/`f6b92a8`). The
docs-sync + version-bump commit rides **on top of** `e4c1464` (nothing else is
committed after it as of this writing). CI (`.github/workflows/ci.yml`) runs both
a `jvm backend` job and an `android app (robolectric)` job; CI status for
`e4c1464` + this docs-sync commit is **not** asserted here — re-check the run.
`:cli` is gated in the jvm job (`:cli:classes`), so its run path compiles in CI
even though it never spends tokens. Working tree after this session: the
docs-sync edits + `app/build.gradle.kts` version bump only (left uncommitted, per
instructions).

**Version: `versionCode = 4`, `versionName = "0.1.3"`** — release discipline:
never reuse a version number, every release updates in place. Bump `versionCode`
(and `versionName`) in `app/build.gradle.kts` for every release.

```
██  NEVER COMMIT tokens/secrets. The git remote already embeds the PAT.  ██
██  Never paste the PAT or an API key into chat or into a doc. Rotate keys  ██
██  when dev is finished. Live tests are manual only (`live.yml`).          ██
```

## 1. What's done (this wave — 0.1.3)

The 0.1.3 UI/UX fix wave: `749df0d` plus the test-only `e4c1464`.

1. **Readable boundaries (`749df0d`).** A new opaque `outline` colour role and a
   `row` shape token; every card, row and input now draws a minimal 1dp gray
   rounded boundary, and the dark surface / light background are raised so panels
   read as distinct layers instead of one flat field.
2. **Connected tool runs (`749df0d`).** Tool cards and thinking rows join into
   visual runs rather than a scatter of isolated cards: `UiStep.linkedAbove` (set
   by `StepMapper.linkRuns`, unit-tested) plus a thin 1dp rail with tight 3dp row
   spacing. `YOU`/`ASSISTANT`/`QUESTION` rows break the run.
3. **No raw tool JSON (`749df0d`).** Tool detail never falls back to
   `argumentsJson`; question/prompt/query and the other friendly keys are
   surfaced, with summary/body falling back to plain text or the tool name.
4. **Streaming tail pinned (`749df0d`).** The newest line stays glued to the
   bottom when the usage meter, todo/changes/ask cards or the IME shrink the
   viewport (a layout-keyed re-pin, not only content growth).
5. **API-key screen redesigned (`749df0d`).** Compact provider radio rows, a
   visible field boundary, and unified inline test feedback. (The 0.1.2
   non-destructive editing, Show/Hide, Paste and real "Test key" probe remain.)
6. **In-chat quick settings sheet (`749df0d`).** Provider, model, theme, budget
   and ask-before-tools plus key/full-settings/close links, rendered over the
   transcript, so opening settings inside a chat no longer jumps to the full
   Settings page.
7. **Test fix (`e4c1464`).** Corrected `assertTrue(message, block)` misuse in
   `StepMapperToolDetailTest` so the pending-question "no raw JSON" assertion
   checks the condition and carries its message correctly.
8. **Version bump (this commit).** `app/build.gradle.kts` → `versionCode 4`,
   `versionName "0.1.3"` (only version lines changed).
9. **Prior-session wins still in force:** the 0.1.2 wave — chat composer
   redesign, mentions → Files, files live refresh + edit highlight, richer Home,
   API-key redesign, GitHub PAT connect/status/clone (**commit/push not built**) —
   plus Zen/Go per-model wire routing (`1845a77`/`a5f36b9`; `/messages`
   fixture-tested, `/responses` deliberately rejected, live Claude/GPT closed by
   owner decision), `skill` + `external-directory` (`15635e8`), opt-in
   `json_schema` structured output (`bf86874`, engine-only), the UI polish passes
   (`ea2e38e`/`189b695`/`fa9ff1a`/`dab4f39`/`1422eb1`/`47fd30b`), the `m15` JVM
   stress re-sweep PASS (`a4807fd`), and `SessionRetentionTest` flake hardening
   (`6e62605`).

## 2. What's left (honest)

- **`m15` on-device perf/ANR re-sweep** — the JVM stress re-sweep is PASS
  (`a4807fd`); the **on-device** sweep (ART/ANR/frame timing, battery/thermal,
  long-session heap/fd/DB/WAL) is still pending and **unverified**. Do not claim
  on-device perf.
- **Host opt-in for structured output** — the engine supports
  `ResponseFormat`/`responseFormat`, but no host (`:cli`, `:app`, `:server`)
  constructs one, so `json_schema` output is unreachable from any entry point.
- **Host opt-in for `external-directory`** — the tool denies everything until a
  host supplies granted roots via `DefaultTools.registry(externalRoots = ...)`;
  no host does yet. `skill` defaults to `.opencode/skills` under the session cwd.
  Neither is reachable from a host today.
- **Multi-session concurrency from the UI** — the engine allows it (per-session
  locks); the UI does not drive N chats at once yet.
- **Git push/commit automation is not built.** GitHub support is PAT connect +
  status + clone/open only; there is no automatic commit or push, and no OAuth.
- **Zen/Go live Claude/GPT parity — deliberately closed / deprioritized, NOT
  blocked pending a paid key.** The owner only has an OpenCode Go subscription
  and will **not** add API credits. `/messages` routing is implemented and
  fixture-tested; the free tier 403s Claude/GPT on every surface. `/responses`
  likewise stays intentionally dropped. This is a **product/owner decision**, so
  future sessions should **not** chase a paid key or re-open it as a dangling
  task.
- Explicit non-goals: OAuth, local models, MCP, LSP, plugins, marketplace
  skills.

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
- `docs/UI-POLISH.md` — polish passes (base + refinements 1–4; all items landed,
  incl. the in-chat quick-settings sheet in the sweep).
- `docs/PERF-RE-SWEEP.md` — m15 long-session JVM stress re-sweep (PASS; off-device).
- `docs/PARITY.md` — what was ported from opencode and what was dropped.
