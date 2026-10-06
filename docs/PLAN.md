# spindle — Plan

Milestones for the v1 backend. `:core` is the foundation; everything else is an
adapter or a harness around it. See `SPEC.md` for the contracts each milestone
must satisfy, and `CAPABILITIES.md` for the merged native-app capability model
(the m11–m15 track below).

Legend: `[x]` done · `[~]` partial · `[ ]` todo · `[—]` deliberately deferred.

## M0 — Repo scaffold + core SPIs/loop — `[x]` DONE

- [x] Gradle multi-module scaffold (`:core`, `:provider-openai`,
      `:provider-anthropic`, `:store-sqlite`, `:tools`, `:cli`).
- [x] Domain model: `Session`, `Message`, `Part`, `Usage`, `ToolCall`,
      `ToolResult`, `TodoItem`, ids, enums.
- [x] SPIs: `Provider`/`ProviderRegistry`, `Tool`/`ToolRegistry`, `SessionStore`.
- [x] `EventBus` + `AgentEvent` fan-out.
- [x] `AgentLoop` (assemble → stream → run tools → repeat) and `Wire`.
- [x] `InMemorySessionStore`.
- [x] CI (`ci.yml`) build + test on JDK 17.

## M1 — OpenAI-compatible provider — `[x]` DONE

- [x] `:provider-openai` SSE parser for `chat/completions`.
- [x] Tool-call index reassembly (start / args-delta / end).
- [x] `reasoning_content` handling (DeepSeek, OpenRouter).
- [x] `models()` catalog with pricing + capability flags (falls back to defaults).
- [x] `MockWebServer` fixtures for text, tool call, reasoning, error.

## M2 — Anthropic provider + provider config — `[~]` PARTIAL

- [x] `:provider-anthropic` SSE parser for `messages`.
- [x] Anthropic content-block ↔ `ProviderEvent` mapping (incl. tool_use input_json_delta).
- [x] DeepSeek, OpenRouter, Zen, Go configs in the CLI (`buildProviders`).
- [x] OpenCode Go: stable `x-opencode-session` header + custom `User-Agent`.
- [x] Per-model wire routing inside Zen/Go (`1845a77`): Claude-family/Qwen →
      `/messages` via the Anthropic adapter (`x-api-key` + `anthropic-version`;
      Go also threads `x-opencode-session` + custom `User-Agent`). GPT/Grok
      `/responses` targets return an explicit terminal `Failure` — `/responses`
      is **deliberately not implemented**, not silently mis-routed. Routing is
      fixture + unit tested.
- [—] Live Claude/GPT streams **not pursued (deprioritized by owner decision)**:
      the free tier returns 403 "Model access is disabled" for Claude/GPT on
      `/messages`, `/responses` and `/chat/completions`, and the owner has an
      OpenCode Go subscription only and will **not** add paid API credits. Only
      free models (`space-bunny-free`) run live, on Go `/chat/completions`.
      Routing itself is implemented + fixture-tested (`/messages`); this is a
      closed scope decision, not a dangling "waiting on a key" task.

## M3 — SQLite store — `[x]` DONE

- [x] `:store-sqlite` on `sqlite-jdbc`; schema + `schema_version` migration runner.
- [x] WAL, `foreign_keys=ON`; single connection behind a mutex.
- [x] Ordered `messages()`/parts; polymorphic `Part` JSON via `SerializersModule`.
- [x] `todos` round-trip and `prune(keepSessions)`.
- [x] Durability test: reopen from disk and compare.

## M4 — Tools — `[x]` DONE

- [x] `read`, `write`, `edit`, `glob`, `grep`, `todowrite`, `question`.
- [x] Path sandbox enforcement with escape tests (`../secret` rejected).
- [x] Permission (`PermissionGate`) + question (`QuestionGate`) wired through `ToolContext`.
- [x] Unified-diff-ish output for `edit`.

## M5 — Loop hardening (retries / abort) — `[x]` DONE

- [x] Bounded retry with exponential backoff + jitter for transient failures (`Retry`).
- [x] Terminal vs retryable classification (429/5xx/timeout retry; 4xx/auth/context do not).
- [x] Cooperative cancellation: `ToolContext.checkAborted()` is suspend and calls `ensureActive()`.
- [x] Step budget (`AgentConfig.maxSteps`) and output clipping.
- [x] Tests: `RetryTest`, `SubagentTest`, `AgentLoopTest`.

## M6 — Cost / context accounting — `[x]` DONE

- [x] `ModelInfo` pricing feeds `Wire.cost`; per-message `Usage.costUsd`.
- [x] `TokenEstimator` + `ContextBudget` (max tokens / max cost) wired into the loop.
- [x] Session-level totals + budget warnings surfaced through `AgentEvent`
      (`UsageUpdated`, `BudgetWarning` at `warnAtFraction` and at the ceiling).

## M7 — Compaction (trim + summarize) — `[x]` DONE

- [x] `Compaction.trim` clips old tool output/reasoning without changing message shape.
- [x] `Compaction.compact` replaces the head with one summary via the cheapest model,
      with a deterministic offline fallback if the provider call fails.
- [x] Never compacts an open tool call; skips re-compacting an already-compacted head.
- [x] Tests: `OverflowTest`, plus trim/compact coverage in `AgentLoopTest`.

## M8 — Tools: bash / patch / fetch / subagents — `[x]` DONE

- [x] `bash` (sandboxed `/bin/sh`, timeout, merged stderr, output cap, permission gate).
- [x] `apply_patch` (opencode patch format, atomic, escape-checked, unified diff).
- [x] `webfetch` (JDK HttpClient, proxy-aware, html→markdown).
- [x] `todowrite` persists through `ToolContext.setTodos`.
- [x] `task` subagents: `SubagentSpawner`, child sessions, `explore`/`general` configs.

## M9 — CLI + live smoke — `[x]` DONE

- [x] `dev.spindle.cli.MainKt` with `--provider/--model/--prompt` (+ interactive mode,
      `--yes`, `--list-models`, `--max-steps`, `--cwd`, `--db`).
- [x] Streams `AgentEvent`s to stdout.
- [x] `.github/workflows/live.yml` (manual-only, secrets-gated; never on push/PR).
- [x] **Live smoke verified** (2026-10) against OpenRouter with a paid
      `OPENROUTER_API_KEY`: text + reasoning + usage and a multi-step
      write→read→bash→read→answer tool-loop both succeeded. Real recorded SSE
      fixtures are committed. Also verified live on **OpenCode Go** (the app's
      default provider) with a free-tier key and free model `space-bunny-free`
      (real write→read tool loop). Both remain manual and secrets-gated — see
      Risks / `HANDOFF.md` for the key traps.

## M10 — Android UI — `[—]` LATER / OUT OF SCOPE NOW

- [ ] Compose consumer of `EventBus`, rendering `SessionStore`.
- [ ] Interactive `PermissionGate` / `QuestionGate` surfaces.
- [ ] Room-backed `SessionStore` (parity with `:store-sqlite`).
- [ ] Swap `bash` to the Debian sandbox instead of host `/bin/sh`.

The UI is intentionally last. `:core` stays Android-free so the backend can be
completed and verified on the JVM first.

## Merge track — native Lumen app — `[~]` IN PROGRESS

The merged app: opencode-android's feature surface on spindle's native engine,
fully native, function-first. `opencode-android` is frozen as the parity
reference; its platform code is ported, not rewritten. See `CAPABILITIES.md`.

### m11 — Capability spec — `[x]` DONE

- [x] `CAPABILITIES.md`: domains, parity checklist, new SPIs, non-goals.
- [x] `PLAN.md` merge track (this section).

### m12 — Core gaps (Android-free, JVM-tested) — `[x]` DONE

- [x] Runtime agent selection (build/plan/explore/general).
- [x] Rules / AGENTS.md injection.
- [x] Session token + cost totals + budget warnings as events.
- [x] New events: `UsageUpdated`, `TitleUpdated`, `RunStateChanged`,
      `SubagentStateChanged`, `FileEdited`, `SnapshotCreated`, and `PartReset`
      (retry reset that keeps live token streaming — see SPEC §8).
- [x] Per-session run serialization (`AgentLoop.prompt` holds a per-session
      `Mutex`; different sessions still run concurrently).
- [x] Provider routing parity: Zen/Go per-model wire routing (`1845a77`) —
      Claude/Qwen → `/messages` via the Anthropic adapter (fixture-tested ✅);
      GPT/Grok → explicit terminal `Failure` for `/responses` (**deliberately
      rejected**). Fixture + routing tests committed. Live Claude/GPT is **out of
      scope by owner decision** (OpenCode Go subscription only, no paid API
      credits), not blocked-pending-a-key.
- [x] Structured output opt-in (`bf86874`): `ResponseFormat(name, schemaJson,
      strict)` + defaulted `ChatRequest.responseFormat` and
      `AgentConfig.responseFormat`; the OpenAI-compatible adapter emits
      `response_format` only when set (Anthropic ignores it). No host wires it
      yet — unreachable from `:cli`/`:app`/`:server` until a host opts in.
- [x] `skill` + `external-directory` tools (`15635e8`), registered in
      `DefaultTools`: local `SKILL.md` discovery/load clamped under the session
      cwd, and read-only access to explicit user-granted roots (empty allow-list
      denies everything).
- [x] `ApprovalPolicy` (allow/ask/deny per tool + path/command glob, persisted).
- [x] Store: full-text search, fork/branch, rewind, persisted run state.
- [x] Pin / archive / rename session surfaces (Home row actions; archived
      hidden behind a toggle).
- [x] Session tags (add/remove/clear + any-of filter bar and row chips; persisted
      through `Session.tags`).
- [x] `SnapshotStore` + snapshot-before-write.
- [x] Structured `FileEdit` emission from `write`/`edit`/`apply_patch`.

### m13 — Android platform adapters

- [x] `ShellExecutor`: host `/bin/sh` (dev) + Debian proot + PTY (device);
      port `Debian.java` / `Sandbox.java`.
- [x] External-change watcher (port `DirWatcher`); files cockpit covers
      list/stat/read/write/save.
- [x] `EnvironmentManager`: rootfs install/curate/prune, apt, storage report.
- [x] Foreground `RunService` + notifications + wake lock; the loop now runs in
      the Application-scoped `LumenApp.applicationScope`, so a run survives
      backgrounding and only an explicit stop cancels it (`87ebe96`; see HANDOFF).
- [x] SQLite FTS index on the app store (`message_fts`, FTS5 with scan
      fallback); key store ported; models.dev live catalogue enrichment merged
      over the embedded snapshot.

### m14 — Feature verticals — `[x]` DONE

- [x] **Changes**: `RunChanges` aggregate, diff card, per-file revert (rework of
      `EditPulse`).
- [x] **References** (`3791ad2`, `47d1ec4`): typed resolver (`path:line`/ranges)
      with directory support (`ReferenceResolver.resolveKinds`,
      `FileReference.isDir`), touched-vs-mentioned, and tappable mentions that
      open the file in the Files viewer at the line (newly created files link on
      a workspace revision bump).
- [x] **Files live refresh + edit highlight** (`47d1ec4`): the list refreshes on
      agent writes/edits (direct `FileEdited` + indirect `WorkspaceWatcher`);
      opening a touched file scrolls to and highlights the newest edited range
      (suppressed for newly created files, `FileEdit.created`).
- [x] Canvas viewer (sandboxed WebView) + Vision (image parts, `supportsVision`,
      composer attachment).
- [x] `websearch` tool (`cea4936`: browser-style POST to DuckDuckGo with an
      honest blocked-vs-no-results error) and `webfetch` (unresolved hosts only
      when a proxy resolves them; HTTP status surfaced).
- [x] Session full-text search + fork/rewind surfaces.
- [x] Interactive permission/question cards; ask-before-tools setting.
- [x] Backlinks panel + `@`-completion in the composer (port `Mentions` extras).
- [x] Subagent call tree, tool inspector, live todo board.

### m15 — UI + hardening — `[~]` IN PROGRESS

- [x] Compose shell: deck/home, chat (think-merge, compact tools, markdown),
      changes card, files cockpit, terminal, canvas, settings, keys/models,
      diagnostics, storage.
- [x] Full Linux userland: Alpine (bundled) + opt-in Debian proot, behind
      `ShellExecutor`; targetSdk 28 exec exemption.
- [x] Adaptive two-pane (tablet/foldable) — files cockpit list + editor.
- [x] Off-device long-session stress re-sweep **PASS** (`a4807fd`,
      `docs/PERF-RE-SWEEP.md`): 25 live OpenRouter turns, retained heap flat at
      ~6 MB (0.00 MB/turn), fds 38→39, DB+WAL ≤192 KB, 0 errors. This is the
      JVM harness, **not** an on-device measurement.
- [~] On-device perf/ANR sweep: first pass clean (48–57 fps, 1–6% janky,
      p95 ≤33 ms, worst ≤200 ms only at cancel, peak heap ≤37 MB). Streaming
      perf fix has since landed (bounded tail-window rendering, no
      `animateContentSize` on growing content, 40 ms coalesced deltas, async
      rebuilds), plus the long-session stability work. A fresh on-device re-sweep
      (battery/thermal, long-session heap/fd/DB/WAL retention) is still
      **pending**; `PerfSampler` logs a `perf:` line to diagnostics per run.
- [x] CI green gate + screenshot evidence (light+dark across every screen,
      uploaded as the `lumen-screenshots` artifact); APK update-in-place.
- [x] UI polish landed in three passes: base (`ea2e38e` + `189b695`), refinement
      1 (`fa9ff1a` chat timeline + `dab4f39` shared screen chrome + `1422eb1`
      FilesScreen fix) and refinement 2 (`47fd30b` lighter/calmer), plus the 0.1.2
      rework below. The light+dark sweep covers every screen including GitHub:
      ~87 PNGs (61 screens + 26 animation frames), 0 render errors.
- [x] **Composer + Home + keys + GitHub rework (`47d1ec4`, fixes
      `977d856`/`098a921`/`f6b92a8`)**: one rounded composer card with a bottom
      control row and compact usage line (`≈ N new · next $X · ctx N`, cue
      `↓ latest`); Home status strip (provider·model, sandbox, budget, GitHub) +
      ask-before-tools / Files / Terminal controls; API-key screen with provider
      cards, Show/Hide, Paste, a real "Test key" probe, inline error and
      non-destructive editing; GitHub PAT connect/validate (`GitHubClient`) +
      clone/status (`GitRunner`), token sealed in `KeyStore` and never placed in
      a URL/argv/log, via `GitHubScreen` + Settings link + Home status pill.
      **PAT only — no OAuth; commit/push automation not built.**
- [x] Release housekeeping: `app/build.gradle.kts` bumped to `versionCode = 3`,
      `versionName = "0.1.2"` (bump per release; never reuse a version number).

## Risks

- **Provider drift** — remote APIs change shape without notice. Mitigation: record
  real SSE bodies as fixtures and assert the exact `ProviderEvent` stream per
  provider; re-record on failures instead of guessing. Live checks are manual.
  DeepSeek/OpenRouter live paths are the ones exercised; Zen/Go live
  Claude/GPT is a closed scope decision (below), so there is less live surface to
  drift.
- **Free-tier limits / routing scope** — OpenCode has no keyless path, but a
  free-tier key DOES work for **free models on `/chat/completions`** (e.g.
  `space-bunny-free`); the free tier returns 403 "Model access is disabled" for
  Claude/GPT on `/messages`, `/responses` and `/chat/completions`. Per-model
  routing is implemented and fixture-tested (`/messages`), and `/responses` is
  deliberately rejected. **Live Claude/GPT is deprioritized/closing by owner
  decision** (OpenCode Go subscription only; no paid API credits will be added),
  so it is *not* a blocker to chase. `:models` on opencode.ai is public, so a 200
  is not proof a key is valid. Live smoke needs a `DEEPSEEK_API_KEY` /
  `OPENROUTER_API_KEY` / `OPENCODE_API_KEY` / `ANTHROPIC_API_KEY` GitHub secret
  and is manual-only (`workflow_dispatch`), so no PR can spend tokens. Verified
  2026-10 with `OPENROUTER_API_KEY` (paid) and a free OpenCode key
  (`opencode-go` + `space-bunny-free`).
- **Tool path safety** — a bad resolve lets a tool read or write outside `cwd`
  (`..`, absolute paths, symlinks). Mitigation: normalize + containment checks on
  every path argument and adversarial tests per escape vector.
- **Streaming chunk reassembly** — SSE frames can split across reads, interleave
  tool calls, and deliver arguments in fragments. Mitigation: buffer by frame,
  reassemble tool calls by `index`, and test with byte-level split fixtures.
- **Context growth / cost blowup** — long sessions silently exceed the model
  window and budget. Mitigation: M6 estimation + M7 trim/summarize before send.
- **OpenCode Go session affinity** — a missing `x-opencode-session` can lose or
  reject a conversation. Mitigation: always thread `sessionHint` and test the header.
