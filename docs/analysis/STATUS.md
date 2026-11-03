# STATUS — the ranked work queue

**Start here after `HANDOFF-NEXT.md`.** This is the ordered list of real work.
Each item names the analysis file that has the detail. Mark items done with the
commit sha and a one-line result.

Legend: `[ ]` todo · `[~]` in progress · `[x]` done · `[—]` deferred

---

## Tier 1 — visible bugs the owner reported

### [x] BUG-01 · Every model shows "free" — FIXED `c4408c6`
**Detail:** `docs/analysis/bugs/BUG-01-models-show-free.md`
Done: `pricingKnown` flag + `modelCostLabel`; unknown now renders "—".
Follow-up: confirm providers actually return `pricing` (`/models`).

### [ ] FEAT-01 · GitHub surface is far too thin
**Detail:** `docs/analysis/features/GITHUB.md`
`GitHubClient` exposes exactly **2** calls (user, repo). The `git` tool has 13
ops but no issues/PRs/releases/actions. The owner wants "almost everything on
GitHub".

### [ ] FEAT-02 · The agent's `git` tool is not good enough to do real work
**Detail:** `docs/analysis/features/GIT-TOOL.md`
Same area as FEAT-01; the owner explicitly called this out.

---

## Tier 2 — the two structural issues

### [ ] ARCH-01 · No subagent concurrency cap (owner wants 7)
**Detail:** `docs/analysis/arch/SUBAGENTS.md`
There is currently **no limit at all** on concurrent subagents. The owner asked
for 7. Needs a semaphore at the spawn boundary.

### [ ] ARCH-02 · Context waste
**Detail:** `docs/analysis/arch/CONTEXT.md`
Inventory of what burns context per turn and the ranked reductions.

---

## Tier 3 — UI quality

### [ ] UI-01 · Screen-by-screen UI assessment and fixes
**Detail:** `docs/analysis/ui/README.md` (index) + one file per screen
Settings is considered good by the owner; the rest is the target. Use the
screenshot harness + a vision model to judge, then fix.

### [ ] UI-02 · `LumenChatScreen` is 3,552 lines and still has known perf issues
**Detail:** `docs/analysis/ui/CHAT-SCREEN.md`
Five of the fourteen audited findings are fixed; the rest are listed with why
they were deferred.

---

## Tier 4 — polish and correctness sweep

### [ ] SWEEP-01 · Remaining audit findings (deferred, need decisions)
**Detail:** `docs/audit/00-INVENTORY.md` + `docs/PROJECT-STATUS.md` (Run F)
Four findings were deliberately deferred because each needs a behavioural
decision, not a surgical patch. Read the reasons before "fixing" them.

---

## Done (do not redo)

- Runs C–F: resource limits, thermal hysteresis, UI recomposition fixes, docs,
  audit-tail triage, duplicate project names.
- `/goal` mode.
- Released as **v0.2.8**.

---

## How to add an item

Append it under the right tier with: an id, one-line description, a pointer to
an analysis file. If it is non-trivial, write the analysis file first — an item
without a detail file forces the next agent to start from scratch, which is the
exact problem these docs exist to prevent.
