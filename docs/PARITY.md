# spindle — opencode parity

What `spindle` reproduces from opencode, and what it deliberately does not.
Baseline: opencode `dev` (~136k LOC source, ~133k LOC tests, 435 test files).
`spindle` is ~22k LOC of Kotlin source (~15k LOC tests) across the JVM backend
plus the native Android app — a deliberate slice. This file is kept current.

Legend: ✅ ported · 🟡 partial · ❌ not ported (by choice unless noted)

## Core

| opencode | spindle | |
|---|---|---|
| Agent loop (`session/prompt.ts`) | `agent/AgentLoop.kt` | ✅ |
| Message/Part model (`message-v2.ts`) | `model/Model.kt` (`Part.Text/Reasoning/Tool/File/Step`) | ✅ |
| Provider abstraction (`llm.ts`) | `provider/Provider.kt` + `ProviderEvent` | ✅ |
| Event bus / streaming fan-out | `event/EventBus.kt` + `AgentEvent` | ✅ |
| Retry with backoff | `agent/Retry.kt` | ✅ |
| Token estimate + overflow policy | `agent/ContextPolicy.kt` | ✅ |
| Compaction (trim + summarize) | `agent/Compaction.kt` | ✅ |
| Session title/summary auto-gen | — | ❌ (UI can title from first message) |
| Structured output (`json_schema`) | `ResponseFormat` + `ChatRequest.responseFormat` (OpenAI adapter emits `response_format` only when set; Anthropic ignores it; host opt-in pending) | ✅ |
| Snapshots / revert / undo / fork | `SnapshotStore` (pinned pre-images) + per-file revert + fork/rewind | ✅ |
| Session tags / pin / archive / retention | `Session.tags/pinned/archived` + tags UI + `pruneBounded` | ✅ |
| Per-session prompt serialization | `AgentLoop` per-session `Mutex` | ✅ |
| Retry without double-render (live streaming kept) | `AgentEvent.PartReset` + app drops the failed attempt | ✅ |
| Share links | — | ❌ |

## Providers

| opencode | spindle | |
|---|---|---|
| OpenAI-compatible `chat/completions` SSE | `:provider-openai` | ✅ |
| Anthropic `messages` SSE | `:provider-anthropic` | ✅ |
| `reasoning_content` / thinking deltas | both adapters | ✅ |
| DeepSeek, OpenRouter | OpenAI adapter | ✅ |
| OpenCode Zen/Go | per-model router: OpenAI adapter (`/chat/completions`) + Anthropic adapter (`/messages`); Go threads `x-opencode-session` + custom UA | ✅ `/messages` wired + fixture-tested; live Claude/GPT out of scope by owner decision (no paid API access) |
| Zen/Go `/responses` (GPT/Grok) | `OpenCodeRoutingProvider` emits an explicit terminal `Failure` (deliberately not implemented) | ❌ by choice |
| Zen/Go `/models/gemini-*`, `/systemone` | — | ❌ |
| OAuth providers (Vertex, Bedrock, Copilot, GitLab, Poe…) | — | ❌ |
| Local providers (Ollama/LM Studio style) | — | ❌ |
| Vision / image input | `Part.File` + `supportsVision` (composer attach) | ✅ |
| Chat-prefix / FIM completion | — | ❌ |

## Tools

| opencode | spindle | |
|---|---|---|
| `read` `write` `edit` `glob` `grep` | `:tools` (sandboxed to cwd) | ✅ |
| `bash` (PTY) | `BashTool` (`/bin/sh`, no PTY) | 🟡 no interactive PTY / background jobs |
| `apply_patch` | `ApplyPatchTool` (opencode patch format) | ✅ |
| `webfetch` | `WebFetchTool` (JDK HttpClient) | ✅ |
| `todowrite` | `TodoWriteTool` (persisted via `ctx.setTodos`) | ✅ |
| `question` | `QuestionTool` + `QuestionGate` | ✅ |
| `task` (subagents) | `TaskTool` + `SubagentSpawner` | ✅ general + explore |
| `websearch` | `WebSearchTool` (keyless DuckDuckGo HTML) | ✅ |
| `skill` | `SkillTool` (closed local `SKILL.md` discovery under the session cwd) | ✅ |
| `lsp` | — | ❌ |
| `code-mode` | — | ❌ |
| `external-directory` | `ExternalDirectoryTool` (read-only; explicit user-granted roots; empty allow-list denies all) | ✅ |
| Post-run truncation store | flat char cap | 🟡 |

## Agents & orchestration

| opencode | spindle | |
|---|---|---|
| Subagents (child sessions, `parentId`) | `SubagentSpawner`, `Session.parentId` | ✅ |
| Multiple primary agents (build/plan/custom) | `AgentConfig.PRIMARY` + `byName` (runtime selection) | ✅ |
| `@init` → `AGENTS.md` | — | ❌ (no generator) |
| Rules / reminders injection | `AgentLoop.systemPrompt` reads `<cwd>/AGENTS.md` + host rules | ✅ |
| Agent Skills | `skill` tool: local `SKILL.md` discovery/load (no marketplaces) | ✅ |
| `@`-mention subagents | — | ❌ (UI concern) |

## Permissions

| opencode | spindle | |
|---|---|---|
| Allow / ask / deny, per-tool | `PermissionGate` + `AgentConfig.denyTools` | ✅ |
| Glob/sub-pattern scoping, "always" memory | `ApprovalPolicy` (per-tool + path/command pattern, remembered) | ✅ |
| Unattended auto-allow | `--yes` in the CLI | 🟡 |

## Storage

| opencode | spindle | |
|---|---|---|
| Sessions, messages, parts, todos | `:store-sqlite` (schema + `schema_version`) | ✅ |
| Retention / prune | `SessionStore.prune` | ✅ |
| Cross-process/multi-client store | — | ❌ (single process, intentionally) |

## Platform / ecosystem (all intentionally dropped)

❌ MCP · plugins + hooks · ACP · worktrees · git integration · LSP servers ·
formatters · background jobs · config layers (`opencode.json`) · custom commands ·
keybinds · themes engine.

❌ HTTP server + OpenAPI + SSE-over-HTTP — **this is the win**: no server, no bridge.

## Android-specific (reuse later, not opencode)

✅ `bash` runs through a `ShellExecutor`: host `/bin/sh` on dev/CI, the ported
Debian proot sandbox + PTY on Android. The tool boundary is unchanged — only the
executor swaps.

## Gaps ranked by when they will bite

1. **Zen/Go live Claude/GPT streams** — the routing is wired and fixture-tested
   (`/messages` for Claude/Qwen); the free-tier key 403s Claude/GPT on every
   surface. This is **deliberately closed / deprioritized** by owner decision
   (OpenCode Go subscription only; no paid API credits), **not blocked pending a
   paid key**. `/responses` (GPT/Grok) is intentionally dropped, not planned.
2. Everything else — optional ecosystem.
