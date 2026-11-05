# STATUS — the ranked work queue

**Start here after `HANDOFF-NEXT.md`.** This is the ordered list of real work.
Each item names the analysis file that has the detail. Mark items done with the
commit sha and a one-line result.

Legend: `[ ]` todo · `[~]` in progress · `[x]` done · `[—]` deferred

> **The full acceptance spec is [`OWNER-BRIEF.md`](OWNER-BRIEF.md).** It states
> what "finished" means in the owner's terms. This queue is the ordered
> implementation of it.

---

## Tier 0 — ship-blockers (owner's core complaint)

### [ ] MOTION-01 · Streaming animation must not strobe; honour system reduce-motion
**Detail:** `OWNER-BRIEF.md` §A
`LumenMotion.pulse` loops forever (1.4 s, reverse) for the whole duration of an
AI run — exactly when the owner says it is seizure-adjacent. `reduceMotion` is
honoured in only 2 places and is NOT derived from the system animation scale.

### [ ] PERF-01 · Smoothness while streaming
**Detail:** `ui/CHAT-SCREEN.md` (#2 decomposition, #10/#11/#14 cheap remembers).

---

## Tier 1 — visible bugs the owner reported

### [x] BUG-01 · Every model shows "free" — FIXED `c4408c6`
**Detail:** `docs/analysis/bugs/BUG-01-models-show-free.md`
Done: `pricingKnown` flag + `modelCostLabel`; unknown now renders "—".
Follow-up: confirm providers actually return `pricing` (`/models`).

### [x] BUG-02 · Large tool output crashed the app — FIXED `57c3395`
**Detail:** `docs/analysis/bugs/BUG-02-large-tool-output-crash.md`
A 1000-result glob (~80k chars) was fully laid out as ~20 Text blocks per pass.
`RawOutputText` had no cap though `WindowedText` beside it did. This is also why
other chats showed "aborted" — the process died and RunRecovery swept them.

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

## Tier 3b — phone-developer file manager

### [ ] FILES-01 · Unify file-type routing; APK install + PDF hand-off from Files
**Detail:** `OWNER-BRIEF.md` §C
`.apk` only routes for mentions, not from the Files screen. PDF has no handling.
Type routing is a duplicated `when` in `MainActivity`.

### [ ] TERM-01 · Show the agent's own commands in the terminal view
**Detail:** `OWNER-BRIEF.md` §D

## Tier 4 — new capability

### [ ] WEB-01 · Optional browser-control mode
**Detail:** `OWNER-BRIEF.md` §E
Nothing exists today; the only WebView is the local-only Canvas. Off by default,
own tool, denied to plan/explore, behind the approval gate.

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
