# Lumen ↔ opencode reliability parity & capability roadmap

Audit of Lumen (`dev.lumen.app`, engine **spindle**; Kotlin `:core` `:tools` `:app`
`:store-sqlite` `:provider-*` `:server` `:cli`) against official **opencode** (TypeScript).
Scope is reliability + capability, not feature-count parity (`docs/PARITY.md` owns that).
Citations are Lumen `file:line`; opencode references are for the behaviors we want to match.

## Status (updated)

Current HEAD `7c3dfd1`, version **0.2.1**, CI green. The analysis below is preserved as written;
this section records what has since landed so a reader can separate done from open.

- **Provider resilience — DONE**: `ProviderEvent.Failure` is typed (`statusCode`/`retryAfterMs`/
  `retryable`/`contextOverflow`); overflow now compacts-and-retries; retry hygiene (`maxRetries` 5,
  saturating backoff, `Retry-After`); `max_tokens` set; OpenAI/Anthropic parse in-band error
  frames. **Credential refresh still OPEN** — the owner ships static API keys.
- **Streaming durability — PARTIAL**: partial-stream checkpoint DONE; per-part upsert
  (`SessionStore.updatePart`) DONE; durable event log + `Last-Event-ID`/`after` catch-up +
  incremental UI patching still OPEN (low value for the single-client app).
- **Run lifecycle — PARTIAL**: `RunRecovery.reconcile` in `:core` + `updateSessionState` targeted
  writes DONE; background-job parent→child cascade registry still OPEN. Note the per-session
  `Mutex` already serializes (joins) same-session prompts.
- **Storage integrity — MOSTLY DONE**: versioned transactional migrations, retention janitor,
  idempotent append + `UNIQUE(session_id,seq)`, FTS repair, corrupt-DB quarantine, monotonic
  snapshot ordering DONE. OPEN: connection refcount/close race, sha256 content-addressed snapshot
  dedup, real FKs/cascade.
- **Runtime/tools — PARTIAL**: cancellation-aware device shell + process-group kill, per-file edit
  locks, bounded glob, schema error for bad args DONE. OPEN: per-tool timeouts, bash output
  spill-to-file, wildcard permission rules/`external_directory`/doom-loop, watcher truncation
  notices.
- **UI — DONE (frozen)**: palettes (prism/ember/phosphor/abyss, default abyss), adaptive icon,
  Home LIVE badges, per-session notifications + custom error sound + deep link, a11y + touch
  targets, unified chips, wrapping composer, delegate chip removed. The owner has frozen UI
  changes.
- **Features — PARTIAL**: session export/import (redacted JSON) DONE; guarded git commit/push
  DONE. OPEN: auto-format, custom commands/`/init`, `json_schema` host opt-in, true multi-session
  view.

## 1. Summary

Lumen reproduces opencode's *shape* (agent loop, event bus, retry, compaction, snapshots) but
not its **failure semantics**. The recurring defect is that correctness depends on a single
in-process, in-memory, live-only pipeline: runs are neither durable nor joinable, streamed
output is lost on process death, provider failures are untyped, and storage writes are
read-modify-write with no atomic commit. On Android the process is killed routinely, so these
are not edge cases — they are the common path.

The 5 highest-impact reliability themes:

1. **Durable, joinable run lifecycle** — a run registry with parent→child cascade and startup
   reconciliation, replacing per-session serialization + UI-only orphan recovery.
2. **Resumable streaming / durable event log** — per-session `seq`, catch-up reads, and a
   partial-stream checkpoint so a killed process keeps the answer it already streamed.
3. **Provider resilience** — typed failures (status/headers/Retry-After), overflow-as-compaction,
   retry hygiene, and credential refresh instead of a static captured key.
4. **Atomic durable storage** — per-part upsert + `UNIQUE(session_id,seq)`, versioned
   transactional migrations, a retention janitor, and corruption detection/recovery.
5. **Cancellation-aware, bounded runtime** — kill process trees on Stop, per-file edit locks,
   per-tool timeouts, and schema errors instead of silent `{}`.

## 2. Reliability gaps by area

### 2.1 Lifecycle

| Gap | Evidence (Lumen) | What opencode does | Suggested fix surface (Lumen) |
|---|---|---|---|
| No durable/joinable run registry; concurrent same-session prompt is **rejected**, not joined | `core/.../agent/AgentLoop.kt:132-135` | Per-session `Runner` joins/reuses the in-flight run; `Busy` is an explicit typed outcome only when it must be | Add `RunRegistry` (engine) keyed by session; `AgentLoop` returns/joins an existing handle instead of erroring — **OPEN** (per-session `Mutex` serializes today) |
| Orphan (`RUNNING`) reconciliation exists only in UI | `app/.../ChatViewModel.kt:736-774` | Run service owns recovery; UI is a projection | Move reconciliation to `AppContainer` startup in `app/.../LumenApp.kt:40-66`; have `:server`/`:cli` call the same engine hook — **DONE** (`RunRecovery.reconcile` in `:core`) |
| No background-job registry with parent→child cascade; deleting a session leaks children + snapshots | (no registry) | Job/run tree with cascading cancel/delete | `BackgroundJobRegistry` owned by engine; session delete walks children, cancels runs, prunes snapshots — **OPEN** |
| Whole-row session writes (`INSERT OR REPLACE`) cause read-modify-write races | `app/.../data/AndroidSessionStore.kt` writes; `AndroidDatabase.kt:57` | Column-targeted updates | Replace wholesale upserts with targeted `UPDATE ... SET col` per field — **DONE** (`updateSessionState`) |
| `Retry.withResilientRetry` retries connectivity **forever**, no `stalled` state, no Retry-After, non-interruptible delay | `core/.../agent/Retry.kt:109-139` | Bounded retry with typed delay + cancelable sleep | Redesign signature to accept a cancel signal + parse `Retry-After`; add `stalled` after N failures — **DONE** (retry hygiene) |
| `RunService` is `START_NOT_STICKY`, one notification, no per-session progress/deep-link | `app/.../platform/RunService.kt` | Foreground service per active run with progress + tap target | Make sticky/recoverable; per-session notification channel + deep-link `Intent` — **DONE** (per-session notifications + deep link) |

### 2.2 Streaming & persistence

| Gap | Evidence (Lumen) | What opencode does | Suggested fix surface (Lumen) |
|---|---|---|---|
| Event bus is a lossy in-memory `SharedFlow` (`replay=0`, `DROP_OLDEST`, ignored `tryEmit`), no seq/replay | `core/.../event/EventBus.kt:122-128` | Events carry ids and are persisted; consumers can resume | Add monotonic `seq` to `AgentEvent`; ring buffer + `after` read path — **OPEN** |
| No durable event log / catch-up: SSE has no `after`/`Last-Event-ID`, live-only broadcast | `server/.../LumenServer.kt:254-274`, `WireEvent.kt:23-43` | SSE supports `Last-Event-ID` replay from the store | Persist events, honor `Last-Event-ID`, add `?after=` catch-up endpoint — **OPEN** |
| Partial streamed output not durable: empty assistant persisted, deltas bus-only until step end; kill loses answer | `core/.../agent/AgentLoop.kt:218-276,325` | Durable `Started`/`Ended` boundaries; deltas live-only but the partial state is recoverable | Checkpoint partial text periodically (debounced) into the message row/part — **DONE** (partial-stream checkpoint) |
| Every structural event triggers full-session re-read + re-map → O(history) jank/memory | `app/.../ChatViewModel.kt:2320-2340,2436-2467` | Incremental part/state updates | Apply incremental patches keyed by part id; drop full remap — **OPEN** |
| Persistence is read-modify-write and rewrites all parts of a message | `app/.../data/AndroidSessionStore.kt:313-347` | Per-part upsert | Upsert by `PartId`; never rewrite unrelated parts — **DONE** (`SessionStore.updatePart`) |
| SSE subscriber overflow silent and shared (`broadcast`) | `server/.../LumenServer.kt` broadcast | Per-subscriber buffer + backpressure/close notice | Per-connection bounded channel + explicit `overflow` event |
| Live tool placeholder id differs from persisted `PartId` → row-key churn / double render | `app/.../ui/model/StepMapper.kt:436-454` | Stable ids assigned before streaming | Generate `PartId` before emitting placeholder; reuse for the persisted row |
| Non-current-session events dropped with no resync marker | `app/.../ChatViewModel.kt:2322-2323` | Consumers resync on gap | Track `seq` gap; emit a `needsResync` marker and refetch once — **OPEN** |

### 2.3 Provider

| Gap | Evidence (Lumen) | What opencode does | Suggested fix surface (Lumen) |
|---|---|---|---|
| No recovery from context overflow: chars/4 estimator, no reserved-output, overflow = fatal | `core/.../agent/Retry.kt:16`, `AgentLoop.kt:438-467` | Overflow triggers compaction and continues | Treat overflow as compaction trigger; reserve output budget; keep the run alive — **DONE** (compacts-and-retries) |
| No credential expiry/refresh; static key captured at adapter construction | `provider-openai/.../OpenAiProvider.kt:195`, `provider-anthropic/.../AnthropicProvider.kt:127`, `app/.../data/KeyStore.kt:56` | Token provider with refresh; OAuth-aware | Inject a `CredentialProvider`/supplier; re-read on 401 with refresh hook — **OPEN** (owner uses static API keys) |
| Retry decisions scrape a flattened message string; `ProviderEvent.Failure` carries no status/headers/isRetryable/Retry-After | `core/.../provider/Provider.kt:95-104` | Typed error with status/headers/retryability | Extend `Failure` with `status`, `headers`, `retryAfter`, `isRetryable` — **DONE** (`statusCode`/`retryAfterMs`/`retryable`/`contextOverflow`) |
| Retry hygiene: `maxRetries=2` vs 5; `backoffMs shl` overflows to a zero-delay hot loop after ~54 retries; `isNetwork` treats read timeout as indefinite | `core/.../query Retry.kt:69-73` | Bounded exponential w/ jitter, cap delay | Clamp shift (`coerceAtMost`), use `maxRetries=5`, distinguish transient vs indefinite — **DONE** (saturating backoff) |
| Compaction is destructive and fixed-window (`KEEP_RECENT=6`) | `core/.../agent/Compaction.kt:26,34-47` | Summarize + keep recent, non-destructive | Keep/teach summary path; make window adaptive; never drop system/tool-critical turns |
| Android always builds `OpenAiProvider`, bypassing `/messages` `/responses` routing used by `:cli` | `app/.../data/ProviderCatalogue.kt:143-153` | Routes per model to the right API | Reuse the `:cli` router in `ProviderCatalogue` |
| OpenAI SSE in-band error frames (`{"type":"error"}`, `response.failed`) ignored | `provider-openai/.../OpenAiProvider.kt:484-514` | Detect in-band errors, surface as typed failure | Parse in-band error frames into `ProviderEvent.Failure` — **DONE** |
| `max_tokens` never set → Anthropic truncates at 4096 | `core/.../agent/Wire.kt:81-97` | Always sends an output limit | Populate `max_tokens` from model metadata/context budget — **DONE** |

### 2.4 Storage

| Gap | Evidence (Lumen) | What opencode does | Suggested fix surface (Lumen) |
|---|---|---|---|
| No atomic durable commit / no `UNIQUE(session_id,seq)`; per-instance mutex only; `appendMessage` uses `INSERT OR REPLACE` (non-idempotent) | `app/.../data/AndroidDatabase.kt:57`, `AndroidSessionStore.kt:306-352` | Transactional append w/ uniqueness | Add unique index + transactional append; make append idempotent — **DONE** (`UNIQUE(session_id,seq)`, idempotent append) |
| `SessionStore.prune` never called; snapshot pruning only at run end → unbounded growth | `core/.../AgentLoop.kt:400-407` | Retention policy runs periodically | Add a retention janitor (see 2.1 registry); call `prune` on app idle/startup — **DONE** (retention janitor) |
| Shared-connection lifecycle race: `db()` returns a handle outside the lock; `close()` can close concurrently | `app/.../data/AndroidDatabase.kt:21-31,99-104` | Connection behind a scope/mutex | Hand out handle under lock; refcount closes; serialize close — **OPEN** |
| Android migrations unversioned and non-transactional | `app/.../data/AndroidDatabase.kt:41-89` | Versioned, transactional migrations | Add `user_version` gate + single transaction per migration — **DONE** (versioned transactional migrations) |
| Snapshots store whole file bodies, clock-ordered pins, no dedup | `app/.../data/AndroidSnapshotStore.kt:35-50,164-170` | Content-addressed / deduped blobs | Hash dedupe + content addressing — **OPEN** (monotonic ordering DONE; sha256 content addressing OPEN) |
| No corruption detection/recovery | — | Integrity checks / rebuild | `PRAGMA quick_check` on startup; quarantine + rebuild FTS — **DONE** (corrupt-DB quarantine) |
| `foreign_keys=ON` but no FKs; FTS partial index never repaired | `AndroidSessionStore.kt:119-143` | Real FKs + FTS rebuild path | Add FKs where ownership exists; scheduled FTS integrity/rebuild — **DONE** (FTS repair); real FKs/cascade **OPEN** |

### 2.5 Tools & runtime

| Gap | Evidence (Lumen) | What opencode does | Suggested fix surface (Lumen) |
|---|---|---|---|
| `AndroidShellExecutor` not cancellation-aware, kills only direct child; Stop hangs to 120s/600s timeout, descendants orphaned | `app/.../platform/AndroidShellExecutor.kt:160-209` vs `tools/.../HostShellExecutor.kt:54-73,121-142` | Process group kill on cancel | Use process-group/`killTree`; honor cancel immediately — **DONE** |
| proot teardown `SIGKILL`s, bypassing `--kill-on-exit` | `app/.../platform/DebianEnvironment.kt:414-421,516-533` | Graceful teardown | Signal graceful stop, then surface leftovers |
| Default-allow permission model; patterns = first command token; no wildcard rules / `external_directory` / doom-loop | `app/.../AskPolicy.kt:27-32`, `core/.../agent/AgentLoop.kt:654-660` | Structured permission rules + loop guard | Wildcard rule parser; `external_directory`; doom-loop detector — **OPEN** |
| No per-file lock for edits across sessions/subagents | `tools/.../EditTool.kt`, `WriteTool.kt` | Serialized edits | File-path mutex registry in `:tools` — **DONE** |
| `GlobTool` unbounded, uncancellable full-tree walk with unbounded accumulation | `tools/.../GlobTool.kt:58-84` | Bounded + cancelable glob | Cooperative cancel checks + max-results cap — **DONE** (bounded glob) |
| Truncated bash output discarded instead of persisted to a re-readable file | `tools/.../BashTool.kt:86` | Persist full output, return a handle | Spill to temp file; return path + preview — **OPEN** |
| Most tools ignore cancellation mid-run; no per-tool timeout | `:tools` broadly | Cancel + timeout per tool | Tool-level `withTimeout` + cancellation plumbing — **OPEN** |
| Polling file watcher with silent `MAX_FILES`/`MAX_DEPTH` truncation | `app/.../platform/WorkspaceWatcher.kt:22-23,121-158` | Native/bounded watch + notice | Surface truncation; consider native watch — **OPEN** |
| Malformed tool args silently become `{}` instead of a schema error | `core/.../agent/AgentLoop.kt:541-542` | Schema validation error fed back to model | Return typed schema error; let model retry — **DONE** |

## 3. Feature roadmap (ranked)

Ranked by Android feasibility/value × opencode parity. **Feasibility** = effort/risk on
arm64 Android with the current sandbox.

| # | Feature | opencode parity | Android feasibility / value |
|---|---|---|---|
| 1 | Multi-session tabs with concurrent streaming | Multi-session runs | High value; needs the run registry (2.1) + incremental state (2.2) first |
| 2 | Session export/import (JSON, redacted) | Session share/export | High: pure storage; redact credentials before write |
| 3 | Per-session completion/error notifications + sound + deep-link | Notify/attach | High: builds on improved `RunService` |
| 4 | `json_schema` structured-output host opt-in | Structured output | Medium: adapters exist; needs host wiring + Anthropic path |
| 5 | Background/process-death robustness | Durable runs | High: the core Android reliability win |
| 6 | Post-edit auto-formatting via the Debian sandbox | Formatter hooks | Medium: sandbox exists; run per-language formatters post-edit |
| 7 | Custom commands (`/init`, `/review`, templates) | Custom commands | Medium: prompt templating over existing loop |
| 8 | Guarded git commit/push | Guarded git | **Move from deferred to guarded in-scope**: confirm-before-write, no force-push |

Documented **non-goals** (keep): MCP, LSP, plugins, OAuth providers, share links,
git worktrees, local models. These are large surfaces with weak Android ROI today; revisit
only if owner priorities change.

## 4. Suggested sequencing

**Wave 1 — Provider resilience (unblocks long sessions).**
Typed `ProviderEvent.Failure` (status/headers/Retry-After), overflow-as-compaction + reserved
output, retry hygiene (clamp shift, `maxRetries=5`, cancelable delay, `stalled`), credential
refresh, `max_tokens`, in-band OpenAI error frames.

**Wave 2 — Durable streaming (unblocks kill-safety).**
Event `seq` + durable event log + `after`/`Last-Event-ID` catch-up, partial-stream checkpoint,
per-part upsert with stable `PartId`, incremental UI patching + resync marker.

**Wave 3 — Runtime safety.**
Cancellation-aware shell (kill tree), proot graceful teardown, per-file edit locks, per-tool
timeout + cancellation, `GlobTool` bounds, bash-output spill, schema errors for bad args.

**Wave 4 — Storage integrity.**
Retention janitor + `prune` calls, versioned transactional migrations, connection lifecycle
refcount, atomic/idempotent append + `UNIQUE(session_id,seq)`, corruption detection + FTS rebuild.

**Wave 5 — Product capability.**
Multi-session tabs / concurrent streaming, session export/import (redacted), per-session
notifications + sound + deep-link, background/process-death recovery, custom commands,
guarded git commit/push.

**Wave 6 — UI polish.**
Launcher icon + theme palettes, error-red unification, MaterialTheme wrapper, a11y
(`contentDescription`, touch targets, contrast), stringly-typed theme state, screenshot harness.

## 5. UI notes (separate audit)

- **Launcher icon**: none existed. An adaptive "terminal window" icon + cold-start
  `windowBackground` are being added.
- **Theme**: single-axis (mode only). Palettes **prism / ember / phosphor / abyss** are being added.
- **Two divergent error reds**: `LumenAlert` (`app/.../ui/LumenTokens.kt:20`) vs
  `LumenColors.danger()` (`app/.../ui/LumenChatScreen.kt:177`). Promote `danger` to a palette role.
- **No `MaterialTheme` wrapper**: Material components that read theme colors may fall back to defaults.
- **a11y**: icon-only controls lack `contentDescription` (Settings/Storage/ModelPicker/Diagnostics = 0).
  Touch targets below the app's own `touchMin=40dp`: `QuickChip`/`QuickAction`
  (`LumenChatScreen.kt:3209-3250`), Settings chip (`SettingsScreen.kt:373-392`).
- **Contrast**: `faint` text is ~2.13:1 (light) / ~3.03:1 (dark) yet used for real microcopy.
- **Duplication**: hardcoded mode list in two selectors; stringly-typed theme state.
- **Harness**: screenshot harness swallows failures (writes `name.error.txt` instead of failing).

## How to verify

`:app` cannot build on the local arm64 host; use a two-tier loop (`:app` is CI-only).

**Local JVM loop (fast, per wave):**
```bash
./gradlew :core:test :sandbox:test :tools:test :provider-openai:test :provider-anthropic:test :store-sqlite:test :server:test :cli:test
./gradlew :core:test :tools:test --tests '*Retry*' --tests '*Compaction*' --tests '*EventBus*'
```
Add/extend tests next to each change: retry overflow + Retry-After, overflow→compaction,
event `seq`/catch-up, per-part upsert idempotency, migration transactionality, shell kill-tree.

**CI / Robolectric (Android-only paths):**
```bash
./gradlew :app:testDebugUnitTest        # Robolectric: stores, watcher, RunService logic
./gradlew :app:assembleDebug            # compile gate for :app
```
Run `:app` instrumented tests (shell cancel, notifications, process-death recovery) in CI on an
emulator; keep them out of the local loop. Gate merges on `:app:assembleDebug` plus the JVM
suites above, and update `docs/PARITY.md` when a parity item here lands.
