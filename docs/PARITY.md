# spindle — opencode parity

What `spindle` reproduces from opencode, and what it deliberately does not.
Baseline: opencode `dev` (~136k LOC source, ~133k LOC tests, 435 test files).
`spindle` is ~4.2k LOC — a deliberate ~3% slice. This file is kept current.

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
| Structured output (`json_schema`) | — | ❌ |
| Snapshots / revert / undo / fork | — | ❌ |
| Share links | — | ❌ |

## Providers

| opencode | spindle | |
|---|---|---|
| OpenAI-compatible `chat/completions` SSE | `:provider-openai` | ✅ |
| Anthropic `messages` SSE | `:provider-anthropic` | ✅ |
| `reasoning_content` / thinking deltas | both adapters | ✅ |
| DeepSeek, OpenRouter | OpenAI adapter | ✅ |
| OpenCode Zen/Go | OpenAI adapter (`x-opencode-session` + custom UA) | 🟡 `/chat/completions` models only |
| Zen/Go `/messages`, `/responses`, `/models/gemini-*`, `/systemone` | — | ❌ |
| OAuth providers (Vertex, Bedrock, Copilot, GitLab, Poe…) | — | ❌ |
| Local providers (Ollama/LM Studio style) | — | ❌ |
| Vision / image input | — | ❌ |
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
| `websearch` | — | ❌ |
| `skill` | — | ❌ |
| `lsp` | — | ❌ |
| `code-mode` | — | ❌ |
| `external-directory` | — | ❌ (sandbox is strict) |
| Post-run truncation store | flat char cap | 🟡 |

## Agents & orchestration

| opencode | spindle | |
|---|---|---|
| Subagents (child sessions, `parentId`) | `SubagentSpawner`, `Session.parentId` | ✅ |
| Multiple primary agents (build/plan/custom) | `AgentConfig` (`DEFAULT_SYSTEM`, `PLAN_SYSTEM`, `EXPLORE_SYSTEM`, `GENERAL_SYSTEM`) | 🟡 configs exist, no runtime switch from UI yet |
| `@init` → `AGENTS.md` | — | ❌ |
| Rules / reminders injection | — | ❌ |
| Agent Skills | — | ❌ |
| `@`-mention subagents | — | ❌ (UI concern) |

## Permissions

| opencode | spindle | |
|---|---|---|
| Allow / ask / deny, per-tool | `PermissionGate` + `AgentConfig.denyTools` | ✅ |
| Glob/sub-pattern scoping, "always" memory | — | ❌ (interactive allow only) |
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

🟡 `bash` currently uses the host `/bin/sh`. On Android it should run through the
existing Debian sandbox from the old app rather than a JVM process. The tool
boundary is already correct — only the executor needs swapping.

## Gaps ranked by when they will bite

1. **`bash` sandbox swap** — on-device only; the tool API is ready.
2. **Zen/Go `/responses` + `/messages` routing** — needed for GPT/Grok and Claude/Qwen
   via the gateways.
3. **AGENTS.md / rules** — house-style adherence in real repos.
4. **Snapshot/undo** — safety net once the agent edits your code.
5. Everything else — optional ecosystem.
