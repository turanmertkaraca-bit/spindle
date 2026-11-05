# OWNER BRIEF — what "finished" means

This is the acceptance spec for the goal-mode run. It is the owner's own
definition of done, written down so it does not have to be re-explained.

**Status as of `b5eb7f9` / v0.2.9.** Update this file when a section lands.

The owner's words, condensed: *"a completely finished and polished bot, UI-wise
and backend-wise, optimised to be smooth as hell so it won't trigger some
people's seizures every time the AI writes something, and an actually usable app
that has all the features a phone developer will ever need."*

---

## A. Motion safety — "won't trigger seizures" (SHIP-BLOCKER)

**Current state, measured:**

- `LumenMotion` has **4 infinite/looping specs**. `pulse` is
  `infiniteRepeatable(1400ms, RepeatMode.Reverse)` and runs whenever a run is
  live — i.e. **for the whole duration of the AI writing**, which is exactly
  the complaint.
- `reduceMotion` exists and is honoured in **only 2 places**:
  `MainActivity.animatedColors` (skips the 420 ms theme cross-fade) and
  `ambient = !state.reduceMotion` (disables the pulse).
- It is **not** derived from the system setting. Android exposes
  `ANIMATOR_DURATION_SCALE`; when a user enables "Remove animations" they expect
  every app to respect it. We do not read it.

**Done means:**

1. `reduceMotion` defaults to **the system's** animation scale == 0, not `false`.
   Read `Settings.Global.ANIMATOR_DURATION_SCALE` / `TRANSITION_ANIMATION_SCALE`
   and seed the existing KeyStore value from it (an explicit user choice wins).
2. Every looping animation stops under reduce-motion. Audit:
   `LumenMotion.pulse`, `rememberInfiniteTransition` uses, the `Bloom`/water
   gradients on Home, anything with `RepeatMode.Reverse`.
3. The **streaming indicator** must not be a fast strobe. If the pulse stays at
   all, it should be a slow, low-amplitude opacity change (≥1.4 s per cycle,
   small delta), never a flash.
4. Document the rule in `docs/guide/06-ui.md`: *no animation may loop faster than
   1 cycle/second, and all loops must be disabled by reduce-motion.*

## B. Smoothness while streaming (SHIP-BLOCKER)

The transcript re-renders per token. Findings and status live in
`ui/CHAT-SCREEN.md` (5/14 fixed). Remaining work there is the decomposition
(`#2`) and the cheap `remember`s (`#10/#11/#14`).

**Done means:** scrolling stays at frame rate while a long answer streams, and a
streaming token does not re-execute the top bar, notice cards or sheets.

## C. File manager — "a phone developer will ever need"

**Current state, measured:**

- Files screen supports: browse, search, rename, delete, edit, preview, open
  externally. Good base.
- **APK install exists but is wired only for *mentions*, not from the Files
  screen**: `MainActivity` line ~353 routes `"apk" -> viewModel.installApk(p)`
  for a tapped mention. From Files, an `.apk` falls into the generic branch.
- **PDF: nothing.** No `pdf` handling anywhere in `app/src/main`.
- No mime map: file-type routing is a `substringAfterLast('.')` `when` in
  `MainActivity`, duplicated per call site.

**Done means:**

1. **One mime/action map** for file types, used by BOTH the Files screen and
   mention taps (no duplicated `when`).
2. **APK**: tapping an `.apk` in Files offers Install (existing
   `WorkspaceActions.installApk`). Never auto-installs — confirm first.
3. **PDF**: open via the system viewer with `application/pdf`, deliberately
   **no built-in PDF viewer** (owner's explicit choice). Same for any document
   type: hand off to the phone's app.
4. **Archive**: `.zip`/`.tar.gz` at least *identify*; extraction is optional.
5. Unknown types: show the type and offer "open with", never a dead tap.

## D. Terminal — "I can see the terminal the AI uses"

`TerminalScreen` exists and is wired (`MainActivity:525`) as an interactive pty.
The owner additionally wants **visibility into what the agent itself is running**.

**Done means:** from a live run you can see the commands the agent executes, not
just open your own shell. The `bash` tool already streams output; surface a
read-only "agent terminal" view (or a filter in the existing terminal) showing
the current/last commands and their output.

## E. Optional web mode — "the AI can control a web browser"

**Current state: nothing.** The only `WebView` is `CanvasScreen` (local HTML
preview, remote loads blocked). There is no browser automation of any kind.

**Done means:** an **optional, off-by-default** mode where the agent can drive a
`WebView`: navigate, read the DOM/text, click, type, screenshot. Constraints:

- Off by default; a Settings toggle, clearly labelled as powerful.
- Owned by a dedicated tool (e.g. `browser`) so it can be denied to `plan`/`explore`.
- Must respect the existing approval gate for anything state-changing.
- Local `file://` stays as-is; do not silently enable remote loads elsewhere.

This is the largest new feature here. Scope it as its own wave.

## F. GitHub — "almost everything on GitHub"

See `features/GITHUB.md` (2 API calls today) and `features/GIT-TOOL.md`.
Staged plan already written. Also the owner's stated pain: the git tool is not
good enough to actually do the work.

## G. "I added some cool ideas but they are just not executed right"

The owner has added UI ideas that did not land well. **Before restyling
anything**, fetch the current `lumen-screenshots` artifact and look at every
screen. Identify what is half-executed rather than inventing new design. Ask the
owner which specific screens they meant if it remains ambiguous — do not guess
and rebuild a whole screen.

---

## Non-negotiables

- Do not redesign **Settings**; the owner considers it good.
- Do not add a built-in PDF viewer or browser.
- No animation may strobe. See A.
- `:app` is CI-verified only (no local Robolectric). Always fetch screenshots.
- One writer per file per round (`docs/analysis/PROTOCOL.md`).

## Priority order

1. **A** (motion safety) and **B** (smoothness) — they are why the owner is
   unhappy every time the AI writes.
2. **C** (file manager) and **D** (terminal visibility) — cheap, visibly useful.
3. **F** (GitHub) — owner's most-complained-about feature.
4. **E** (web mode) — largest, do last, optional.
5. **G** — after the screenshots are reviewed with the owner.
