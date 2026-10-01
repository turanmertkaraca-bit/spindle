# Lumen — UI polish checklist (final pass)

Deferred by the user ("you're gonna polish this at the very end"). Function work
comes first; this file is the running list so nothing is lost.

## From the user (verbatim intent, paraphrased)

- **Composer / input box feels bad to use.** The text field, send button and
  attachment affordance need real polish: comfortable padding, clear focus
  state, a send button that reads as primary, the keyboard/IME transition, and
  hint text that does not collide with the `image` chip. Make it feel native
  and calm, matching the AMOLED/prism chat.
- **Top-bar buttons look bad.** `fork`, `files`, `shell` (and `back`) render as
  bare monospace text. Give the app bar real affordances: consistent touch
  targets, subtle icons or a grouped segmented control, and alignment that
  does not crowd the title.

## Already addressed (for reference, do not regress)

- Removed the left spine rail.
- Tool runs are compact, distinct cards, collapsed by default.
- Sent image attachments render a thumbnail.
- Markdown rendering, wider bubbles, no per-message internal scroll
  (expanded tool output still scrolls).
- Reading-friendly scroll with a `↓ new` cue + light haptic.
- Merged think→answer, subagent call tree, live todo board, changes card with
  per-file revert, usage meter, build/plan mode chips.

## Polish pass scope (when reached)

1. Composer: spacing, focus ring, send/stop button, attachment chip layout,
   IME insets, disabled states.
2. App bar: button treatment (icons/labels), tap targets, title truncation,
   back affordance.
3. Consistent corner radii + elevation across cards (tool, changes, todo,
   permission, question, canvas, files).
4. Empty/loading/error states across every screen.
5. Motion: unify spring specs; verify no jitter via `AnimationSmoothnessTest`.
6. Screenshot sweep: every screen, light + dark, read them all.
