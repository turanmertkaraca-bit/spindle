# UI · Chat screen (`LumenChatScreen.kt`, 3,552 lines)

**Status:** `[~]` partially fixed · 5 of 14 audited findings done.

The chat surface is the app's centre and its largest file. It renders: a
`LazyColumn` transcript of grouped/deduped `MessageRow`s (keyed by step id, with
linked tool runs drawn as a rail), the top bar, a stack of notice/card
composables above the composer (`ErrorNotice`, `HintNotice`, `UndoNotice`,
`ChangesCard`, `TodoBoard`, `UsageMeter`, `AskCard`), `Composer`, and overlays
(`FilePeekOverlay`, `QuickSettingsSheet`, `ModelPickerSheet`) plus a "latest"
scroll cue.

## Already fixed (do not redo)

Commit `7a3f0c9`:

- **pulse lambda identity** — remembered so `MessageRow`/`TodoBoard` params are
  stable and lazy-item skipping works.
- **per-row `Modifier` chain** — hoisted into
  `remember(step.id, linkedAbove, colors)`.
- **duplicate tail-pin effects** — the two `scrollBy` effects merged into one
  `snapshotFlow { listState.layoutInfo }` collector, so the bottom gap is
  corrected once per frame, not twice.
- **`forceOpenId` key** — dropped redundant `steps`.
- **tail-pin effect key** — dropped the growing `body.length`.

## Still open (deferred, each needs a decision)

Source: `docs/audit/07-perf-ui.md` rows 2 and 7–14.

| # | Finding | Why it was deferred |
|---|---|---|
| 2 | The whole body is **one restart scope** — a streaming token re-executes top bar, all notice cards and both sheets | Real fix = split into sub-composables with their own scopes. Large refactor of a 3.5k-line file; needs care not to break test tags. **Highest value remaining.** |
| 7 | `remember(p.path, steps, touchedPaths) { onBacklinks(p.path) }` re-runs on every streaming token | `onBacklinks` is host-supplied; needs a contract decision (should it be called only when the sheet is open?) |
| 8 | Inline lambdas allocated per recomposition passed as params (`onAttach`, quick-settings `onOpenModel`, 4 ModelPicker callbacks) | Fix is mechanical but touches host call sites in `MainActivity`/`ChatViewModel` |
| 9 | `rememberLauncherForActivityResult` closes over `context`/`onAttachImage` with **no keys** — a rebound host delivers images to a stale lambda | Correct fix is `rememberUpdatedState`; needs verification of host rebinding behaviour |
| 10, 11, 14 | `running`, `tailTolerancePx`, `pending`, `lastAssistantId` computed in the body (O(n) per token) | Cheap `remember`/`derivedStateOf` wins; bundle them together |
| 12 | `followTail` read in the main body, so scroll changes recompose the whole screen | Should move into a tiny composable that reads it itself |
| 13 | Sheets composed unconditionally, gated only by `visible` | Verify both early-return before doing work; if they do, it is fine |

## Suggested order (best value first)

1. **#2 — decomposition.** Split `LumenChatScreen` into `ChatTopBar`,
   `ChatNotices`, `ChatTranscript`, `ChatComposer` sub-composables. This is the
   one change that stops per-token cost scaling with screen furniture.
   **Preserve every test tag** (`agent-build`, `agent-goal`, `timeline`,
   `composer-*`, `run-<id>`, `quick-*`, …) — the Robolectric suite asserts them.
2. **#10/#11/#14 — bundle the cheap `remember`s.**
3. **#8 — stabilise the inline lambdas.**
4. **#7/#9/#12/#13 — only after the above, and only with a stated contract.**

## Constraints

- **Do not change the visual design** unless the owner asks; this file is about
  correctness and cost, not restyling.
- Every `Modifier` must come from `LumenTokens` roles, not fresh literals.
- `:app` is CI-only for behaviour. Compile locally, then push and read CI.

## Tests that gate this file

`app/src/test/kotlin/dev/lumen/app/ui/LumenChatScreenTest.kt` (~935 lines) —
asserts tags, the composer, rows, regenerate/edit affordances, quick settings.
`AgentModeChipTest.kt`, `AskCardTest.kt`, `ComposerCompletionTest.kt`,
`FileReferenceTest.kt`, `AnimationSmoothnessTest.kt` also drive this screen.

**Never delete or rename a tag without updating those tests in the same commit.**

---

## PERF-02 · Opening a chat feels slow (OWNER-REPORTED `4daa70f`)

**Status:** `[ ]` open · Owner: *"loading the chat is taking a bit of time for some
reason more than before it feels bad … maybe not immediately just note it."*
Not yet measured — the paths below are the ones worth measuring **first**.

### Suspect 1 (most likely): FTS repair walks the whole database on store init

`data/AndroidSessionStore.kt:136-158` — `ensureFts()` runs on the first
`shared.lease { }` of a store, i.e. on the first query after the process starts or
after the store is reopened. It calls `repairFts()` (`:167-183`), which does:

```kotlin
val messageCount = countRows("messages")     // COUNT(*) over ALL messages
val indexed      = countRows("message_fts")
if (indexed == messageCount) return
val searchable = allMessages().filter { ... }  // <-- reads EVERY message row
```

`allMessages()` is a full table read — every message, every part, every session in
the database, not just the one being opened. On an install that has accumulated
hundreds of sessions (and this app keeps 100 sessions plus 24 h of subagent
children), that is the single largest fixed cost on the "first chat open" path, and
it grew with history, which matches "more than before".

**Fix sketch:** the equality check is a full scan for a *repair heuristic*. Either
(a) store a `fts_version`/`fts_indexed_at` marker in prefs and skip the walk unless
a cheap signal says it is stale, or (b) do the count comparison and only fall back to
`allMessages()` when counts differ (they currently differ → full read on **every**
launch where the index is behind by even one row), or (c) move the repair off the
session-open path entirely — kick it off from startup maintenance
(`runStartupMaintenance`) instead of lazily inside the first read.

### Suspect 2: the session open path is serial and does 4+ store reads

`ChatViewModel.openSession` (`:2053-2094`): `store.session` → `store.messages` →
`enrich(...)` → `loadTodos` → `seedUsage`, each `locked { }` on one connection, then
`enrich` may read every child session's messages (`childSteps`, `:3744-3754`, up to
`MAX_CHILD_LOADS = 24` children × full transcript). Nothing renders until all of it
finishes. `reconcileOpenSession` (`:3497`) repeats the same shape on resume.

**Fix sketch:** render the parent timeline first and enrich asynchronously; or bound
child loading to children that are actually referenced by an *expanded* row.

### Suspect 3: `enrich` on every load, not just when a subagent row is on screen

`enrich` (`:3713-3736`) maps the whole step list and, for any step with
`childIds`/`childId`, reads child transcripts. A session with many subagent steps
pays that on every open even if the user never expands one.

### How to measure before fixing

The app already has `PerfSampler`/`MeasureMs`. Add a temporary timing around
`openSession`'s five steps and log it via `diag(...)`, then open a chat with a long
history and read Diagnostics. **Do not optimise on the strength of this note alone**
— `store.messages` for one session may well be the cheap part and the FTS walk the
expensive one; the point of writing it down is that the candidates are enumerated.
