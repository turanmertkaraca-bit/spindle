# HANDOFF — read this first

**You are picking up a live project, not starting one.** This file is the entry
point. Everything you need is in this repo; nothing here is guesswork.

- **Repo:** `turanmertkaraca-bit/spindle` (Android app `dev.lumen.app`, engine
  codename "spindle")
- **Working dir:** `spindle/` (the repo root — you are already in it)
- **Branch:** `main` — **in sync with origin, clean tree**
- **Version:** `0.2.8` (`versionCode 13`)
- **Published release:** https://github.com/turanmertkaraca-bit/spindle/releases/tag/v0.2.8
- **State as of this handoff:** all stability/perf waves (Runs C–F) are
  **complete, CI-green, and released**. The app installs and runs.

---

## 1. What this project is

A native Android coding-agent app. The agent engine (`:core`) is pure Kotlin
with **no Android dependency**, so it runs and is tested on a plain JVM. The
Compose UI (`:app`) is a thin consumer of the event stream — there is no HTTP
server between them and no second state model.

Read `docs/analysis/OWNER-BRIEF.md` (what "finished" means, measured against the
code) and `docs/analysis/STATUS.md` (the ordered queue).

`docs/guide/README.md` is the **per-subsystem developer guide**, one file per
area, written against this exact HEAD.

---

## 2. Your mission (owner's words, condensed)

> Turn this app into a **completely finished, easy-to-use app** — all features
> present, the UI fixed where it is bad, the tools made genuinely useful, and
> bug-fix waves run with subagents.

Specifically the owner has said:

1. **The UI has bad parts.** Settings is fine; much of the rest is not. Use the
   screenshot harness + a vision model to judge each screen, then fix it.
2. **The GitHub story is weak.** "Almost everything on GitHub" should work
   (issues, PRs, releases, actions…), and the agent's `git` tool is not good
   enough to do real work.
3. **A concrete bug: every model shows "free"** even when it is not. Root cause
   is already diagnosed — see `docs/analysis/bugs/BUG-01-models-show-free.md`.
4. **Context is being wasted.** Analyse and fix anything that burns context.
5. **Subagents must be capped at 7 concurrent.** There is currently **no cap at
   all** — see `docs/analysis/arch/SUBAGENTS.md`.

Do not invent a different mission. Do not rewrite the app from scratch. Work in
**waves**, each wave shippable.

---

## 3. How to work — non-negotiable

These rules exist because breaking them has burned this project before.

### 3.1 Verification

- **`:core`, `:tools`, `:sandbox`, `:store-sqlite` tests run locally** and are
  authoritative. Run them before every commit.
- **`:app` Robolectric tests do NOT run in this environment** (no aarch64
  conscrypt native). `:app` **compiles** locally; its behaviour is verified
  **only by CI**. A local `:app` build is a compile check, never a pass.
- **Always push and wait for CI to go green on the sha** before claiming done.
  Five real regressions shipped during earlier waves by trusting a local `:app`
  build.
- Fetch the `lumen-screenshots` CI artifact and **look at the PNGs** for any UI
  change.

Exact commands: `docs/guide/07-testing.md`.

### 3.2 Subagents

You have **unlimited subagent use**, but a hard **7 concurrent** cap is wanted
(and must be implemented — it does not exist yet). Rules:

- **One writer per file per round.** Parallel writers to the same file corrupt
  each other. Sequence them.
- Read-only `explore` agents can run freely in parallel — they cannot collide.
- Give every writer a **frozen interface contract** (exact signatures) plus the
  **test tags it must preserve**.
- Point each agent at the specific `docs/analysis/**` file for its area instead
  of letting it re-derive the codebase.

Full protocol: `docs/analysis/PROTOCOL.md`.

### 3.3 Never regress

`docs/HANDOFF.md` §5 lists invariants that must not break. The ones most easily
broken by well-meaning edits:

- No tool part may stay `PENDING` after a run ends.
- Cancellation must never be reported as a timeout.
- `START_NOT_STICKY`; a failed `startForeground` tears the run down.
- Path arguments always go through `resolveInsideCwd`.
- The guest `ulimit` guards go on the guest **script**, not on proot.
- Identity is the id, never the normalised name.

---

## 4. Documentation map

| Path | What it is | Update when |
|---|---|---|
| `docs/HANDOFF.md` | Long-form session history, build/CI recipe, invariants | At the end of a wave |
| `docs/guide/*.md` | **Per-subsystem developer guide** (9 files) | When you change a subsystem's behaviour |
| `docs/PROJECT-STATUS.md` | The live board (what's done / left) | Every wave |
| `docs/analysis/bugs/*.md` | **Confirmed bugs with root cause + fix sketch** | When you fix one — mark it FIXED with the sha |
| `docs/analysis/ui/*.md` | Per-screen UI assessment + known problems | After a UI wave |
| `docs/analysis/arch/*.md` | Context/subagent/architecture analysis | When the structure changes |
| `docs/analysis/features/*.md` | Feature-by-feature gap analysis (GitHub etc.) | When a feature lands |
| `docs/audit/*.md` | The **original** audit, pinned at `d17dbd2` | **Read-only, mostly stale** — many findings are already fixed |

**Rule:** if you learn something a future agent would otherwise re-derive, write
it into one of these files. That is the entire point of them.

---

## 5. First 30 minutes — suggested

1. Read this file, then `docs/guide/README.md`, then
   `docs/analysis/STATUS.md` (the ranked work queue).
2. Run the local JVM tests to confirm the baseline is green:
   see `docs/guide/07-testing.md`.
3. Pick the **top item** from `docs/analysis/STATUS.md`.
4. Fix it with a subagent or inline; verify per §3.1; update the analysis file
   and `PROJECT-STATUS.md`; commit; push; wait for CI.

**Do not** start by "exploring the codebase". It is already mapped. Start from
the queue.

---

## 6. Environment facts (verified, don't re-derive)

- JDK 17 at `/usr/lib/jvm/java-17-openjdk-arm64`.
- Android SDK at `/opt/android-sdk` (platforms 34/35).
- Build needs `-Pandroid.aapt2FromMavenOverride=/opt/aapt2bin/aapt2`.
- **The HTTPS proxy port changes between sessions.** Read it from the live
  `$https_proxy` env var; do not hardcode a remembered port. A stale port shows
  up as Gradle failing to download a random jar.
- The SDK's `aapt`/`aapt2` binaries are x86 and **crash with `Illegal
  instruction`** on this aarch64 host. To inspect an APK, use Python
  (`zipfile`) or the pure-Java `apksig` jar in the Gradle cache — not the SDK
  tools.
- Git remote already has credentials; `git push` works without extra setup.

---

## 7. Known traps

- `.git/objects/**` may contain **broken symlinks** (proot link2symlink
  artifacts) pointing at a deleted path. If git says `bad object HEAD`, the
  fix is in `docs/analysis/arch/ENVIRONMENT.md` — do not `git clone` over the
  repo, you will lose local refs.
- `projects/playground/` also contains an **unrelated repo**
  (`fnportandroid`). Ignore it. Work only in `spindle/`.
- The `dist/` directory is gitignored; release APKs are published by CI, not
  committed.
