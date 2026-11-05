# BUG-02 · Large tool output crashed the app (reported from real use)

**Status:** `[x]` **FIXED in `57c3395`** · CI green.
Reported by the owner on v0.2.9 while running a session in a *different* chat.

## What the owner saw

1. The agent (plan mode) ran a broad `glob` over a big working directory and it
   reported hitting the 1000-result cap (paths under `lynkr/`,
   `native/target/`, `vendor/Butterscotch/`).
2. **The app crashed** — while the owner was looking at another chat.
3. On relaunch the affected chat showed `grep`, `read`, `websearch` all as
   **`failed / aborted`**.

Those last two are the *same event*, which is what made this confusing: the
process died mid-run, and `RunRecovery` swept the stale session on next launch,
rewriting every open tool part to `ERROR "aborted"`. Nothing was actually
written or lost — the aborted rows are the recovery sweep, not tool failures.

## Root cause (renderer, not the tool)

`app/src/main/kotlin/dev/lumen/app/ui/LumenChatScreen.kt` → `RawOutputText`.

It chunked the **entire** output string into 4,000-char `Text` composables with
**no cap**:

```kotlin
Column(modifier.testTag(tag)) {
    for (chunk in textChunks(text, TEXT_CHUNK_CHARS)) {   // whole string
        Text(chunk, ...)
    }
}
```

`WindowedText` — the composable *right next to it* — already capped at
`LITERAL_MAX_CHARS` (8,000) behind a "show all" affordance. `RawOutputText` was
simply missed.

Cost: a 1,000-path glob is ~80,000 chars → ~20 full text layouts for a single
card, inside a `LazyColumn` item, re-run on every streaming pass. The card is
already height-bounded (`heightIn(max = 240.dp)`), so every one of those layouts
past the visible window was pure, unbounded cost.

`GlobTool`'s cap (`Limits.GLOB_MAX_RESULTS = 1000`, `Support.kt:30`) is what
decides the size, but the tool itself is fine — it caps during the walk.

## Fix (`57c3395`)

`RawOutputText` now matches `WindowedText`'s contract:

- ≤ 8,000 chars → one `Text` (unchanged, keeps the single tag for tests).
- otherwise → head of 8,000 chars, then
  `…[N chars hidden] · show all` (tag `tool-output-show-all`), which reveals the
  full text inside a 320dp scrollable box with a "show less" toggle
  (tag `tool-output-show-less`).

Regression test: a 1,000-path output shows the head, hides the tail, reveals it
on tap.

## Rules this establishes

1. **Any composable rendering tool-controlled text must be bounded.** Tool
   output size is driven by repo contents; a workspace can be arbitrarily large.
2. **`WindowedText` and `RawOutputText` must stay in sync** — they were
   duplicates that drifted, and the drift was the bug. Consider extracting one
   shared windowed-text primitive.
3. When a chat shows rows as `aborted`, check whether the process died
   (`RunRecovery` sweep) before investigating the individual tools.

## Still open (related, not fixed)

- The same unbounded-layout risk applies to any *other* place that renders raw
  text without a window. Grep for `textChunks(` and confirm each call site is
  bounded.
- `GlobTool` returning 1,000 paths is still a context bomb even when rendered
  safely (see `arch/CONTEXT.md`); the cap could be lowered or made summarised
  ("1,000 matches, showing 50; narrow the pattern").
