# Lumen — session handoff (read me first)

Native Android agent app. `spindle` repo, branch `main`, package `dev.lumen.app`.
`HEAD = 06b113c8b7c085890d834dfd7b1ffe63c9734614`. CI (`.github/workflows/ci.yml`,
both `jvm backend` + `android app (robolectric)`) is **green at 06b113c**. `:cli`
is now gated in the jvm job (`:cli:classes`), so its run path compiles in CI even
though it never spends tokens. Working tree: documentation edits only, no code
changes pending.

```
██  NEVER COMMIT tokens/secrets. The git remote already embeds the PAT.  ██
██  Never paste the PAT or an API key into chat or into a doc. Rotate keys  ██
██  when dev is finished. Live tests are manual only (`live.yml`).          ██
```

## 1. What's done (this session)

1. **Background-run bug FIXED (`87ebe96`).** The agent loop runs in the
   Application-scoped owner `LumenApp.applicationScope`, not `viewModelScope`;
   `closeChat`/`onCleared` no longer cancel it, only an explicit stop does. Shared
   event bus + live-run tracking let a reopened ViewModel reflect an in-flight
   run; `RunService` hardened. Top-priority issue is closed.
2. **Core engine correctness.** Compaction no longer wipes the tail/current
   prompt and can always shrink (ratio-only overflow + no-op guard); `Wire` emits
   a matching tool reply per call; `maxSteps` finalizes PENDING tools; retry no
   longer double-counts usage; cancellation is correct; symlink sandbox escape
   closed.
3. **Provider streaming hardening.** Mid-stream `IOException` → `Failure`;
   cancellation aborts the OkHttp call; exactly one terminal; a single
   `ToolCallStart` with accumulated id/name; `ToolCallEnd` before `Finished`;
   Anthropic threads `sessionHint`/`userAgent`/`x-opencode-session`; SSE line
   reads bounded (16 MiB); clean EOF → retryable `Failure`; bounded reasoning
   tracking.
4. **Long-session stability.** FTS `optimize` + WAL checkpoint at end of run
   (`maintain()`); bounded snapshots (`pruneBounded` per-session + global + age)
   with **newest-per-`(session,path)` pinning** so revert always has a pre-image;
   session-delete cascades snapshots; stale open tool calls finalized; ViewModel
   caches bounded; `PerfSampler` bounded; Android FTS falls back to a scan on
   write failure.
5. **Streaming/UI perf.** Bounded text rendering (tail window while streaming,
   prefix when finished, chunked "show full"); removed `animateContentSize` on
   growing content; coalesced deltas (40 ms); async + coalesced rebuilds;
   off-thread vision decode; composer/app-bar polish; session tags UI; models.dev
   catalogue enrichment.
6. **Retry double-render FIXED while keeping live token streaming (`e1d773f`).**
   New `AgentEvent.PartReset` is emitted once per part at the start of a retried
   attempt; the app clears that part's delta buffer and drops the failed attempt's
   tool rows.
7. **Defensive active-run guard (`06b113c`).** `AgentLoop.prompt` serializes per
   session with a per-session `Mutex`: a second same-session prompt waits for the
   first; different sessions still run concurrently.
8. **Live verification (M9) DONE.**
   - OpenRouter (paid key) verified: real text + reasoning (`reasoning` field) +
     usage, and a real multi-step tool loop (write→read→bash→read→answer).
     Recorded real SSE fixtures are **committed** at
     `provider-openai/src/test/resources/openrouter_recorded_{text,tool}.sse`,
     with exact `ProviderEvent` assertions in `OpenAiProviderTest`.
   - **OpenCode Go — the app's DEFAULT provider — verified live** with a real
     free-tier key: `--provider opencode-go --model space-bunny-free` ran a real
     write→read tool loop. This validates the app's default provider path.
   - Found+fixed live: resolver was broken for slash-namespaced model ids
     (`openrouter/auto`); `parseUsage` dropped real `cost` / `cache_write_tokens`.

## 2. What's left / blocked

- **Zen/Go per-model routing** (`/responses` for GPT/Grok, `/messages` for
  Claude/Qwen) — **blocked**: needs a PAID OpenCode key to record real streams.
  Free tier returns **403 "Model access is disabled"** for Claude/GPT. Do not
  claim this works.
- **Multi-session concurrency from the UI** — the engine allows it (per-session
  locks); the UI does not drive N chats at once yet.
- **`m15` on-device perf re-sweep** — the streaming fix landed; a fresh device
  sweep (long-session heap/fd/DB/WAL under load) is pending.
- Deferred: structured output (`json_schema`), `skill` / `external-directory`
  tools.
- Explicit non-goals: OAuth, local models, MCP, LSP, plugins, git-commit UI.

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
- Free tier returns **403 "Model access is disabled"** for Claude/GPT, so
  Zen/Go `/messages` + `/responses` parity stays blocked until a paid key.

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
models.dev catalogue; on-device `PerfSampler`.

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

## 7. Docs index

- `docs/PLAN.md` — milestones M0–M10 + merge track m11–m15 (statuses).
- `docs/CAPABILITIES.md` — capability model, parity checklist, app non-goals.
- `docs/SPEC.md` — engine contracts (loop, events, SPIs, wire protocol).
- `docs/PROVIDERS.md` — provider configs + Zen/Go auth surfaces.
- `docs/UI-POLISH.md` — polish pass (composer/app-bar landed; remainder parked).
- `docs/PARITY.md` — what was ported from opencode and what was dropped.
