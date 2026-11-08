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
