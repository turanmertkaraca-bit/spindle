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
