# spindle — Plan

Milestones for the v1 backend. `:core` is the foundation; everything else is an
adapter or a harness around it. See `SPEC.md` for the contracts each milestone
must satisfy.

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

## M5 — Loop hardening (retries / abort) — `[~]` PARTIAL

- [x] Step budget (`AgentConfig.maxSteps`) with a loop test.
- [ ] Bounded retry with backoff for transient provider/network failures.
- [ ] Real cancellation: `ToolContext.checkAborted()` and cooperative tool abort.
- [ ] Distinguish terminal vs retryable `Failure`.

## M6 — Cost / context accounting — `[~]` PARTIAL

- [x] `ModelInfo` pricing feeds `Wire.cost`; per-message `Usage.costUsd`.
- [ ] Context-window estimation from wire messages + tool specs.
- [ ] Session-level totals + budget warnings before send.

## M7 — Compaction (trim + summarize) — `[ ]` TODO

- [ ] Trim old tool output / parts when over budget.
- [ ] Single summarization pass for evicted history.
- [ ] Never compact the most recent turn or an open tool call.

## M8 — CLI + live smoke — `[~]` PARTIAL

- [x] `dev.spindle.cli.MainKt` with `--provider/--model/--prompt` (+ interactive mode,
      `--yes`, `--list-models`, `--max-steps`, `--cwd`, `--db`).
- [x] Streams `AgentEvent`s to stdout.
- [x] `.github/workflows/live.yml` (manual-only, secrets-gated).
- [ ] **Blocked on credentials**: live execution needs a provider key. The OpenCode
      free tier rejects third-party clients
      (`FreeTierError: can only be used from within OpenCode`), so keyless live runs
      are not possible — see Risks.

## M9 — Android UI — `[—]` LATER / OUT OF SCOPE NOW

- [ ] Compose consumer of `EventBus`, rendering `SessionStore`.
- [ ] Interactive `PermissionGate` / `QuestionGate` surfaces.
- [ ] Room-backed `SessionStore` (parity with `:store-sqlite`).

The UI is intentionally last. `:core` stays Android-free so the backend can be
completed and verified on the JVM first.

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
