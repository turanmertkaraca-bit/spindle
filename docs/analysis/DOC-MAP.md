# DOC MAP — which files are current, and which will mislead you

There are 30+ markdown files in this repo. **Most are stale.** This page says which
ones to trust. Read this before opening anything else.

**Current HEAD: `2b16802` · version `0.2.18` · CI green on both jobs.**

---

## Tier 1 — authoritative, read in this order (5 files)

These are maintained against the current code. Start here and stop when you have
what you need.

| # | File | Read it when |
|---|---|---|
| 1 | [`HANDOFF-NEXT.md`](HANDOFF-NEXT.md) | **Always first.** Mission, §0 "check the installed versionName", §3 rules, §4b traps, §6 environment facts, §7 known traps. |
| 2 | [`STATUS.md`](STATUS.md) | **Your work queue.** Ranked tiers, each marked done/todo with the sha that shipped it. The "Done (do not redo)" section is as important as the open items. |
| 3 | [`OWNER-BRIEF.md`](OWNER-BRIEF.md) | **The acceptance spec.** What "finished" means, in the owner's words, measured against the code. §"Still open" is the backlog. §"Non-negotiables" must be obeyed. Waves 1–4 tables are the execution log with shas. |
| 4 | [`PROTOCOL.md`](PROTOCOL.md) | Before spawning any subagent. One writer per file per round, ≤7 concurrent, frozen contracts. |
| 5 | [`README.md`](README.md) | The index. Also lists the two files most likely to save you a wasted round. |

## Tier 2 — authoritative for ONE area, read on demand

Do not read these up front. Open the one matching your task.

| Area | File | Why it matters |
|---|---|---|
| Subagents | [`bugs/BUG-03-subagent-output-lost.md`](bugs/BUG-03-subagent-output-lost.md) | **The single most valuable file in this repo.** Five distinct bugs in one code path, four rounds to find them all. If you touch `SubagentSpawner`/`SubagentReport`/`TaskTool`, read it in full first. |
| Agent loop / budgets | [`bugs/BUG-04-budget-stop-reported-as-crash.md`](bugs/BUG-04-budget-stop-reported-as-crash.md) | A spent budget used to `throw` and be reported as a crash. Also documents that **green tests had blessed the bug**. |
| Loop internals | [`../guide/01-agent-loop.md`](../guide/01-agent-loop.md) | Step/budget/compaction/subagent flow. |
| Context burn | [`arch/CONTEXT.md`](arch/CONTEXT.md) | What costs context per turn; ranked reductions. |
| Subagent cap | [`arch/SUBAGENTS.md`](arch/SUBAGENTS.md) | The 7-concurrent design and why it is process-wide. |
| UI, motion, scroll | [`../guide/06-ui.md`](../guide/06-ui.md) | **Two hard rules live here:** "Motion: the safety rule" (no loop > 1 Hz) and "The transcript scroll rule" (never scroll from inside a `layoutInfo` collector — that was an ANR). |
| Chat screen perf | [`ui/CHAT-SCREEN.md`](ui/CHAT-SCREEN.md), [`ui/README.md`](ui/README.md) | Per-screen findings; 5/14 fixed. |
| Storage / projects | [`../guide/02-storage.md`](../guide/02-storage.md) | SQL, migrations, leases, snapshots, project folders. |
| Tools | [`../guide/03-tools.md`](../guide/03-tools.md) | Path containment, approval, output limits. |
| Shell / proot | [`../guide/04-shell-and-proot.md`](../guide/04-shell-and-proot.md) | Guest shell, ulimits, admission control. |
| Background runs | [`../guide/05-background-service.md`](../guide/05-background-service.md) | Foreground service, wake lock, `START_NOT_STICKY`. |
| Conventions | [`../guide/08-conventions.md`](../guide/08-conventions.md) | Invariants and hard-won rules. **Read once, early.** |
| Building / testing | [`../guide/07-testing.md`](../guide/07-testing.md) | Why `:app` is CI-only here, and the exact commands. |
| GitHub / git tool | [`features/GITHUB.md`](features/GITHUB.md), [`features/GIT-TOOL.md`](features/GIT-TOOL.md) | Gap analysis for §F. |
| Bugs (fixed) | `bugs/BUG-01-models-show-free.md`, `bugs/BUG-02-large-tool-output-crash.md` | Historical root causes; useful patterns, no action needed. |
| Environment / build / git repair | [`arch/ENVIRONMENT.md`](arch/ENVIRONMENT.md) | **§1** loose-object repair, **§6** why local Gradle fails, **§7** repairing a *downstream* repo's object store (verified recipe). |

---

## Tier 3 — STALE. Read as history, never as fact.

These were accurate once. Each names the sha it was written against, which is far
behind `2b16802`. **Do not act on their claims without checking the code.**

| File | Stamped against | Why it is dangerous |
|---|---|---|
| `../HANDOFF.md` | HEAD `c0dd166`, v0.2.8 | Says "READ FIRST" and gives a v0.2.8 build recipe. Superseded entirely by `HANDOFF-NEXT.md`. Its §5 invariants list is still worth reading. |
| `../PROJECT-STATUS.md` | HEAD `f80644e` | "Live board" from Run F. `STATUS.md` replaced it. |
| `../AGENTS-INDEX.md` | HEAD `4ff37c0` | Agent inventory from Run C. |
| `../audit/*.md` (10 files) | pinned `d17dbd2` | The original audit. **Many findings are already fixed.** Read the reasons before "fixing" anything from here. |
| `../SPEC.md`, `../PLAN.md` | 2026-10-06 | Original design intent, not current behaviour. |
| `../CAPABILITIES.md`, `../PARITY.md`, `../RELIABILITY-PARITY.md` | 2026-10-06…10 | Comparison notes vs opencode; v0.2.2 era. |
| `../UI-POLISH.md`, `../PERF-RE-SWEEP.md` | 2026-10-05…14 | Superseded by `ui/CHAT-SCREEN.md` and the Wave tables. |
| `../PROVIDERS.md` | 2026-10-06 | Provider notes predate the 429/retry work. |

`../guide/*` pages are stamped `4ff37c0` (older than HEAD) — **but they are the
only per-subsystem map**, and every change since has been additive and described in
`STATUS.md`. Treat the structure as current and re-check any specific claim.

---

## The three ways doc-reading wastes a session here

1. **Reading `docs/HANDOFF.md` first.** It is the loudest file (says "READ FIRST")
   and the most out of date. `docs/analysis/HANDOFF-NEXT.md` is the real entry point.
2. **Trusting a stamp.** A file that says "CI green on this sha" was green *then*.
   Verify against `git log -1` and the code.
3. **Reading everything.** Tier 1 is ~900 lines. That is enough to start. Read a
   Tier 2 file only when your task touches that area.

---

## When you finish work

Update the file for the area you touched, with the sha, and mark the item in
`STATUS.md`. **An out-of-date analysis file is worse than none** — it sends the next
agent down a path that no longer exists. If you cannot keep a file accurate, say so
inside it rather than letting it quietly rot.
