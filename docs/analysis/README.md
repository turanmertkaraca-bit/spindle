# `docs/analysis/` — the working brain for the next sessions

These files exist so a fresh agent (or a subagent) can start **informed** instead
of re-deriving the codebase and burning context.

## Read in this order

> **Confused about which of the 30+ docs to trust? Read [`DOC-MAP.md`](DOC-MAP.md)
> first.** It marks every file authoritative or stale, with the sha each was
> written against. `docs/HANDOFF.md` in particular says "READ FIRST" and is the
> most out-of-date file in the repo.

| # | File | Purpose |
|---|---|---|
| 0 | [`DOC-MAP.md`](DOC-MAP.md) | **Which docs are current, which will mislead you.** |
| 1 | [`HANDOFF-NEXT.md`](HANDOFF-NEXT.md) | **Entry point.** Mission, rules, environment, traps. |
| 2 | [`STATUS.md`](STATUS.md) | **The ranked work queue.** Start your next task here. |
| 3 | [`OWNER-BRIEF.md`](OWNER-BRIEF.md) | **What "finished" means.** The owner's acceptance spec, measured against the code. |
| 4 | [`PROTOCOL.md`](PROTOCOL.md) | How to run subagent waves (1 writer per file; ≤7 at a time). |

## Then, by task

| Area | File |
|---|---|
| Bugs (confirmed root cause + fix sha) | `bugs/BUG-01` models-show-free · `bugs/BUG-02` large-tool-output-crash · **`bugs/BUG-03` subagent-output-lost** · **`bugs/BUG-04` budget-stop-reported-as-crash** |
| UI (per-screen, + how to judge screenshots) | [`ui/README.md`](ui/README.md), [`ui/CHAT-SCREEN.md`](ui/CHAT-SCREEN.md), [`../guide/06-ui.md`](../guide/06-ui.md) |
| Architecture (subagents, context, environment) | [`arch/SUBAGENTS.md`](arch/SUBAGENTS.md), [`arch/CONTEXT.md`](arch/CONTEXT.md), **`arch/ENVIRONMENT.md` §7** (repairing a downstream repo's object store) |
| Features (GitHub, git tool) | [`features/GITHUB.md`](features/GITHUB.md), [`features/GIT-TOOL.md`](features/GIT-TOOL.md) |

### The two files most likely to save you a wasted round

- **`bugs/BUG-03`** — subagents lost their report in **five** separate ways over
  four rounds. Read it before touching `SubagentSpawner` / `SubagentReport`.
- **`arch/ENVIRONMENT.md` §6–7** — local Gradle is unreliable on this host, and
  `projects/playground/` holds a *second* repo whose object store needed repair.
  Both traps are documented with verified recipes; do not re-derive them.

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
