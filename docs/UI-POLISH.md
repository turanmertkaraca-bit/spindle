# Lumen — UI polish checklist (final pass)

Deferred by the user ("you're gonna polish this at the very end"). Function work
comes first; this file is the running list so nothing is lost.

## From the user (verbatim intent, paraphrased)

- **Composer / input box feels bad to use.** The text field, send button and
  attachment affordance need real polish: comfortable padding, clear focus
  state, a send button that reads as primary, the keyboard/IME transition, and
  hint text that does not collide with the `image` chip. Make it feel native
  and calm, matching the AMOLED/prism chat.
  **DONE** — function-only pass at `aaac126` (see "Landed" below).
- **Top-bar buttons look bad.** `fork`, `files`, `shell` (and `back`) render as
  bare monospace text. Give the app bar real affordances: consistent touch
  targets, subtle icons or a grouped segmented control, and alignment that does
  not crowd the title.
  **DONE** — function-only pass at `aaac126` (see "Landed" below).

## Landed (`aaac126`, function-only)

- Composer + app-bar treatment: padding/focus/send-stop/attachment layout, IME
  insets, primary send affordance; app-bar buttons grouped with real touch
  targets and title truncation.
- Session tags UI (add/remove/clear + any-of filter bar and row chips) and
  models.dev catalogue enrichment shipped alongside.
- This was a function-first pass; a final visual/motion sweep is still parked
  (items 3–6 below).

## Landed (`ea2e38e` + `189b695`, polish pass)

Items 3–6 below are now done:

- **Shared tokens** (`LumenTokens.kt`): `LumenShapes` (corner radii) and
  `LumenElevation` are the single source of truth for cards, panels, chips,
  fields and bubbles; the AMOLED/prism look is unchanged, only the literals are
  named.
- **Calm states** (`LumenState.kt`): one `StateHint` empty/loading/error
  placeholder used across Home, Files, Terminal, Canvas, Settings, Diagnostics
  and Storage, so no surface renders as a blank void (error tints it warm).
- **Unified motion** (`LumenMotion.kt`): one expand spring + fade tween + press
  spring + run pulse shared app-wide; still deliberately **no**
  `animateContentSize` on growing, unbounded text. `AnimationSmoothnessTest`
  steps the frame clock and pins that the unified specs actually animate.
- **Screenshot sweep** (`ScreenshotTest.kt`): every screen in light **and** dark
  (chat, Home, Files + editor + empty/loading, Terminal + empty, Canvas + empty,
  Settings, Diagnostics + empty, Storage + empty, Key), uploaded as the
  `lumen-screenshots` CI artifact. `189b695` was the follow-up Canvas import
  compile fix found by CI.

## Refinement pass 1 (`fa9ff1a` + `dab4f39` + `1422eb1`)

Two follow-up passes tightened the earlier polish. Pass 1:

- **Chat timeline (`fa9ff1a`).** Tool cards collapse to a single line
  (tool + key arg + status) with plain-language failure text and cleaned
  output; thinking renders as a subtle single line; the assistant hash label is
  replaced by the **agent name**; prose drops monospace (kept only for
  code/paths); calmer spacing/elevation and no-bounce motion.
  `StepMapper` gained tool-detail projection and `StepMapperToolDetailTest`.
- **Shared screen chrome (`dab4f39`).** One shared `LumenTopBar` /
  `LumenBarAction` language with 44dp targets and an overflow for secondary
  verbs; clearer titles, primary actions and destructive wording; a properly
  centered Files empty state; quieter borders and roomier layout. Applied across
  Home, Files, Terminal, Canvas, Settings, Diagnostics, Storage and Key.
- **Follow-up fix (`1422eb1`).** Restored the FilesScreen new-file/new-folder
  actions (local `newKind` setter after `FilesHeader` removal) and simplified
  `LumenBarAction` modifier composition.

## Refinement pass 2 (`47fd30b`, lighter / calmer)

- Title: *lighter, calmer transcript and controls* — **no behavior or signature
  changes**.
- Quiet surface fills instead of hard borders; slimmer status spine; trimmed
  card/transcript rhythm and fainter pills/tags.
- Low-key composer secondary controls with **send as the sole primary**; subtle
  press feedback on New chat.

## Refinement pass 3 (`47d1ec4` + fixes, 0.1.2)

The 0.1.2 app wave revisited the highest-traffic surfaces:

- **Composer.** One rounded input card with a bottom control row (attach,
  vision/eye, build/plan chips, model chip, key/theme, send), a compact usage
  line (`≈ N new · next $X · ctx N`), and the new-content cue now reads
  `↓ latest`.
- **Home.** A status strip (provider·model, sandbox, budget, GitHub) plus quick
  controls (ask-before-tools switch, Files/Terminal).
- **Key screen.** Provider cards with the default model, Show/Hide, Paste, a real
  "Test key" probe, inline error, and non-destructive editing.
- **GitHub.** A new PAT-connect screen (`GitHubScreen`) with connect/validate,
  status, and clone/open controls, reached from Settings and a Home status pill;
  the token is sealed on-device and never placed in a URL or log.

## Refinement pass 4 (`749df0d` + `e4c1464`, 0.1.3)

The UI/UX fix wave after 0.1.2:

- **Readable boundaries.** Added an opaque `outline` colour role plus a `row`
  shape token; every card, row and input now draws a minimal 1dp gray rounded
  boundary, and the dark surface / light background are raised so panels read as
  distinct layers instead of one flat field.
- **Connected tool runs.** Tool cards and thinking rows are joined into visual
  runs rather than a scatter of isolated cards: `UiStep.linkedAbove` (set by
  `StepMapper.linkRuns`) plus a thin 1dp rail with tight 3dp row spacing.
- **No raw tool JSON.** Tool detail never falls back to `argumentsJson`;
  question/prompt/query and the other friendly keys are surfaced instead, with
  summary/body falling back to plain text or the tool name.
- **Streaming tail pinned.** The newest line stays glued to the bottom when the
  usage meter, todo/changes/ask cards or the IME shrink the viewport (layout-
  keyed re-pin, not just content growth).
- **API-key screen redesigned.** Compact provider radio rows, a visible field
  boundary, and unified inline test feedback.
- **In-chat quick settings sheet.** Provider, model, theme, budget and
  ask-before-tools plus key/full-settings/close links, rendered over the
  transcript, so opening settings inside a chat no longer jumps to the full
  Settings page.

## Refinement pass 5 (`baac664` + `4a2f248` + `6ae8bdd`, 0.1.3)

The non-chat rework plus the live model picker:

- **Non-chat UI rework (`baac664`).** New shared tokens: a compact type scale
  (`LumenType`), touch/icon sizes (`LumenSize`), and a page inset
  (`LumenSpacing.page`). `StateHint` is larger with a nested droplet. A gradient
  New-chat button and card-style session rows on Home; cleaner status pills,
  search field and touch targets. Friendlier Key header, clearer provider cards,
  more prominent field. Files breadcrumb/rows/editor tidied. Settings/GitHub/
  Storage/Diagnostics use grouped cards and consistent captions; Terminal/Canvas
  get a status dot and consistent chrome. **Every pre-existing test tag is
  preserved.**
- **Full-screen model picker (`4a2f248` + `6ae8bdd`).** `ModelPickerScreen`
  (route `"models"`) lists the live catalogue with provider chips, context/cost
  badges, a selected check, an in-flight spinner and Refresh; reachable from the
  composer model chip and Settings ("Browse all models"). Screenshot frames
  `models_light`/`models_dark` were added.

## Verification sweep

Items 3–6 above are fully landed. The Robolectric screenshot sweep renders every
screen in light **and** dark — including GitHub (connected, connect + status), the
in-chat **quick settings sheet** (pass 4) and, as of pass 5, the full-screen
**model picker** (`models_light`/`models_dark`) — for a total of **~91 frames
(65 per-screen shots + 26 animation-smoothness frames)**; each entry is a PNG, or
a `.error.txt` if a render fails, so the sweep never fails the build
(`build/screenshots`, uploaded as the `lumen-screenshots` CI artifact). The last
CI-verified sweep for 0.1.2 reported **0 render errors**; the newer frames
(quick settings, model picker) are re-verified in CI — `:app` is CI-only (no
Android SDK in the dev guest).

## Already addressed (for reference, do not regress)

- Removed the left spine rail.
- Tool runs are compact, distinct cards, collapsed by default.
- Sent image attachments render a thumbnail.
- Markdown rendering, wider bubbles, no per-message internal scroll
  (expanded tool output still scrolls).
- Reading-friendly scroll with a `↓ new` cue + light haptic.
- Merged think→answer, subagent call tree, live todo board, changes card with
  per-file revert, usage meter, build/plan mode chips.

## Polish pass scope

1. Composer: spacing, focus ring, send/stop button, attachment chip layout,
   IME insets, disabled states. — **landed `aaac126`**.
2. App bar: button treatment (icons/labels), tap targets, title truncation,
   back affordance. — **landed `aaac126`**.
3. Consistent corner radii + elevation across cards (tool, changes, todo,
   permission, question, canvas, files). — **landed `ea2e38e`** (`LumenTokens`).
4. Empty/loading/error states across every screen. — **landed `ea2e38e`**
   (`LumenState`).
5. Motion: unify spring specs; verify no jitter via `AnimationSmoothnessTest`.
   — **landed `ea2e38e`** (`LumenMotion`; the test steps the frame clock).
6. Screenshot sweep: every screen, light + dark, read them all. — **landed
   `ea2e38e` + `189b695`**; the sweep now covers every screen in both themes
   (uploaded as the `lumen-screenshots` artifact).
