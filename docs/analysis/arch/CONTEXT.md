# ARCH-02 · Context waste — what burns tokens and what to do

**Status:** `[ ]` open · Owner-requested ("analyse problems that limit context
usage / waste it").

Everything below was read from the code. Each item states the mechanism, the
cost, and a fix — ranked by how much context it actually reclaims.

The operating model: **every turn re-sends the whole conversation**, so anything
that inflates a persisted message is paid for again on *every* subsequent turn.
A 5k-token tool result is not a 5k cost, it is 5k × remaining turns.

---

## Ranked findings

### 1. Tool output cap is very high: 60,000 chars ≈ 15k tokens per call
`core/.../AgentLoop.kt:73` → `maxToolOutputChars: Int = 60_000`

Every oversized tool result is clipped to 60k chars, spilled to
`.spindle/truncated/`, and the **clipped head+tail is persisted** in the
message, so it is re-sent on every later turn.

- A single `grep` or `bash` can therefore cost ~15k tokens *permanently*.
- With the default 1M-token window on `deepseek-v4.1-flash` this looks
  affordable, but with a 128k–200k model it is 8–12% of the window for **one**
  call.

**Fix:** lower the default (a realistic 12k–16k chars is plenty for a model to
act on) and, better, make the clip **adaptive**: keep head+tail small and rely
on the existing spill file, telling the model the path and that it can `read`
specific ranges. Consider a per-tool cap (bash/grep small; `read` moderate).

### 2. Compaction keeps 15,000 tokens of tail
`core/.../ContextPolicy.kt:31` → `DEFAULT_COMPACT_KEEP_TOKENS = 15_000`

Reasonable, but it is a **fixed** number regardless of the model's window. On a
1M-token model you could keep far more without compacting; on a 128k model,
keeping 15k plus a large system prompt plus tool results is a big fraction.

**Fix:** express the tail retention as a *fraction* of the model's window with a
floor and a ceiling, instead of one constant.

### 3. `AGENTS.md` is re-read and re-sent on every turn
`core/.../AgentLoop.kt:1049` → `systemPrompt()` calls `readProjectRules(cwd)`
each turn, capped at `MAX_RULES_CHARS`.

- Re-reading the file each turn is a (minor) I/O cost, but the real cost is that
  the rules become part of the system prompt **every turn**.
- A large `AGENTS.md` is therefore a permanent per-turn tax.
- Worse for subagents: every child re-reads and re-sends the same rules.

**Fix:** cache the rules per session (invalidate on a workspace revision bump,
which the app already tracks via `fileRevision`); cap harder; and consider
summarising large rule files once rather than re-sending verbatim.

### 4. ~~Subagent results are copied wholesale into the parent~~ **FIXED `88df698`**
`SubagentSpawner` returns `SubagentResult(text = ...)`, and the parent's `task`
tool result embeds that text. A child that read a lot can hand back a very large
string, which then lives in the parent forever.

**Done:** `TaskTool` now inlines at most 4,000 chars (head 3/4 + tail 1/4) and
spills the full report to `.spindle/subagents/<session>.txt`, advertising the
path in both the output and metadata. A failed spill degrades to a plain clip.
This was the biggest win for the many-subagent workflow, and it is now closed.

### 5. Reasoning/thinking parts are persisted and re-sent
Thinking is folded for display, but the raw parts remain in the store and are
part of history. Depending on provider echo behaviour this can double-count the
reasoning budget.

**Fix:** audit whether reasoning parts are included in the outgoing request when
the provider rejects/echoes them; strip where safe. (Needs care — some providers
require the thinking block to be returned.)

### 6. `.spindle/truncated` spill files accumulate
Spill is bounded per call but the directory grows across a run. Not a per-turn
token cost, but it is disk and it makes `read`-based recovery noisier.

**Fix:** prune on run end (the startup maintenance pass already clears stale
spill — confirm it covers mid-run accumulation).

---

## Quick measurement recipe

Before/after any change, measure rather than assume:

1. Run a fixed prompt with a scripted/fake provider that records
   `request.messages` (see `core/src/test/.../AgentLoopTest.kt` for the pattern)
   and print total characters per turn.
2. Or use the app's own `Usage` roll-up (`AgentEvent.UsageUpdated`) on a real
   run and compare `inputTokens` per turn for the same task.

Fix items 1 and 4 first — they have the largest and most certain effect.

---

## Do not

- Do not lower `maxToolOutputChars` so far that the model cannot see enough of a
  failing test output to fix it. 12k chars is roughly a screen and a half; the
  spill file is the escape hatch.
- Do not shrink `compactKeepTokens` without checking that compaction quality
  holds — over-compacting makes the agent forget the task and redo work, which
  costs *more* context than it saves.

---

## How opencode handles subagent results (researched, for comparison)

Useful because the owner asked. opencode does **not** fully solve this — it has
the same class of bug filed against it — but its design points are worth copying:

1. **Tool outputs are truncated by a generic wrapper** (`Tool.define`), with a
   configured byte cap (`tool_output.max_bytes`, default ~51,200 bytes). So a
   subagent result is bounded the same way as any other tool output — it is not
   exempt.
2. **Known bug (opencode #11903):** the task tool appends a `<task_metadata>`
   block *before* the generic truncation runs, so on large output the metadata
   (including `session_id`) is truncated away. Their fix direction: truncate
   first, then append metadata — never let the tail metadata be clipped.
   **Lesson for us:** our `reportPath` hint must stay *outside* the truncation,
   exactly as opencode learned. Ours is appended after the head/tail preview, so
   it survives — keep it that way.
3. **Subagent sessions are resumable** by `session_id`, so a parent can ask a
   child to continue rather than re-spawning it. That is the real context saver:
   you pay once for the child's context, then reuse it.
4. Their compaction is two-phase (prune tool outputs, then summarise), rather
   than summarising everything at once.

### The gap we still have

`SubagentSpec` in this repo has **no `sessionId` / resume parameter** — every
`task` call creates a *new* child session. So a parent cannot say "continue
where you left off"; it must re-brief a fresh child, paying the full input cost
again. The metadata we return already carries `sessionId`, so the plumbing to
build resume exists but is unused.

**Suggested follow-up (not done):** add an optional `sessionId` to
`SubagentSpec`/`TaskTool`, and when present, continue that child session instead
of creating one. This is the next real context win after the cap.
