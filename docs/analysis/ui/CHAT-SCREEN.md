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
