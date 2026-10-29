# Documentation drift audit

Checked `README.md` (all 125 lines) and the header block of `docs/AGENTS-INDEX.md`
(lines 1-40) against the working tree at HEAD `d17dbd2` (`docs: update
AGENTS-INDEX to the verified end-of-run state`, 1 commit past `d9e880a` and 34
commits past tag `v0.2.7`). Reality was taken from `app/build.gradle.kts`,
`settings.gradle.kts`, `.github/workflows/ci.yml`, `.github/workflows/live.yml`,
the `tools/` registry, the tag list (7 tags, newest `v0.2.7`) and the test-file
count. Every row below is backed by a command actually run, not by reading
source.

## Drift table

| # | Doc | Claim | Reality | Fix |
|---|---|---|---|---|
| 1 | `README.md:33` | Status line reads **`0.2.2`** (`versionCode 7`) | `app/build.gradle.kts:22-23` has `versionCode = 13`, `versionName = "0.2.8"` — six releases and six codes behind | Rewrite to **`0.2.8`** (`versionCode 13`) |
| 2 | `README.md:33-39` | "Backend is feature-complete" implied at the 0.2.2 wave | HEAD is 34 commits past `v0.2.7`; no tag exists for the shipped version | Say "0.2.8, 34 commits past `v0.2.7`" so the reader knows where they stand |
| 3 | git tags vs. docs | — (implicit) a tag should mark the version the docs advertise | `git tag -l "v0.2.8"` → MISSING; newest tag is `v0.2.7` | Cut `v0.2.8` at HEAD, or state "untagged" explicitly in the README status line |
| 4 | `AGENTS-INDEX.md:8-9` | **Last updated:** run E, HEAD `d9e880a`; verified against `d9e880a` | HEAD is `d17dbd2` — exactly 1 commit ahead (`git rev-list --count d9e880a..HEAD` = 1), and that commit only touches `docs/AGENTS-INDEX.md` | Update both stamps to `d17dbd2`; the "CI green on this sha" claim needs re-verifying for the new sha |
| 5 | `AGENTS-INDEX.md:8-9` vs. `PROJECT-STATUS.md:7` | AGENTS-INDEX header says verified at `d9e880a` | `docs/PROJECT-STATUS.md` already says "Verified against HEAD `d17dbd2` on branch `main`" — the two status docs disagree on the same HEAD | Bump AGENTS-INDEX to `d17dbd2` so the whole docs set shares one stamp |
| 6 | `README.md:19-26` | Modules table lists 6 modules: `:core`, both providers, `:store-sqlite`, `:tools`, `:cli` | `settings.gradle.kts` has **9** `include` lines: those six **plus `:sandbox`, `:server`, `:app`** | Add `:sandbox` and `:server` rows; `:app` is only mentioned in prose at line 34 |
| 7 | `README.md:25` | `:tools` provides "read, write, edit, bash, apply_patch, glob, grep, webfetch, websearch, todowrite, question, task, skill, external-directory" (13 named) | `DefaultTools.kt:13-29` registers **14** — the listed 13 **plus `GitTool()`** | Add `git` to the `:tools` row |
| 8 | `README.md:36` | "14 tools" | 14 in the default registry (`DefaultTools`), and `GitTool` is additionally wired in `ChatViewModel.kt:3641` | Keep "14" but name the set, or say "14 default + git in `:app`" to remove the ambiguity with row 7 |
| 9 | `README.md:3-5, 11` | "A native, **UI-agnostic** agent backend… with **no HTTP server**, no process bridge" | `:server` is a real module (`settings.gradle.kts:32`) whose `LumenServer.kt` imports `com.sun.net.httpserver.HttpServer` | Reword: the *agent loop* has no server; an optional `:server` module exists |
| 10 | `README.md:41` | "The **0.1.2** wave adds…" | This section sits under a 0.2.x status line, and `:app` ships at 0.2.8 / code 13 | Prefix historical waves with their version range, or move them under a "History" heading |
| 11 | `README.md:66` | "The **0.1.3** UI/UX fix wave adds…" | Same: 0.1.3-era prose presented as current under a 0.2.x header | Same fix as row 10 |
| 12 | `README.md:83-88` | "the last CI-verified sweep reported 0 render errors" | `.github/workflows/ci.yml` does upload `lumen-screenshots` (line 68), so the mechanism exists, but no sha is cited and HEAD `d17dbd2` has no recorded sweep | Cite the sha + run id of the last green sweep, or drop the claim |
| 13 | `README.md:118` | `live.yml` "gates this behind `workflow_dispatch`" and "never runs on push/PR" | Confirmed: `live.yml:3` "MANUAL ONLY" and `on:` → `workflow_dispatch:` only (line 12) | No change — verified accurate |

Rows 13 is a control (verified-true claim); rows 1-3 and 6-11 are real drift.

## Highest-leverage doc fix

Fix the `README.md:33` status line first — it is the only sentence most readers
(and most agents) read before deciding whether the repo matches their
expectations, and it is off by six releases and six version codes (`0.2.2` /
code 7 advertised, `0.2.8` / code 13 on disk). Because the same paragraph then
enumerates the feature set and the two following sections describe "the 0.1.2
wave" and "the 0.1.3 wave" as if they were current, a reader cannot tell which
capabilities actually exist at HEAD; correcting the version and demoting those
waves to a dated history block fixes the credibility of the whole document in one
edit. Pair it with row 6 (9 modules, not 6) so the two most-scanned structures in
the README — the status line and the module table — are both true. The
AGENTS-INDEX stamp (rows 4-5) is the next cheapest win: it is a two-token edit,
and it currently contradicts `PROJECT-STATUS.md`, which already claims `d17dbd2`.
