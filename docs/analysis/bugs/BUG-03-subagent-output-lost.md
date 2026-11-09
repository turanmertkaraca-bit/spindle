# BUG-03 · Subagents lose their entire report

**Status:** `[x]` FIXED — `c391e20` (output loss), `347270d` (narration passed off
as a report), both **shipped in `v0.2.14`** (versionCode 19).
**Owner report:** *"the sub agents dont work right"* — a `task` call returns an
`ok` badge but the parent gets nothing, or the run burns 250k tokens and delivers
no files.

---

## What the owner saw

From the session transcript:

- `subagent 2 tools · todowrite, task ok` — the parent's `task` call succeeded.
- Then: *"Both subagents returned without text"*, and later
  *"Subagent 1 … burned ~250k tokens and returned no text and no files."*

Two children, 150k and 186k input tokens, zero bytes of report. That is not the
model failing; it is the app discarding the answer.

---

## Root cause 1 — the child's last turn is a tool call (`c391e20`)

`SubagentSpawner.spawn` read the report from **only the child's final message**:

```kotlin
val text = finalMessage.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }
    .ifBlank { "(subagent produced no text)" }
```

`maxChildSteps` is the only bound on a child. The loop returns when a step's
budget is spent **after** the model emits tool calls — so a child that hits the
step cap ends on a message whose parts are `Part.Tool`, with no `Part.Text` at
all. The child had been writing findings into earlier assistant messages; all of
it was dropped, and the parent was handed the literal string
`(subagent produced no text)`.

The same defect is why the owner's *goal* runs looked broken: with
`maxSteps = null` they ended on `MAX_TOTAL_TOOL_CALLS`, which also `break`s out
of the loop with no text.

## Root cause 2 — a short mid-run narration was accepted as "the report" (`347270d`)

The first fix fell back to the **newest** assistant text. That is still wrong for
the real-world shape. Reproduced live in this repo: an explorer ran **233k
tokens** and the parent received the single line

> "Let me check the rest of KeyStoreTest and ChatViewModel"

as its answer. A child that spends its budget on reads/greps typically narrates
between turns and ends on one such line. Returning it is indistinguishable from
the child having failed — and it is arguably worse, because it looks like a
successful report of nothing.

## Root cause 3 — a thrown child discarded its work (`c391e20`)

```kotlin
catch (e: Throwable) { return SubagentResult(childId, "subagent failed: …", false) }
```

dropped every message the child had already written. A child that read 40 files
and then hit a provider error returned one sentence and nothing else.

---

## The fix, in three parts

1. **`AgentLoop` takes a wind-down turn.** When `maxSteps`, `MAX_TOTAL_TOOL_CALLS`
   or the wall clock trips, the loop sets `wrapUpReason` and runs exactly one more
   provider turn with `tools = []` and `WRAP_UP_INSTRUCTION`, asking for (1) what
   was done, (2) what remains, (3) the next step. The child therefore *ends with
   prose*. A doom loop still breaks immediately — its error text already explains
   itself and another turn would repeat it.
2. **`SubagentSpawner.subagentReport` prefers the most SUBSTANTIAL text**, not the
   newest. `SUBSTANTIAL_REPORT_CHARS = 200` is the line between "a report" and "a
   progress narration". Above it, return the text. Below it, return the longest
   candidate **clearly labelled as incomplete** — a partial answer beats an empty
   one, but it must never be passed off as finished.
3. **A thrown child is salvaged**: its newest assistant text is attached to the
   failure result.

Also `maxChildSteps` 12 → 24 (`DEFAULT_MAX_CHILD_STEPS`). 12 was too few for
focused exploration; children were ending mid-investigation as a matter of course.

---

## Rules that follow (do not regress)

- A subagent result must always contain either the child's prose or a **specific**
  reason there is none. Never a fixed placeholder string.
- Any loop exit path must leave the run with a written assistant message.
- Never present a short narration as a report. Length is a crude but effective
  signal; the label matters more than the heuristic.
- `maxChildSteps` bounds *tool* work, so the child's report arrives on a
  wind-down turn, not on the step that tripped the budget.

## Tests

`core/src/test/kotlin/dev/spindle/core/agent/SubagentTest.kt`:

- `child that ends on a tool call still reports its last written text` — the
  original regression.
- `a child that stops on a short narration reports its substantial output
  instead` — the live reproduction above: the findings must reach the parent and
  the bare narration must not become the answer.
- `a run that exhausts its step budget asks the model to wind down and reports` —
  asserts the wind-down turn is sent **with tools disabled**.
- `maxSteps caps working turns, then spends exactly one on a wrap-up report`.
- `concurrent children never exceed the cap and none are lost` — the 7-cap.

---

## The meta-lesson from this bug

Every fix above was CI-green while the owner **kept seeing the old behaviour**,
because the device was on 0.2.12. Two of the three "it happened again" reports in
this session were that, not a new defect.

**Before believing a "still broken" report, check the installed `versionName`.**
`git log -1` on `main` says nothing about what is on the phone.

## Related

- `docs/analysis/STATUS.md` GOAL-01 — the same silent-stop defect in goal mode.
- `docs/analysis/arch/SUBAGENTS.md` — the concurrency cap.

---

## Round 2 — three MORE defects (fixed `b301743`, released `v0.2.15`)

The owner reported subagents still broken after 0.2.14. They were right: the
round-1 fixes addressed *where the text was read*, not *whether it existed*.

### The bug that hid behind the others

**The collector stored one entry per token, not per message.**

A `TextDelta` carries a single token. The evidence collector appended each delta as
its own list entry, so:

- `texts.lastOrNull()` was a fragment like `"42"`, not the child's whole finding;
- every other token landed in the "earlier output" list, where each was far too
  short to clear the 200-char substantial-report bar;
- the report therefore concluded the child had written nothing and printed a work
  summary — which is exactly what CI showed: six `fail read` lines and no prose,
  about a child that had written a full report.

Text is now accumulated **per part id** (`LinkedHashMap<String, StringBuilder>`).
Bounding is on the number of *messages*, never their length: an answer has to stay
whole to be usable.

### The other two

3. **Reasoning-only models produce no `Part.Text` at all.** `deepseek-r1`, `o1`
   and similar stream `ReasoningDelta` (verified in both provider adapters). A
   child that thought for 250k tokens then called a tool had *zero* text parts.
   The report now falls back to the reasoning, labelled as reasoning.
4. **Compaction erased the evidence before it was read.** `Compaction.compact`
   replaces head messages' parts with an empty list, so a post-hoc store walk sees
   only the retained tail — the **longer** a child worked, the **less** it
   reported. The spawner now subscribes to the child's event stream and captures
   as it arrives.

Also: **file changes never appear in the transcript at all** (they arrive as
`AgentEvent.FileEdited`), so a child that wrote the files and said nothing looked
like it had done nothing. Collected now.

### Structural change

The report logic moved out of the spawner into **`SubagentReport`**, a pure object
with 15 JVM tests. That is the actual lesson of rounds 1–3: the assembly was
trapped inline in the spawner, where none of these conditions could be exercised,
so each fix was reasoned about rather than tested. Every case above now has a test
that fails without the fix.

### Invariant now enforced

**A report always says what happened**, in this order: substantial prose → any
prose → reasoning → the work it did (files + tools) → the stop reason. Silence is
never an answer to a parent that has to decide what to do next.

### Two traps worth remembering

- **`MutableSharedFlow` with `replay = 0` drops anything emitted before a collector
  subscribes.** A `launch { flow.collect }` is a race; `start = UNDISPATCHED` is
  not. This bit the production collector *and* the test that was meant to catch it.
- **Accumulate streams by part/message id, never by delta.** A per-delta store
  looks like it works (the text is all there) while every read of "the last
  message" returns one token.

---

## Round 3 — what the chess workbench report exposed

A separate project (`chess/`) ran four subagents and filed a bug report. Two of
its findings are about **this app**, not the chess engine, and they matter more
than the chess bugs:

### The report that made correct work look fabricated

> *"I accused a subagent of fabricating its 'Stockfish agrees: cp 1047 / cp 675 /
> cp 419' claims — those claims were CORRECT, my verification was the broken part."*

The verification used a raw shell pipe (`printf ... go depth 8\nquit | stockfish`).
`quit` racing `go` makes Stockfish exit mid-search and print `depth 1 nodes 0
score cp 0` with `bestmove a2a3` **regardless of position**. The measurement was
garbage; the subagent was right.

**Fixed in `chess/uci.js`**: `search()` now rejects a degenerate result
(`nodes == 0 && depth <= 1 && cp == 0`) instead of returning it. A corrupt
measurement now raises an error naming the cause, rather than silently becoming a
data point. All three conditions are required, because a genuine `go nodes 1`
also reports 0 nodes — requiring all three avoids rejecting honest tiny budgets.

**Lesson for this app:** a tool that returns plausible garbage is worse than one
that fails. The same principle is why `RunEndPolicy` refuses silence and why
`SubagentReport` refuses an empty report.

### Subagents leaving scratch files in the repo root

A subagent wrote `.scan.js`, `.scan2.js`, `.v2.js`, `.final.js` into the
repository root. Nothing in the system prompt said where scratch work belongs, and
in a repo whose git index is damaged (`fatal: bad object HEAD`) those files are
nearly invisible.

Fixed in two places:
- `AgentLoop.FILE_ACCESS_NOTICE` (injected into **every** agent, including
  subagents) now states the rule: scratch work in a temp directory, deliverables
  under the requested name, no numbered variants.
- `AgentConfig.GENERAL_SYSTEM` additionally tells a subagent to always finish with
  its summary **as text**, because a run that ends on a tool call gives the caller
  nothing to act on — the round-1 bug, now prevented at the prompt as well as in
  the report path.

### Not fixable in this app

- **`fatal: bad object HEAD` in the downstream repo.** Its `.git/objects`
  contained proot `--link2symlink` artifacts. The repair procedure is in
  `docs/analysis/arch/ENVIRONMENT.md` §1 — it is mechanical, but it must be run in
  *that* repo, and the working tree must not be re-cloned over.
- **Thermal throttling** (265 → 18 knops on identical work). No software fix;
  fixed `--nodes` and interleaved runs are the correct methodology, which the
  project already adopted.
- **`go nodes 1000` completing only depth 1.** Real, and a search-efficiency
  problem rather than a correctness one: the engine now emits an honest `info`
  line (`depth 1 ... nodes 1000`) and a legal move at every budget ≥ 1, so
  measurements are no longer corrupt, but the engine is still weak. That is the
  project's own Phase 2 work (SEE, aspiration windows, LMR table, IID), not
  something this app should reach into.
