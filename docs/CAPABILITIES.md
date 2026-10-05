# spindle — Capabilities

The capability model for the merged **Lumen** app: the opencode-android feature
surface, rebuilt on spindle's native engine. This file is the contract between
the engine and the (later) UI. **Nothing gets a screen until its capability
exists here, is exercisable headless, and is reliable.**

Direction (locked):

- Base = `spindle` (repo stays `turanmertkaraca-bit/spindle`), package
  `dev.lumen.app`, UI last.
- `opencode-android` is frozen as the parity reference and last-known-good
  release; its proven platform code (proot/Debian, DirWatcher, RenderServer,
  Vision, Models, Storage, Theme, Shims) is **ported, not rewritten**.
- Fully native: no bundled opencode server, no HTTP/SSE bridge, no parallel
  client state model.
- User owns the device: the app must be manageable, observable, and
  controllable from the phone, with no silent failure modes.

Legend: ✅ exists in spindle · 🟡 partial · ➕ to build · 📦 port from
opencode-android · ❌ dropped on purpose.

---

## 0. Platform constraint (read first)

The execution capability needs **`targetSdk 28`**. That is the W^X/SELinux
exemption the old app verified on device: it is the only way to `exec()` the
proot loader and Debian binaries out of app-private storage without root.
Compose runs fine at targetSdk 28. This is non-negotiable while we own a real
Linux userland; it is also why the app is sideloaded, not Play-distributed.

---

## 1. Capability domains

Each domain is a contract the UI consumes. Domains 1–6 are Android-free and
JVM-testable; 7–9 are platform adapters.

### 1.1 Agent runtime — `:core` (`dev.spindle.core.agent`)

| capability | status |
|---|---|
| Loop: assemble → stream → tools → repeat | ✅ |
| Retry/backoff, cancellation, step budget | ✅ |
| Compaction (trim + summarize) | ✅ |
| Subagents (child sessions, `parentId`) | ✅ |
| Primary-agent selection at runtime (build/plan/explore/general) | ✅ |
| Rules / reminders injection (AGENTS.md, house style) | ✅ |
| Session token + cost totals surfaced as events | ✅ |
| Max-cost budget + warnings | ✅ (pricing populated; ceiling fires) |
| Per-session run serialization (same-session prompts queue; distinct sessions run concurrently) | ✅ |
| Structured output (`json_schema`, opt-in) | ✅ (engine: `ResponseFormat`; OpenAI-compatible adapter only; Anthropic ignores it; no host opts in yet) |

New events: `UsageUpdated`, `TitleUpdated`, `RunStateChanged` (per-session),
`SubagentStateChanged`, `FileEdited`, `SnapshotCreated`, and `PartReset` (retry
reset; retracts a failed attempt's streamed parts while keeping the retry live —
see SPEC §8).

### 1.2 Session & store — `:core` + `:store-sqlite`

| capability | status |
|---|---|
| create/update/delete session, ordered messages, todos | ✅ |
| Durable SQLite store (schema + migration runner) | ✅ |
| Full-text search over messages/parts | ✅ (FTS5 + scan fallback; `maintain()` optimize) |
| Fork / branch a session at a message | ✅ |
| Rewind / revert to a message | ✅ (snapshot-pinned pre-images) |
| Persisted session run state (a reopened running child reads as running) | ✅ |
| Pin / archive / tags / rename | ✅ (tags UI: add/remove/clear + any-of filter) |
| Retention / prune | ✅ (bounded snapshots via `pruneBounded`) |

### 1.3 Tools & approval — `:core` + `:tools`

| tool | status |
|---|---|
| `read` `write` `edit` `glob` `grep` | ✅ |
| `apply_patch` | ✅ |
| `bash` (sandboxed `/bin/sh`) | 🟡 (non-interactive; PTY terminal is separate → see 1.4) |
| `webfetch` | ✅ |
| `todowrite` | ✅ |
| `question` | ✅ |
| `task` (subagents) | ✅ |
| `websearch` | ✅ (keyless DuckDuckGo HTML) |
| `skill` | ✅ (closed local `SKILL.md` discovery/load under the session cwd) |
| `external-directory` | ✅ (read-only; explicit user-granted roots; an empty allow-list denies every request) |

Every tool result carries a structured diff + typed metadata (✅ shape in
`ToolOutcome`; tighten per tool).

New SPI — **`ApprovalPolicy`**: allow / ask / deny per tool **and per
path/command glob**, with persisted "always" memory. Replaces today's
auto-allow stubs in the app. (`PermissionGate` stays as the transport; the
policy is the decision.)

### 1.4 Shell / execution — `:platform-android`

New SPI — **`ShellExecutor`**:

```
run(command, cwd, timeoutMs, env) -> { exit, stdout+stderr, truncated }
openPty(...) -> interactive session
```

Implementations (shipped ✅): host `/bin/sh` (dev/CI), Debian proot + PTY
(device), plus the bundled static busybox applets first on PATH. Ported
from `Debian.java` (`prootArgv`, `guestProcess`, `runGuest`, rootfs
install/curate) and `Sandbox.java` (wrapper generation). This is the single
biggest parity win and the reason targetSdk stays 28.

### 1.5 Workspace / files

New SPI — **`FileSystemService`**: list/stat/read/write/search, project-scoped
canonical-path clamp (port `FilesActivity` clamp rules), snapshots, save-from-UI.

| capability | status |
|---|---|
| read/write/list/stat/search | ✅ (files cockpit) |
| External-change watcher (event-driven, port `DirWatcher`) | ✅ (`WorkspaceWatcher`) |
| Snapshot before write + revert | ✅ (`SnapshotStore`; pinned pre-images) |
| Git-lite status / diff | ➕ (defer commit UI) |

### 1.6 Provider / auth / cost

| capability | status |
|---|---|
| OpenAI-compatible + Anthropic streaming | ✅ |
| DeepSeek, OpenRouter, Zen, Go configs | ✅ |
| Zen/Go per-model routing (`/messages` for Claude/Qwen, `/chat/completions` otherwise) | ✅ (wired + fixture-tested; `/responses` for GPT/Grok is **deliberately rejected** with an explicit terminal `Failure`) |
| Zen/Go live Claude/GPT streams | ❌ (blocked on a paid OpenCode key; free tier 403s `/messages`, `/responses` and `/chat/completions` for Claude/GPT) |
| models.dev catalogue (ported snapshot + live enrichment) | ✅ |
| Key store (port `AuthStore`, `KeysActivity`) | ✅ (encrypted `KeyStore` + key screen) |
| OAuth providers | ❌ |
| Free tier without a key | ❌ (no keyless path; a free-tier key serves only free `/chat/completions` models) |

### 1.7 Environment manager — `:platform-android`

| capability | status |
|---|---|
| Debian rootfs install / curate / prune | ✅ (opt-in, not auto-downloaded) |
| apt, git, python, node, gcc available to the agent | ✅ (through `ShellExecutor`) |
| Package-manager surface (install/remove) | ➕ |
| Service manager (start/stop long-running processes) | ➕ |
| Storage footprint report + safe reclaim | ✅ |

### 1.8 Platform lifecycle

| capability | status |
|---|---|
| Foreground `RunService` for long runs + notification | ✅ |
| Resumable runs; store is truth across process death | ✅ (Application-scoped `LumenApp.applicationScope`; `onCleared` cannot cancel) |
| Wake lock only while working | ✅ (`PARTIAL_WAKE_LOCK` in `RunService`) |
| Watchdog / boot behavior | 📦 |
| Multi-session concurrency (run N chats at once) | ➕ (engine: per-session mutex allows it; UI does not drive it) |

### 1.9 Observability

| capability | status |
|---|---|
| Guarded render/parse paths (a bad part degrades, never crashes) | ➕ |
| Incident log (port `Trail`) + last-exit diagnosis | 📦 |
| Crash/ANR avoidance on the main thread | ➕ |

---

## 2. Feature verticals (the user-visible functions)

Built on the domains above, before any UI.

### 2.1 Changes (reimagines `EditPulse`)

- Every `write`/`edit`/`apply_patch` emits a structured
  `FileEdit{path, startLine, endLine, added, removed, diff, before, after}`.
- A `RunChanges` aggregate per session: files touched, hunks, +/- totals.
- Revert: per-hunk, per-file, or whole-run, backed by `SnapshotStore`.
- The watcher only covers **indirect** writes (a script the agent ran) so the
  picture is complete and honest.

### 2.2 References (reimagines `Mentions`)

- Resolver turns assistant text into typed targets: `path`, `path:line`,
  `path:line:col`, `path#Lx-Ly`, inline-code and bare tokens — fenced code is
  inert (port the `Mentions` shape filter + existence gate).
- Each target tagged **touched** (changed this run → open diff) vs **mentioned**
  (→ open file). Backlinks: file → messages/tool calls referencing it.
- Composer `@`-completion over project files, inserted as context chips.

### 2.3 Transparency

- Step timeline (thought → tool → edit → answer) with jump-to-step.
- Subagent call tree with live child status; drill into a child transcript.
- Tool inspector: input, output, exit, duration, files touched.
- Live todo board from `todowrite`.

### 2.4 Canvas / render / vision

- **Canvas** 📦 — sandboxed WebView for self-contained HTML the agent writes
  (JS on, file/content access off, loaded as a string, never auto-open).
- **Render bridge** 📦 — the localhost render endpoint (`RenderServer` +
  `RenderCheck`) so the agent can verify its own HTML in the app's WebView;
  independent of any server lifecycle now.
- **Vision** 📦 — screenshot → image part (native image input) with a free
  vision-model fallback via `ModelInfo.supportsVision`; renders as an image
  bubble (needs `Part.File` image support end to end).

### 2.5 Search & navigation

- Full-text search across sessions and files; jump-to-message.
- File nav back/forward history; deep links to a file at a line.

---

## 3. Native advantages to exploit

- Own the tools ⇒ structured diffs, exact touched-file sets, snapshot/revert,
  no output scraping.
- Own the store ⇒ search, fork, rewind, replay, instant reopen.
- In-process ⇒ no SSE pacing/governor needed; per-token rendering is direct.
- Android platform ⇒ PTY terminal, package manager, render bridge, vision,
  adaptive two-pane layouts.

---

## 4. Non-goals (explicit; do not build)

| dropped | why |
|---|---|
| MCP | a protocol for third-party tool servers; we own a closed, in-process tool set. |
| LSP | we have grep/glob plus a real `gcc`/`node`/`python` toolchain; a compiler and grep beat an LSP on-device. |
| Plugins / hooks | no third-party extension market; eliminates a whole class of failure modes. |
| OAuth providers | API keys only; the connect screen writes the key form. |
| Free keyless tier | native third-party clients are rejected; the user does not need it. |
| HTTP server / OpenAPI / SSE-over-HTTP | the win: no server, no bridge, no parallel state. |
| Git commit UI (v1) | defer; keep status/diff only. |
| Local models (v1) | defer; revisit once the on-device runtime is stable. |

---

## 5. Verification discipline (unchanged)

1. Pure logic → JVM tests (`./gradlew build`, `:core:test` is the fast loop).
2. Platform/Android → CI (Robolectric) + on-device APK; read the screenshots
   every time.
3. Every feature lands with its function test **before** its UI.
4. No UI/functional regressions; the old app is the parity baseline.
5. Never reuse a version number; every release updates in place (persistent
   debug keystore).
