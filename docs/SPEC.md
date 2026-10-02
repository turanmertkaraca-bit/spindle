# spindle — Engineering Specification

`spindle` is a native, UI-agnostic Kotlin/JVM agent backend. It runs the agent
loop in-process: no HTTP server, no process bridge, no parallel client-side state
model. The UI (Compose/Android, later) is a thin consumer of an event stream; the
`SessionStore` remains the single source of truth.

Status: **v1**. The `:core` domain, SPIs, and loop are implemented, together with
the provider adapters, the SQLite stores, the tool set, the sandbox helpers, the
CLI/server hosts, and the native Android app. See `PLAN.md` for milestone status.

---

## 1. Module map

| module | role | key deps | state |
|---|---|---|---|
| `:core` | domain model, agent loop, events, provider/tool/store SPIs | coroutines, serialization-json | implemented |
| `:provider-openai` | OpenAI-compatible streaming (`chat/completions`) | `:core`, OkHttp | implemented |
| `:provider-anthropic` | Anthropic-style streaming (`messages`) | `:core`, OkHttp | implemented |
| `:store-sqlite` | durable `SessionStore`/`SnapshotStore` on SQLite (JDBC) | `:core`, sqlite-jdbc | implemented |
| `:tools` | `read`/`write`/`edit`/`glob`/`grep`/`todowrite`/`question` (+ `bash`/`apply_patch`/`webfetch`/`task`) | `:core` | implemented |
| `:sandbox` | tar.gz rootfs helpers + in-app HTTP proxy | JDK only | implemented |
| `:cli` | headless harness + live smoke runner (`dev.spindle.cli.MainKt`) | all JVM modules | implemented |
| `:server` | local UI preview backed by the real agent (`/api` + SSE) | `:core`, `:tools`, providers, `:store-sqlite` | implemented |
| `:app` | native Android/Compose consumer (`dev.lumen.app`) | AndroidX Compose, `:core` | implemented (Robolectric in CI) |

`:core` carries **no Android dependencies** (only `kotlinx-coroutines-core` and
`kotlinx-serialization-json`), so the entire backend — including the agent loop —
is exercisable on a plain JVM and in CI.

Dependency rule: adapters, stores, `:sandbox` and the `:cli`/`:server`/`:app`
hosts depend on `:core`; `:core` depends on none of them. The loop talks to
interfaces only (`ProviderRegistry`, `SessionStore`, `ToolRegistry`, `EventBus`).

---

## 2. Domain model

All model types live in `dev.spindle.core.model` and are `@Serializable` unless
noted.

### 2.1 Identifiers (`Ids.kt`)

```kotlin
@JvmInline @Serializable value class SessionId(val value: String)
@JvmInline @Serializable value class MessageId(val value: String)
@JvmInline @Serializable value class PartId(val value: String)
```

`Ids.new(prefix)` returns `"<prefix>_<base36>"` where the number is
`(System.currentTimeMillis() shl 20) xor counter.incrementAndGet()`. It is
monotonic and sortable within a process; it is **not** a ULID (see §13).

Known prefixes: `ses`, `msg`, `prt`, `q`.

### 2.2 Enums

```kotlin
enum class Role { USER, ASSISTANT, SYSTEM, TOOL }
enum class FinishReason { STOP, TOOL_CALLS, LENGTH, CONTENT_FILTER, ERROR, UNKNOWN }
enum class ToolState { PENDING, RUNNING, DONE, ERROR }
enum class SessionState { IDLE, RUNNING, ERROR }
enum class TodoStatus { PENDING, IN_PROGRESS, DONE, CANCELLED }
```

### 2.3 Usage

```kotlin
@Serializable data class Usage(
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val reasoningTokens: Int = 0,
    val cacheReadTokens: Int = 0,
    val cacheWriteTokens: Int = 0,
    val costUsd: Double = 0.0,
) {
    operator fun plus(other: Usage): Usage   // field-wise sum
    val totalTokens: Int get() = inputTokens + outputTokens
}
```

Usage accumulates across provider events within one assistant message via `plus`.

### 2.4 Tool call / result

```kotlin
@Serializable data class ToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String,   // raw JSON object text; "{}" when absent
)

@Serializable data class ToolResult(
    val callId: String,
    val output: String,
    val isError: Boolean = false,
    val diff: String? = null,
    val metadata: Map<String, String> = emptyMap(),
)
```

`argumentsJson` is kept as an opaque string so partial argument deltas can be
concatenated during streaming without re-parsing.

### 2.5 Part

`Part` is a sealed interface so the UI renders each kind distinctly. Every part
has a stable `PartId`.

```kotlin
@Serializable sealed interface Part {
    val id: PartId
    data class Text(id, text: String) : Part
    data class Reasoning(id, text: String) : Part
    data class Tool(id, call: ToolCall, state: ToolState,
                    result: ToolResult? = null, title: String? = null) : Part
    data class File(id, path: String, mime: String? = null) : Part
    data class Step(id, index: Int) : Part
}
```

### 2.6 Message

```kotlin
@Serializable data class Message(
    val id: MessageId,
    val sessionId: SessionId,
    val role: Role,
    val parts: List<Part> = emptyList(),
    val createdAt: Long,
    val model: String? = null,
    val providerId: String? = null,
    val agent: String? = null,
    val usage: Usage = Usage(),
    val finish: FinishReason? = null,
    val error: String? = null,
)
```

An assistant turn with tool calls is stored once: `parts` holds the optional
`Reasoning`, the optional `Text`, and one `Tool` part per call. Tool results are
written back into the corresponding `Tool` part (state `DONE` / `ERROR`), not as
separate messages.

### 2.7 TodoItem

```kotlin
@Serializable data class TodoItem(val id: String, val content: String, val status: TodoStatus)
```

### 2.8 Session

```kotlin
@Serializable data class Session(
    val id: SessionId,
    val title: String = "",
    val cwd: String,                 // absolute, sandbox root for the session
    val createdAt: Long,
    val updatedAt: Long,
    val model: String? = null,
    val providerId: String? = null,
    val agent: String = "build",
    val parentId: SessionId? = null, // reserved for subagents (out of scope v1)
)
```

---

## 3. Provider SPI

Defined in `dev.spindle.core.provider` (`Provider.kt`, `SimpleProviderRegistry.kt`).

### 3.1 ModelInfo

```kotlin
@Serializable data class ModelInfo(
    val providerId: String,
    val id: String,
    val label: String = id,
    val contextWindow: Int = 128_000,
    val maxOutputTokens: Int = 8_192,
    val supportsTools: Boolean = true,
    val supportsReasoning: Boolean = false,
    val supportsVision: Boolean = false,
    val inputCostPerM: Double = 0.0,
    val outputCostPerM: Double = 0.0,
    val cacheReadCostPerM: Double = 0.0,
    val cacheWriteCostPerM: Double = 0.0,
)
```

Capability flags gate adapter behaviour: the loop only sends `tools` when
`supportsTools` is true.

### 3.2 ChatRequest / WireMessage / ToolSpec

```kotlin
data class WireMessage(
    val role: String,              // "system" | "user" | "assistant" | "tool"
    val text: String? = null,
    val reasoning: String? = null,
    val toolCalls: List<ToolCall> = emptyList(),
    val toolCallId: String? = null,
    val toolName: String? = null,
)

data class ToolSpec(
    val name: String,
    val description: String,
    val parametersJson: String,    // JSON Schema object, as text
)

data class ChatRequest(
    val model: String,
    val system: String,
    val messages: List<WireMessage>,
    val tools: List<ToolSpec> = emptyList(),
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val reasoningEffort: String? = null,  // ignored by adapters that lack it
    val thinking: Boolean? = null,        // explicit thinking mode (DeepSeek)
    val sessionHint: String? = null,      // stable per-conversation id (Go)
)
```

### 3.3 Provider

```kotlin
interface Provider {
    val id: String
    suspend fun models(): List<ModelInfo>
    fun stream(request: ChatRequest): Flow<ProviderEvent>
}
```

`models()` may hit the network (cached by the registry). `stream()` is a cold
`Flow`; cancellation of the collector must abort the HTTP call. A provider never
throws for HTTP/remote errors that occur mid-stream — it emits `Failure` and
completes the flow. It may throw for setup failures before the first byte.

### 3.4 ProviderEvent stream contract

```kotlin
sealed interface ProviderEvent {
    data class TextDelta(val text: String) : ProviderEvent
    data class ReasoningDelta(val text: String) : ProviderEvent
    data class ToolCallStart(val index: Int, val id: String, val name: String) : ProviderEvent
    data class ToolCallArgsDelta(val index: Int, val argsDelta: String) : ProviderEvent
    data class ToolCallEnd(val index: Int) : ProviderEvent
    data class UsageEvent(val usage: Usage) : ProviderEvent
    data class Finished(val reason: FinishReason) : ProviderEvent
    data class Failure(val message: String, val cause: Throwable? = null) : ProviderEvent
}
```

Contract rules every adapter must obey:

1. **Ordering is authoritative at the delta level.** `ToolCall*` events carry an
   `index` because OpenAI-style streams interleave multiple concurrent tool calls.
   Argument fragments must be emitted in arrival order for a given index.
2. **Reassembly is the adapter's job, not the loop's.** The loop accumulates
   `ToolCallArgsDelta` per index and takes the final `argumentsJson` at
   `ToolCallEnd`. Assert `Finished` follows the last tool-call fragment.
3. **Exactly one terminal event.** A stream ends with either `Finished` or
   `Failure`, never both and never zero (except cancellation).
4. **`UsageEvent` may be absent or may arrive once at the end** depending on
   provider; the loop treats a missing usage as `Usage()`.
5. **`TextDelta` / `ReasoningDelta` payloads are incremental** — adapters must not
   emit cumulative text.
6. Adapters must never emit `TextDelta` for assistant tool-call framing.

### 3.5 ProviderRegistry / SimpleProviderRegistry

```kotlin
interface ProviderRegistry {
    fun provider(id: String): Provider?
    fun all(): List<Provider>
    suspend fun models(): List<ModelInfo>
    suspend fun resolve(modelRef: String): Pair<Provider, ModelInfo>?
}
```

`resolve` accepts `"provider/model"` (split on the **first** `/`, so nested ids
like `openrouter/anthropic/claude-3.5` resolve provider `openrouter`) or a bare
model id, which is looked up across all registered providers. Model lists are
cached once behind a mutex.

---

## 4. Provider configuration

Four providers ship in v1: DeepSeek, OpenRouter, OpenCode Zen, OpenCode Go. All
authenticate with `Authorization: Bearer <api key>`. Each adapter is an
OpenAI-compatible adapter (`:provider-openai`) or an Anthropic adapter
(`:provider-anthropic`); "mixed" providers pick the adapter per model.

| provider | base URL | wire format(s) | model id prefix | auth | notes |
|---|---|---|---|---|---|
| DeepSeek | `https://api.deepseek.com` | `chat/completions` | `deepseek/` | `Authorization: Bearer $DEEPSEEK_API_KEY` | model ids `deepseek-flash`, `deepseek-v4-pro`; `thinking` maps to explicit reasoning mode |
| OpenRouter | `https://openrouter.ai/api/v1` | `chat/completions` | `openrouter/` | `Authorization: Bearer $OPENROUTER_API_KEY` | vendor ids can contain `/`; optional `HTTP-Referer` / `X-Title` attribution headers |
| OpenCode Zen | `https://opencode.ai/zen/v1` | `chat/completions` + `messages` + `responses` | `opencode/` | `Authorization: Bearer $OPENCODE_API_KEY` | adapter chosen per model capability |
| OpenCode Go | `https://opencode.ai/zen/go/v1` | `chat/completions` + `messages` + `responses` | `opencode-go/` | `Authorization: Bearer $OPENCODE_API_KEY` | **requires `x-opencode-session: <sessionId>` on every request** and a stable custom `User-Agent`; the response is routed by session |

### 4.1 OpenCode Go session header (mandatory)

Go is a session-affine gateway. The adapter MUST send:

- `x-opencode-session: <sessionHint>` — pass `ChatRequest.sessionHint` through
  verbatim. The loop sets `sessionHint = sessionId.value`, so every request in a
  conversation carries the same stable id.
- A stable, non-default `User-Agent`, e.g. `spindle/<version>`.

A missing or changing `x-opencode-session` may cause the gateway to reject the
request or lose conversation affinity. This is a hard requirement, not an
optimization.

### 4.2 Provider → wire format

`chat/completions` is used by DeepSeek, OpenRouter, and the OpenAI-capable models
of Zen/Go. `messages` (Anthropic) is used by models that only speak that shape on
Zen/Go. `responses` is listed by the Zen/Go docs as an available surface; spindle
v1 does **not** target it (see §13).

| provider | default wire | accepts tools | notes |
|---|---|---|---|
| DeepSeek | `chat/completions` | yes | reasoning content may arrive as `reasoning_content` |
| OpenRouter | `chat/completions` | yes | pass-through of upstream vendor quirks |
| OpenCode Zen | per model | per model | route by model metadata |
| OpenCode Go | per model | per model | route by model metadata; session header required |

---

## 5. SessionStore

SPI in `dev.spindle.core.store.SessionStore`; default in-memory implementation in
`InMemorySessionStore`. The durable implementation is `:store-sqlite`.

```kotlin
interface SessionStore {
    suspend fun createSession(session: Session)
    suspend fun updateSession(session: Session)
    suspend fun session(id: SessionId): Session?
    suspend fun sessions(limit: Int = 50, includeChildren: Boolean = false): List<Session>
    suspend fun deleteSession(id: SessionId)

    suspend fun appendMessage(message: Message)
    suspend fun updateMessage(message: Message)
    suspend fun message(sessionId: SessionId, id: MessageId): Message?
    suspend fun messages(sessionId: SessionId): List<Message>
    suspend fun latestMessage(sessionId: SessionId): Message?

    suspend fun todos(sessionId: SessionId): List<TodoItem>
    suspend fun setTodos(sessionId: SessionId, todos: List<TodoItem>)

    suspend fun prune(keepSessions: Int = 100): Int
}
```

Contract:

- `sessions` is ordered by `updatedAt` descending and filters out child sessions
  unless `includeChildren` is set.
- `appendMessage` preserves insertion order; `messages` returns that order.
- `updateMessage` replaces by id, or inserts if missing (idempotent upsert).
- Implementations must be safe under single-writer, many-reader use; the
  in-memory store serializes with a `Mutex`.
- `prune` drops whole sessions beyond `keepSessions` (oldest `updatedAt`) and their
  messages/todos, returning the count of sessions removed.

### 5.1 SQLite schema overview (`:store-sqlite`)

Four tables; messages and parts are stored together for simple, ordered reads.

```
session(
  id TEXT PRIMARY KEY,
  title TEXT NOT NULL DEFAULT '',
  cwd TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  model TEXT,
  provider_id TEXT,
  agent TEXT NOT NULL DEFAULT 'build',
  parent_id TEXT REFERENCES session(id)
)

message(
  id TEXT PRIMARY KEY,
  session_id TEXT NOT NULL REFERENCES session(id) ON DELETE CASCADE,
  role TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  model TEXT,
  provider_id TEXT,
  agent TEXT,
  finish TEXT,
  error TEXT,
  usage_json TEXT NOT NULL DEFAULT '{}',
  parts_json TEXT NOT NULL DEFAULT '[]',   -- serialized List<Part>
  seq INTEGER NOT NULL                      -- insertion order within session
)
CREATE INDEX message_session_seq ON message(session_id, seq);

todo(
  session_id TEXT NOT NULL REFERENCES session(id) ON DELETE CASCADE,
  id TEXT NOT NULL,
  content TEXT NOT NULL,
  status TEXT NOT NULL,
  position INTEGER NOT NULL,
  PRIMARY KEY (session_id, id)
)

meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)   -- schema_version, etc.
```

- Whole-message parts (`parts_json`) keep the write atomic and the read ordered.
- `appendMessage` assigns `seq = MAX(seq)+1` per session; `messages` orders by `seq`.
- WAL mode and `foreign_keys=ON`; one connection per store instance guarded by a
  mutex.
- `PRAGMA user_version` records the schema version for forward migrations.

---

## 6. Tools

SPI in `dev.spindle.core.tool.Tool`. Every tool exposes a
`dev.spindle.core.provider.ToolSpec` (name, description, JSON Schema) and runs
against a `ToolContext`.

```kotlin
interface ToolContext {
    val sessionId: SessionId
    val cwd: java.nio.file.Path             // absolute sandbox root
    suspend fun requestPermission(tool: String, detail: String, pattern: String? = null): Boolean
    suspend fun ask(question: String, options: List<String>, multiple: Boolean = false): List<String>
    fun emit(event: ToolProgress)
    fun checkAborted()
}

data class ToolOutcome(
    val output: String,
    val isError: Boolean = false,
    val diff: String? = null,
    val metadata: Map<String, String> = emptyMap(),
)

interface Tool {
    val spec: ToolSpec
    suspend fun run(input: JsonObject, ctx: ToolContext): ToolOutcome
}

class ToolRegistry(tools: List<Tool>) {
    val specs: List<ToolSpec>
    fun get(name: String): Tool?
    fun all(): List<Tool>
}
```

### 6.1 Tool list and one-line schemas

All paths are resolved against `ctx.cwd` and must remain inside it (see §12).

| tool | one-line schema |
|---|---|
| `read` | `{path:string, offset?:int, limit?:int}` → numbered file contents; binary/huge files refused. |
| `write` | `{path:string, content:string}` → create/overwrite a file; emits a unified diff. |
| `edit` | `{path:string, old_string:string, new_string:string, replace_all?:bool}` → exact-match replacement; fails if `old_string` is absent or ambiguous. |
| `glob` | `{pattern:string, path?:string}` → matching paths, newest first, capped. |
| `grep` | `{pattern:string, path?:string, include?:string, context?:int}` → matching lines with `file:line` prefixes. |
| `todowrite` | `{todos:[{content:string, status:string}]}` → replaces the session todo list; emits state to the store. |
| `question` | `{question:string, options:[string], multiple?:bool}` → suspends for the user's answer via `ctx.ask`, returns the selection. |

Notes:

- `read` clips to a bounded number of lines and never reads directories.
- `edit` requires a unique `old_string` unless `replace_all` is true; a
  whitespace/indentation mismatch is surfaced, not silently corrected.
- `todowrite` is the only tool with durable side state besides the filesystem; it
  maps onto `SessionStore.setTodos`.
- `question` is passive: it never mutates the repo and exists to resolve genuine
  ambiguity instead of guessing.

---

## 7. Agent loop

`dev.spindle.core.agent.AgentLoop`, configured by `AgentConfig`.

```kotlin
data class AgentConfig(
    val name: String = "build",
    val maxSteps: Int? = null,          // null = unlimited
    val temperature: Double? = null,
    val reasoningEffort: String? = null,
    val systemPrompt: String = DEFAULT_SYSTEM,
)
```

`DEFAULT_SYSTEM` and `PLAN_SYSTEM` are provided; the default agent is `build`.

### 7.1 Algorithm (pseudocode)

```
prompt(sessionId, userText, modelRef, agent, onPermission):
    (provider, model) = registry.resolve(modelRef) or fail
    session = store.session(sessionId) or fail

    emit StateChanged(RUNNING)
    try:
        store.appendMessage(user Message(parts=[Text(userText)]))
        step = 0
        loop:
            step += 1
            history = store.messages(sessionId)
            wire    = Wire.toWire(history)
            specs   = tools.specs if model.supportsTools else []

            assistant = new assistant Message(model, providerId, agent)
            store.appendMessage(assistant); emit MessageCreated(assistant)

            text, reasoning = "", ""
            calls = ordered map<index, {id, name, argsBuilder}>
            usage = Usage(); finish = UNKNOWN; failure = null

            collect provider.stream(Wire.request(model, system, wire, specs, agent, sessionHint=sessionId)):
                TextDelta        -> text += delta;        emit PartDelta(TEXT)
                ReasoningDelta   -> reasoning += delta;   emit PartDelta(REASONING)
                ToolCallStart    -> calls[index] = {id, name}
                ToolCallArgsDelta-> calls[index].args += delta
                ToolCallEnd      -> no-op
                UsageEvent       -> usage += u
                Finished         -> finish = reason
                Failure          -> failure = message

            if failure:
                finalize assistant with error, finish=ERROR, usage
                emit Error; emit StateChanged(ERROR); return assistant

            toolCalls = calls.values (sorted by index) as ToolCall[]
            parts = [Reasoning?, Text?, Tool(PENDING) per call]
            finalized = assistant.copy(parts, usage.costUsd = Wire.cost(model, usage),
                                       finish = TOOL_CALLS if toolCalls else finish)
            store.updateMessage(finalized); emit PartUpdated per part

            if toolCalls empty: break
            if agent.maxSteps != null and step >= agent.maxSteps: break

            for each Tool part in finalized.parts:
                executeTool(sessionId, finalized.id, part, session.cwd, onPermission)

        emit StateChanged(IDLE)
        return store.latestMessage(sessionId)

    catch CancellationException:
        mark latest message finish=ERROR, error="aborted"
        emit StateChanged(IDLE); rethrow
    catch Throwable:
        emit Error; emit StateChanged(ERROR); rethrow
```

### 7.2 Tool execution

```
executeTool(sessionId, messageId, part, cwd, gate):
    tool = registry.get(part.call.name)
    updatePart(part.copy(state=RUNNING))

    if tool == null:
        finish with ToolResult(isError=true, "Unknown tool: <name>"); return

    input = parse(part.call.argumentsJson) or {}
    try:   outcome = tool.run(input, LoopToolContext(sessionId, cwd, gate, questions, bus))
    catch CancellationException: rethrow
    catch Throwable: outcome = ToolOutcome("Tool <name> failed: <msg>", isError=true)

    clip outcome.output to maxToolOutputChars (default 60_000), appending a
    "...[truncated N chars]" marker
    finish with state = ERROR if outcome.isError else DONE, and a ToolResult
```

`finishTool` writes the updated part back to the store and emits `ToolFinished`.
Parallel tool calls are executed sequentially in ascending index order; a tool
failure is reported to the model as an errored tool result, not a loop abort.

### 7.3 Wire conversion (`Wire.kt`)

`Wire.toWire(messages)` expands stored messages into `WireMessage`s:

- `SYSTEM` / `USER` / `TOOL` → a single message with concatenated `Text` parts.
- `ASSISTANT` → one assistant message carrying concatenated `Text`, concatenated
  `Reasoning`, and all `ToolCall`s, followed by one `tool` `WireMessage` per tool
  part that has a result (carrying `toolCallId` and `toolName`).

`Wire.cost(model, usage)` computes USD from per-million rates over input (cache
miss), output, cache-read, and cache-write token counts.

---

## 8. Events

`dev.spindle.core.event.EventBus` fans out `AgentEvent` on a `MutableSharedFlow`
with `replay = 0`, `extraBufferCapacity = 1024`, and `BufferOverflow.DROP_OLDEST`
— a slow UI drops old deltas rather than back-pressuring the loop. The store
remains authoritative; events are a live view.

```kotlin
sealed interface AgentEvent {
    val sessionId: SessionId
    data class StateChanged(sessionId, state: SessionState)
    data class MessageCreated(sessionId, messageId: String, role: String)
    data class PartDelta(sessionId, messageId, partId: PartId, kind: DeltaKind, delta: String)
    data class PartUpdated(sessionId, messageId, part: Part)
    data class ToolFinished(sessionId, messageId, partId: PartId, result: ToolResult)
    data class PermissionRequested(sessionId, requestId: String, tool: String, detail: String, pattern: String?)
    data class QuestionAsked(sessionId, requestId: String, question: String, options: List<String>, multiple: Boolean)
    data class Error(sessionId, message: String)
}
enum class DeltaKind { TEXT, REASONING }
```

Event ordering guarantees per prompt:

1. `StateChanged(RUNNING)` first, `StateChanged(IDLE|ERROR)` last.
2. `MessageCreated(assistant)` precedes any `PartDelta` for that message.
3. `PartDelta`s for a part precede the terminal `PartUpdated` for that part.
4. `ToolFinished` follows the `PartUpdated(RUNNING)` and the final
   `PartUpdated(DONE|ERROR)` for the same tool part.
5. `Error` is always paired with a terminal `StateChanged(ERROR)`.

`PermissionRequested` and `QuestionAsked` are emitted by `ToolContext`; how the UI
answers them (a `PermissionGate` / `QuestionGate`) is an upper-layer concern.

---

## 9. Wiring / lifecycle

A headless host constructs the object graph:

```
ProviderRegistry = SimpleProviderRegistry(listOf(deepseek, openrouter, zen, go))
ToolRegistry     = ToolRegistry(tools.read/write/edit/glob/grep/todowrite/question)
SessionStore     = SqliteSessionStore(path)      // or InMemorySessionStore in tests
EventBus         = EventBus()
AgentLoop        = AgentLoop(providers, tools, store, bus, permissions, questions)
```

`prompt(...)` is the only entry point and is safe to call concurrently for
distinct sessions. Session creation/seeding is the host's responsibility.

---

## 10. CLI (spec)

`:cli` is a headless harness with `mainClass = dev.spindle.cli.MainKt`.

```
./gradlew :cli:run --args="--provider <id> --model <id> --prompt \"<text>\" [--cwd <dir>] [--agent <name>] [--max-steps N]"
```

- `--provider` selects the adapter (and therefore the API key env var).
- `--model` is the provider-scoped model id (bare id is fine).
- `--prompt` is the user text; the process streams `AgentEvent`s to stdout and
  exits non-zero on `Failure` or an errored terminal message.
- API keys come from `DEEPSEEK_API_KEY`, `OPENROUTER_API_KEY`,
  `OPENCODE_API_KEY`.

This same binary is the target of the manual live-smoke workflow
(`.github/workflows/live.yml`).

---

## 11. Testing

- `:core` is pure JVM: loop and `Wire` unit tests use `InMemorySessionStore` and a
  scripted fake `Provider` that emits a fixed `ProviderEvent` sequence.
- Provider adapters use OkHttp `MockWebServer` to replay recorded SSE bodies
  (fixtures), asserting the exact `ProviderEvent` stream.
- The SQLite store is tested against a temp file, including `prune` and ordered
  reads.
- Live network tests are **not** run in CI (`ci.yml`); they are manual only
  (`live.yml`) so no PR can spend tokens.

---

## 12. Security: tool path safety

Tools receive an absolute `ctx.cwd`. Every path argument must be resolved with
`cwd.resolve(input).normalize()` and verified to still have `cwd` as a prefix
**after** resolving symlinks (`toRealPath()` where the target exists). Paths that
escape the sandbox — `..`, absolute paths outside `cwd`, or symlink traversal —
are rejected with an errored `ToolOutcome`. Destructive/side-effecting tools
(`write`, `edit`) should route through `ctx.requestPermission` when a
`PermissionGate` is configured. The loop itself never bypasses the sandbox.

---

## 13. Deliberately dropped for v1

These are explicit non-goals. They are not bugs; do not build them now.

- **LSP integration** — no diagnostics, symbols, or code actions. Tools operate on
  text and the filesystem only.
- **MCP** — no Model Context Protocol client/server. The tool set is in-process
  and closed.
- **Plugins** — no dynamic loading, no hook system, no third-party extension
  points.
- **Subagents** — `Session.parentId` is reserved but the loop never spawns a child
  session in v1.
- **Compaction beyond trim + summarize** — no hierarchical/embeddings-based
  memory. When context pressure arrives, the only allowed strategy is trimming old
  parts plus a single summarization pass.
- **`/responses` wire surface** — Zen/Go advertise it; v1 targets
  `chat/completions` and `messages` only.
- **Parallel tool execution** — tools run sequentially in call order.
- **ULID ids / cross-process uniqueness** — `Ids.new` is process-monotonic only.
