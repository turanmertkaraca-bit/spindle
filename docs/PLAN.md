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
- [ ] Per-model wire routing inside Zen/Go (Claude/Qwen/MiniMax live on `/messages`,
      GPT/Grok on `/responses`). v1 only routes the OpenAI-compatible subset.

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

## M6 — Cost / context accounting — `[~]` PARTIAL

- [x] `ModelInfo` pricing feeds `Wire.cost`; per-message `Usage.costUsd`.
- [x] `TokenEstimator` + `ContextBudget` (max tokens / max cost) wired into the loop.
- [ ] Session-level totals + budget warnings surfaced through `AgentEvent`.

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

## M9 — CLI + live smoke — `[~]` PARTIAL

- [x] `dev.spindle.cli.MainKt` with `--provider/--model/--prompt` (+ interactive mode,
      `--yes`, `--list-models`, `--max-steps`, `--cwd`, `--db`).
- [x] Streams `AgentEvent`s to stdout.
- [x] `.github/workflows/live.yml` (manual-only, secrets-gated).
- [ ] **Blocked on credentials**: live execution needs a provider key. The OpenCode
      free tier rejects third-party clients
      (`FreeTierError: can only be used from within OpenCode`), so keyless live runs
      are not possible — see Risks.

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

### m12 — Core gaps (Android-free, JVM-tested)

- [ ] Runtime agent selection (build/plan/explore/general).
- [ ] Rules / AGENTS.md injection.
- [ ] Session token + cost totals + budget warnings as events.
- [ ] New events: `UsageUpdated`, `TitleUpdated`, `RunStateChanged`,
      `SubagentStateChanged`, `FileEdited`, `SnapshotCreated`.
- [ ] Provider routing parity: Zen/Go `/responses` + `/messages` per model.
- [ ] `ApprovalPolicy` (allow/ask/deny per tool + path/command glob, persisted).
- [ ] Store: full-text search, fork/branch, rewind, persisted run state,
      pin/archive/tags/rename.
- [ ] `SnapshotStore` + snapshot-before-write.
- [ ] Structured `FileEdit` emission from `write`/`edit`/`apply_patch`.

### m13 — Android platform adapters

- [ ] `ShellExecutor`: host `/bin/sh` (dev) + Debian proot + PTY (device);
      port `Debian.java` / `Sandbox.java`.
- [ ] `FileSystemService` + external-change watcher (port `DirWatcher`).
- [ ] `EnvironmentManager`: rootfs install/curate/prune, apt, storage report.
- [ ] Foreground `RunService` + notifications + wake lock; resumable runs.
- [ ] SQLite FTS index; key store (port `AuthStore`); models.dev catalogue.

### m14 — Feature verticals

- [ ] **Changes**: `RunChanges` aggregate, diff viewer, revert (port/rework
      `EditPulse`).
- [ ] **References**: typed resolver, touched-vs-mentioned, backlinks,
      `@`-completion (port `Mentions` shape rules).
- [ ] Transparency: step timeline, subagent call tree, tool inspector, todo board.
- [ ] Canvas + RenderServer port; Vision (image parts, `supportsVision`).
- [ ] `websearch` tool (properly: result ranking, clipping, citation).
- [ ] Session/file full-text search + navigation history.

### m15 — UI + hardening

- [ ] Compose shell consuming the contracts (deck, chat with think-merge + tool
      + subagent + permission/question surfaces, changes rail, files, terminal,
      canvas, settings, keys/models, diagnostics, storage).
- [ ] Adaptive two-pane (tablet/foldable).
- [ ] Crash guards, on-device performance, battery, ANR avoidance.
- [ ] CI green gate + screenshot/animation evidence; APK update-in-place.

## Risks

- **Provider drift** — remote APIs change shape without notice. Mitigation: record
  real SSE bodies as fixtures and assert the exact `ProviderEvent` stream per
  provider; re-record on failures instead of guessing. Live checks are manual.
- **Free-tier lockout** — OpenCode's free routing only serves the official client,
  so `spindle` cannot use it. Live smoke needs `DEEPSEEK_API_KEY` /
  `OPENROUTER_API_KEY` / `OPENCODE_API_KEY` as GitHub secrets.
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
