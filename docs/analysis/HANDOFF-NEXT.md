# HANDOFF — read this first

**You are picking up a live project, not starting one.** This file is the entry
point. Everything you need is in this repo; nothing here is guesswork.

- **Repo:** `turanmertkaraca-bit/spindle` (Android app `dev.lumen.app`, engine
  codename "spindle")
- **Working dir:** `spindle/` (the repo root — you are already in it)
- **Branch:** `main` — in sync with origin, clean tree
- **Version:** `0.2.20` (`versionCode 25`)
- **Published release:** https://github.com/turanmertkaraca-bit/spindle/releases/tag/v0.2.20
- **Last CI-green sha:** `4daa70f` (both jobs green; release workflow green too)
- **Installed on the owner's device:** the owner was installing **0.2.20** at the end
  of the last session — **verify it** (§0). 0.2.20 is the first release containing
  the goal-mode change, the prompt-caching fix and the UI type alignment.

---

## 0. Read this before you conclude anything is broken

**Check the installed `versionName` first.** During the last session, three
separate "it happened again" reports were the owner still running **0.2.12**
while the fixes were sitting CI-green on `main`. `git log -1` says nothing about
what is on the phone. `Settings` shows the version; so does
`aapt`-free inspection of the installed APK.

The second half of those reports were genuine. Assume neither.

---

## 1. What this project is

A native Android coding-agent app. The agent engine (`:core`) is pure Kotlin
with **no Android dependency**, so it runs and is tested on a plain JVM. The
Compose UI (`:app`) is a thin consumer of the event stream — there is no HTTP
server between them and no second state model.

Read `docs/analysis/OWNER-BRIEF.md` (what "finished" means, measured against the
code, with shas) and `docs/analysis/STATUS.md` (the ordered queue + execution log).

**There are 30+ markdown files here and most are stale.** Read
[`DOC-MAP.md`](DOC-MAP.md) — it marks every one authoritative or stale, with the sha
each was written against. Note in particular that `docs/HANDOFF.md` says "READ
FIRST" in its title and is the most out-of-date file in the repo; this file
supersedes it.

`docs/guide/README.md` is the **per-subsystem developer guide**, one file per
area.

---

## 2. State at this handoff

**The single most important thing to know: the subagent report path was broken in
FIVE different ways across four rounds of fixes.** Read
`docs/analysis/bugs/BUG-03-subagent-output-lost.md` in full before touching
`SubagentSpawner.kt`, `SubagentReport.kt` or `TaskTool.kt`. The pattern that kept
recurring: *a tool returning plausible garbage instead of failing* — the owner's
own separate project accused a subagent of fabricating data when the verification
harness was the broken part.

Shipped and CI-verified:

- **Motion safety** (`40bae2c`): no animation loops faster than 1 Hz, the
  streaming pulse is 2 s at 0.62→1.0, and reduce-motion is derived from Android's
  own animator/transition duration scale.
- **Goal mode** (`0481ef7`): renews its budget window while todos remain instead
  of stopping at one 60-minute boundary.
- **Subagents** (`c391e20`, `347270d`): a child that ends on a tool call, or on a
  mid-run narration, now reports its substantial findings; child step budget 24;
  concurrency capped at 7.
- **No silent stops** (`2e854fd`, `495c83d`): `EventBus` retries a refused
  publish so a terminal `StateChanged` cannot be dropped; `stop(reason)` says why;
  a watchdog reports a run that vanishes; `RunEndPolicy` is pure and tested.
- **Storage** (`7084dd2`): drill into any category, see the biggest folders and
  files, delete one entry precisely.
- **Files** (`144d108`): "New project (bind agent)" creates a real project root so
  the agent cannot wander into sibling projects.
- **Composer** (`efcea8f`): the context limit is a labelled bar inside the card.
- **Freeze/teleport** (`5e90bfc`): the transcript no longer scrolls from inside
  its own `layoutInfo` collector (that was a main-thread ANR).
- **Budget stops** (`69474f5`, BUG-04): a spent cost/input-token budget used to
  **throw** from outside the provider try/catch, so it was reported as a crash with
  no wind-down report. It now returns a `StopReason`.
- **Tool call bounded by the run's wall clock** (`72e35df`, `58d2a8f`): a tool with
  `timeoutMs: 0` (e.g. `bash`) could outlive the whole run — the "got stuck on a
  task" cause. Clamped to the remaining budget; Settings now states the 30-min limit.
- **Four more subagent defects** (`973314c`, `c616516`): tools counted twice, a
  retried attempt's text concatenated into the report, children getting a fixed
  30-minute window regardless of parent mode, and a wind-down turn with no prose
  leaving an **empty** answer.

Open work is listed at the bottom of `OWNER-BRIEF.md` §"Still open" and in
`STATUS.md` tiers.

**The detailed "what shipped / what is next / what was deliberately not done"
record for the last session is `bugs/WAVE-BUGHUNT.md`, last section. Read it after
`STATUS.md`.** It names each defect with its sha, and lists the verified gaps with
file:line — including the 20 GB storage-controller work and the chat-open latency
report (PERF-02), which are the two biggest open items.

---

## 3. How to work — non-negotiable

### 3.1 Verification

- **`:core`, `:tools`, `:sandbox`, `:store-sqlite` tests run locally** and are
  authoritative. Run them before every commit **if the host lets you**.
- **`:app` Robolectric tests do NOT run in this environment.** `:app` **compiles**
  locally; its behaviour is verified **only by CI**.
- **Always push and wait for CI green on the sha** before claiming done. Many real
  regressions shipped during earlier waves by trusting a local `:app` build.
- Fetch the `lumen-screenshots` CI artifact and **look at the PNGs** for any UI
  change. Reading it found a real bug (`ede1984`): the smoothness artefact
  reported an infinite hitch on a smooth animation.

**CI is also the build server.** On this host the Gradle daemon frequently dies
during startup before running a single task:

- the shell carries a hard `ulimit -v 2097152` (2 GiB of *virtual* address space)
  and `prlimit` cannot raise it;
- the box is memory-overcommitted (`Committed_AS` ~116–125 GB against a
  `CommitLimit` of 7.6 GB).

Do not "fix" this by committing memory tuning to `gradle.properties` — **CI reads
that file too**, and `MaxMetaspaceSize=224m` with in-process Kotlin made
`:core:compileTestKotlin` die with `OutOfMemoryError: Metaspace` (`c391e20`).
Keep sandbox tuning outside the repo.

### 3.2 Subagents

The app's own `task` tool is now fixed and released, so subagents are usable.
Rules still apply:

- **One writer per file per round.** Parallel writers to the same file corrupt
  each other.
- Read-only `explore` agents can run freely in parallel.
- Give every writer a **frozen interface contract** plus the **test tags** it must
  preserve.
- Point each agent at the specific `docs/analysis/**` file for its area instead of
  letting it re-derive the codebase.
- **Check the returned report is real.** A subagent that answers with one
  narration line is a bug to report, not a result to trust.

Full protocol: `docs/analysis/PROTOCOL.md`.

### 3.3 Never regress

`docs/HANDOFF.md` §5 lists invariants. The ones most easily broken:

- No tool part may stay `PENDING` after a run ends.
- Cancellation must never be reported as a timeout.
- A run must **never** end without telling the user (`RunEndPolicy`).
- `START_NOT_STICKY`; a failed `startForeground` tears the run down.
- Path arguments always go through `resolveInsideCwd`.
- The guest `ulimit` guards go on the guest **script**, not on proot.
- Identity is the id, never the normalised name.
- **Never scroll from inside a `layoutInfo` collector** (see FREEZE-01).

---

## 4. Documentation map

| Path | What it is | Update when |
|---|---|---|
| `docs/HANDOFF.md` | Long-form session history, build/CI recipe, invariants | At the end of a wave |
| `docs/guide/*.md` | Per-subsystem developer guide (9 files) | When you change a subsystem |
| `docs/PROJECT-STATUS.md` | The live board | Every wave |
| `docs/analysis/bugs/*.md` | Confirmed bugs with root cause + fix sketch | When you fix one — mark FIXED with the sha |
| `docs/analysis/ui/*.md` | Per-screen UI assessment | After a UI wave |
| `docs/analysis/arch/*.md` | Context/subagent/architecture analysis | When the structure changes |
| `docs/analysis/features/*.md` | Feature gap analysis (GitHub etc.) | When a feature lands |
| `docs/audit/*.md` | Original audit, pinned at `d17dbd2` | Read-only, mostly stale |

**Rule:** if you learn something a future agent would otherwise re-derive, write
it into one of these files. That is the entire point of them.

---

## 4b. The two traps that cost the most time last session

1. **Two clocks, one fake, must not be mixed.** `withTimeout`/`delay` measure the
   *coroutine* clock, which is **virtual time under `runTest`**. The wall-clock
   budget is measured with an injectable `clock`. Enforcing that budget with
   `withTimeoutOrNull` cancelled every tool call instantly in six tests. Do not
   attempt it again — the fix is deadline clamping (`AgentLoop.executeTool`'s
   `deadlineCeilingMs`).
2. **A test helper must never return a stale value.** `RetryResetTest` failed twice
   because its poll helper returned whatever it last saw on timeout, so a late
   background rebuild read as a product bug. Use `awaitCondition { }` — observe the
   condition or fail naming what you saw.

## 5. First 30 minutes — suggested

1. Read this file, then `docs/analysis/STATUS.md` (ranked queue + execution log),
   then `OWNER-BRIEF.md` §"Still open".
2. Confirm what the device is running. If it is older than the latest release,
   release first — otherwise you will chase already-fixed bugs.
3. Pick the top open item. Write the plan into its analysis file **before**
   spawning writers, so it survives a crash.
4. Verify per §3.1; update the analysis file and `PROJECT-STATUS.md`; commit; push;
   wait for CI.

**Do not** start by "exploring the codebase". It is already mapped.

---

## 6. Environment facts (verified, don't re-derive)

- JDK 17 at `/usr/lib/jvm/java-17-openjdk-arm64`.
- Android SDK at `/opt/android-sdk` (platforms 34/35).
- Build needs `-Pandroid.aapt2FromMavenOverride=/opt/aapt2bin/aapt2`.
- **The HTTPS proxy port changes between sessions.** Read it from the live
  `$https_proxy`; do not hardcode a remembered port. A stale port shows up as
  Gradle failing to download a random jar.
- The SDK's `aapt`/`aapt2` are x86 and **crash with `Illegal instruction`** on this
  aarch64 host. Inspect APKs with Python (`zipfile`) or the pure-Java `apksig` jar.
- Push works with the GitHub token. The app's own `git` **tool** runs in the
  *session* cwd, which is `projects/playground/` — a **different repo**
  (`fnportandroid`). Work in `spindle/` and push from there; a raw
  `git push` in this shell has no credentials, so use the token (see below).

### Pushing from a shell (no credential helper is configured)

```sh
cd spindle
cat > /tmp/gh-askpass.sh <<'EOF'
#!/bin/sh
case "$1" in
  *Username*) echo "x-access-token" ;;
  *Password*) echo "$GITHUB_TOKEN" ;;
esac
EOF
chmod +x /tmp/gh-askpass.sh
GIT_ASKPASS=/tmp/gh-askpass.sh GITHUB_TOKEN=<token> GIT_TERMINAL_PROMPT=0 \
  git push origin main
```

The token is the one the owner supplied; it is not in the repo and must not be
committed.

### CI helpers used last session

```sh
TOKEN=<token>
# latest run for a sha
curl -s "https://api.github.com/repos/turanmertkaraca-bit/spindle/actions/runs?per_page=1" \
  -H "Authorization: Bearer $TOKEN"
# jobs for a run
curl -s ".../actions/runs/<RUN>/jobs" -H "Authorization: Bearer $TOKEN"
# a job's log, then grep for "e: file" / FAILED / "tests completed"
curl -sL ".../actions/jobs/<JOB>/logs" -H "Authorization: Bearer $TOKEN"
# an artifact
curl -sL ".../actions/artifacts/<ID>/zip" -H "Authorization: Bearer $TOKEN" -o a.zip
```

---

## 7. Known traps

- `.git/objects/**` may contain **broken symlinks** (proot link2symlink artifacts).
  If git says `bad object HEAD`, the repair is in
  `docs/analysis/arch/ENVIRONMENT.md` §1 — do not `git clone` over the repo.
- `projects/playground/` also contains an **unrelated repo** (`fnportandroid`).
  Work only in `spindle/`.
- `dist/` is gitignored; release APKs are published by CI, not committed.
- **A test that passes by ordering luck is a flake.** Two shared-state bugs hid in
  `:app` tests for weeks; both surfaced only as isolated CI failures. Each store
  test now gets its own database **file** (deleting the shared one corrupts
  Robolectric's pooled handle).
- **A fix that is not released is not a fix.** Tag and publish before asking the
  owner to re-test.
