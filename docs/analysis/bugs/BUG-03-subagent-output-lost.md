# BUG-03 · Subagents lose their entire report

**Status:** `[x]` FIXED in `c391e20` (output) + this wave (salvage on error)
**Owner report:** *"the sub agents dont work right"* — a `task` call returns an
`ok` badge but the parent gets nothing, or the run burns 250k tokens and
delivers no files.

## What the owner saw

From the session transcript:

- `subagent 2 tools · todowrite, task ok` — the parent's `task` call succeeded.
- Then: *"Both subagents returned without text"*, and later
  *"Subagent 1 … burned ~250k tokens and returned no text and no files."*

Two children, 150k and 186k input tokens, zero bytes of report. That is not the
model failing; it is the app discarding the answer.

## Root cause 1 — the child's last turn is a tool call (fixed `c391e20`)

`SubagentSpawner.spawn` read the report from **only the child's final message**:

```kotlin
val text = finalMessage.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }
    .ifBlank { "(subagent produced no text)" }
```

`maxChildSteps = 12` is the *only* bound on a child. The loop returns when a
step's budget is spent **after** the model emits tool calls — so a child that
hits step 12 ends on a message whose parts are `Part.Tool`, with no `Part.Text`
at all. The child had been writing findings into earlier assistant messages; all
of it was dropped, and the parent was handed the literal string
`(subagent produced no text)`.

The same defect is why the owner's *goal* runs looked broken: with
`maxSteps = null` they ended on `MAX_TOTAL_TOOL_CALLS`, which also `break`s out
of the loop with no text.

## Root cause 2 — a thrown child discarded its work too

`catch (e: Throwable) { return SubagentResult(childId, "subagent failed: …", false) }`
dropped every message the child had already written. A child that read 40 files
and then hit a provider error returned one sentence and nothing else.

## The fix

1. **`AgentLoop` takes a wind-down turn.** When `maxSteps`, `MAX_TOTAL_TOOL_CALLS`
   or the wall clock trips, the loop sets `wrapUpReason` and runs exactly one
   more provider turn with `tools = []` and `WRAP_UP_INSTRUCTION`, asking for
   (1) what was done, (2) what remains, (3) the next step. The user always gets
   prose. A doom loop still breaks immediately — its error text already explains
   itself, and another turn would just repeat it.
2. **`SubagentSpawner.subagentReport`** prefers the final message's text, then
   falls back to the most recent earlier assistant text (clearly labelled as such
   so the parent knows it is not a tidy final answer), then to an explicit reason.
3. **`SubagentSpawner` salvages on throw**: the newest assistant text is attached
   to the error result instead of discarded.

## Rules that follow

- A subagent result must always contain either the child's prose or a specific
  reason there is none. Never a fixed placeholder string.
- Any loop exit path must leave the run with a written assistant message.
- `maxChildSteps` bounds *tool* work, so the child's report arrives on a
  wind-down turn, not on the step that tripped the budget.

## Tests

`core/src/test/kotlin/dev/spindle/core/agent/SubagentTest.kt`:

- `child that ends on a tool call still reports its last written text` — the
  exact regression, asserting the finding text reaches the parent.
- `a run that exhausts its step budget asks the model to wind down and reports` —
  asserts the wind-down turn is sent **with tools disabled**.
- `maxSteps caps working turns, then spends exactly one on a wrap-up report`.

## Related

- `docs/analysis/STATUS.md` GOAL-01 — the same silent-stop defect in goal mode.
- `docs/analysis/arch/SUBAGENTS.md` — the concurrency cap, fixed in `c391e20`.
