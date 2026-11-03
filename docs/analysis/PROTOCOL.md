# PROTOCOL — how to run subagent waves without corrupting the repo

The owner wants **unlimited subagent use, max 7 concurrent**, to keep the parent
context small. This file is the operating procedure. It exists because parallel
writers to the same file silently destroy each other's work.

> **Note:** the 7-concurrent cap is **not implemented in the app** yet
> (`docs/analysis/arch/SUBAGENTS.md`). This file is about *your* discipline as
> the orchestrating agent; create at most 7 at a time regardless.

---

## 1. The one rule that matters

**One writer per file per round.**

Path collisions are the failure mode. Two agents editing
`ui/LumenChatScreen.kt` concurrently produce a merge you cannot reason about.
Before spawning any writer, list the files it will touch and ensure no other
in-flight writer shares them. If they must overlap, **sequence** them.

Read-only `explore` agents cannot collide — run those freely in parallel.

---

## 2. Choosing the agent

| Goal | Agent | Parallelism |
|---|---|---|
| Answer a question about the code | `explore` | many at once |
| Fix code in a file | `general` | at most one per file |
| Judge a screenshot | `explore` (+ vision if available) | many |
| Run a build/test | do it yourself | — |

---

## 3. Writing the subagent prompt (this is the whole game)

A vague prompt makes the agent re-derive the codebase, which is exactly the
context waste we are trying to avoid. Every writer prompt must contain:

1. **The exact files to touch** (and which *not* to touch).
2. **A frozen interface contract** — exact function/param names and types it must
   produce, so two agents can interoperate without negotiation.
3. **The test tags to preserve** (for UI work) — copy the list from the screen's
   analysis file.
4. **A pointer to the analysis file** for its area, e.g.
   *"Read `docs/analysis/bugs/BUG-01-models-show-free.md` first; the root cause
   is already diagnosed — do not re-investigate."*
5. **The verification step** it must run
   (`./gradlew :core:test`, or "compiles + will be CI-verified").
6. **The doc it must update** when finished — which analysis file, and what to
   write (finding + sha).

Example skeleton:

```
You are fixing <id> in the spindle Android project at <repo>.

READ FIRST (do not re-investigate):
- docs/analysis/bugs/BUG-01-models-show-free.md   <- root cause is there
- docs/guide/03-tools.md                          <- conventions

SCOPE — you may edit ONLY:
- app/src/main/kotlin/dev/lumen/app/ui/ModelPickerScreen.kt
Do NOT touch any other file.

CONTRACT (must compile against this exactly):
  internal fun modelCostLabel(inputPricePerM: Double?, outputPricePerM: Double?): String
  returns "0.28/0.42" | "free" | "—" (unknown)

PRESERVE these test tags: model-option-<id>, model-star-<ref>, ...

VERIFY: :app compiles; :app tests are CI-only, so state "compiles" only.

WHEN DONE: append a short "Done" section to
docs/analysis/bugs/BUG-01-models-show-free.md with the sha and what changed.
```

---

## 4. Wave shape

```
WAVE
 1. Orchestrator reads docs/analysis/STATUS.md, picks item(s)
 2. Spawn up to 7 read-only explorers if research is needed  -> collect findings
 3. Orchestrator writes the plan into the analysis file (so it survives a crash)
 4. Spawn writers, ONE PER FILE, in dependency order
 5. Orchestrator integrates, runs local JVM tests
 6. Push -> wait for CI green on the sha
 7. Update analysis files + PROJECT-STATUS.md, commit, push
```

**After step 3, the plan exists on disk.** If your context dies, the next agent
resumes from the file rather than restarting. That is the point of all of this.

---

## 5. Context hygiene for you (the orchestrator)

- Delegate **research** to `explore` agents and ask for **short reports with
  file:line citations**, not code dumps.
- Never paste large files into your own context to "check something" — ask an
  agent, or `grep` for the specific symbol.
- Read the analysis file, not the implementation, when you need to understand
  an area.
- When a subagent returns, record the *conclusion* in the analysis file, not the
  full transcript.

---

## 6. Definition of done for a wave

- [ ] Local JVM tests green (`:core :tools :sandbox :store-sqlite`).
- [ ] CI green on the pushed sha (both jobs).
- [ ] For UI work: fetched the new `lumen-screenshots` and looked at them.
- [ ] The relevant `docs/analysis/**` file updated with finding + sha.
- [ ] `docs/PROJECT-STATUS.md` updated.
- [ ] Committed and pushed; working tree clean.
