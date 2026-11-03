# UI analysis — index

**Status:** `[ ]` open · Owner: "some parts of the UI is really bad, settings is
good tho."

**Owner's own assessment is the priority signal:** Settings is the standard the
rest should meet. Do not redesign Settings.

---

## How to judge the UI properly (the owner's suggested method)

The owner wants a **vision model** used to assess screens. This repo already has
the machinery:

1. **Screenshot harness:** `app/src/test/kotlin/dev/lumen/app/ui/ScreenshotTest.kt`
   renders every screen and palette to `app/build/screenshots/*.png`
   (~20+ named captures: `dark_assistant.png`, `empty_light.png`,
   `error_dark.png`, `light_running.png`, …).
2. **CI uploads them** as the `lumen-screenshots` artifact (see
   `.github/workflows/ci.yml`, step "upload artifact").
3. **Fetch the artifact, then look at the PNGs.** For an agent with vision, feed
   the PNGs directly; for a text-only agent, describe against the checklist
   below.

`ScreenshotSupport.kt` holds `captureDecor` / `writePng` / `meanAbsDiff`.

> Fetching artifacts needs the GitHub API and a token — the remote already has
> credentials. Pattern is documented in `docs/HANDOFF.md` §4.

## Screens, by size (lines) — the work surface

| Screen | Lines | File | Owner signal |
|---|---:|---|---|
| Chat | 3,552 | `ui/LumenChatScreen.kt` | **target** — see `CHAT-SCREEN.md` |
| Home | 1,323 | `ui/HomeScreen.kt` | target |
| GitHub | 1,040 | `ui/GitHubScreen.kt` | target; also `features/GITHUB.md` |
| Files | 1,020 | `ui/FilesScreen.kt` | target |
| Model picker | 654 | `ui/ModelPickerScreen.kt` | target; see `bugs/BUG-01` |
| **Settings** | 628 | `ui/SettingsScreen.kt` | **good — do not redesign** |
| Key / onboarding | 588 / 487 | `ui/KeyScreen.kt`, `ui/OnboardingScreen.kt` | target |
| Projects | 406 | `ui/ProjectsScreen.kt` | target |
| Terminal | 393 | `ui/TerminalScreen.kt` | target |
| Storage | 319 | `ui/StorageScreen.kt` | target |
| Diagnostics | 247 | `ui/DiagnosticsScreen.kt` | target |
| Canvas | 224 | `ui/CanvasScreen.kt` | target |

Shared language lives in `ui/LumenTokens.kt` (shapes, spacing, type, size,
elevation), `ui/LumenControls.kt`, `ui/LumenSheet.kt`. **Every fix must use these
tokens, not new literals** — token-literal drift was already a bug once.

## The review checklist (apply to every screen)

Judge each screen against these; they are the pattern Settings already gets
right:

1. **Hierarchy** — is there one obvious primary action? Is anything competing
   with it?
2. **Spacing rhythm** — consistent page gutter (`LumenSpacing.page`), consistent
   row height, no ad-hoc `dp` literals.
3. **Tap targets** — nothing below `LumenSize.touchMin` (40dp).
4. **Empty / loading / error states** — does the screen say something in every
   state, or does it render blank?
5. **Text overflow** — long model ids, paths, branch names: ellipsised, not
   crushed or wrapped mid-token.
6. **Contrast** — `dim`/`faint` roles used for genuinely secondary text only;
   check on all 6 palette variants (prism/ember/phosphor/abyss × light/dark).
7. **Consistency** — same concept, same control. Chips vs segmented vs rows must
   not drift between screens.
8. **Scroll behaviour** — pinned actions stay reachable; IME does not cover the
   composer.

## Process for a UI wave

1. Pick **one** screen (never several in parallel — they share `LumenTokens`).
2. Fetch screenshots, list concrete problems against the checklist.
3. Fix with **one writer agent**, using tokens only.
4. Add/extend the screenshot test for that screen if it is missing.
5. Push → **fetch the new screenshots** → confirm the fix actually looks right.
6. Update the screen's analysis file with what changed.

## Hard rule

`:app` UI is **CI-verified only** in this environment. A local compile proves
nothing about layout. Always look at the rendered PNG.
