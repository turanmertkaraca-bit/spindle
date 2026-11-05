# `docs/analysis/` — the working brain for the next sessions

These files exist so a fresh agent (or a subagent) can start **informed** instead
of re-deriving the codebase and burning context.

## Read in this order

| # | File | Purpose |
|---|---|---|
| 1 | [`HANDOFF-NEXT.md`](HANDOFF-NEXT.md) | **Entry point.** Mission, rules, environment, traps. |
| 2 | [`STATUS.md`](STATUS.md) | **The ranked work queue.** Start your next task here. |
| 3 | [`OWNER-BRIEF.md`](OWNER-BRIEF.md) | **What "finished" means.** The owner's acceptance spec, measured against the code. |
| 4 | [`PROTOCOL.md`](PROTOCOL.md) | How to run subagent waves (1 writer per file; ≤7 at a time). |

## Then, by task

| Area | File |
|---|---|
| Bugs (with confirmed root causes) | [`bugs/BUG-01-models-show-free.md`](bugs/BUG-01-models-show-free.md) |
| UI (per-screen, + how to judge with screenshots/VLM) | [`ui/README.md`](ui/README.md), [`ui/CHAT-SCREEN.md`](ui/CHAT-SCREEN.md) |
| Architecture (subagents, context, environment) | [`arch/SUBAGENTS.md`](arch/SUBAGENTS.md), [`arch/CONTEXT.md`](arch/CONTEXT.md), [`arch/ENVIRONMENT.md`](arch/ENVIRONMENT.md) |
| Features (GitHub, git tool) | [`features/GITHUB.md`](features/GITHUB.md), [`features/GIT-TOOL.md`](features/GIT-TOOL.md) |

Deeper background lives in `docs/guide/` (per-subsystem developer guide) and
`docs/HANDOFF.md` (long-form history + invariants).

---

## The rule that keeps this useful

**When you finish work, update the file for that area** — what you found, what
you changed, the commit sha. Mark the item in `STATUS.md`.

An out-of-date analysis file is worse than none: it sends the next agent down a
path that no longer exists. If you cannot keep it accurate, say so in the file.

Files here are **not** a transcript of what anyone did. They are the current best
understanding of the app, written for whoever picks it up next.
