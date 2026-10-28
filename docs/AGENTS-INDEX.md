# AGENTS-INDEX — living whole-app analysis

**This file is the entry point for any agent working on this app.** It is
updated at the end of every work run. If you are an agent picking this up:
read §1, then jump to the section matching your task. Do not trust a section
whose "verified" stamp predates the current HEAD without re-checking it.

- **Last updated:** run E, HEAD `d9e880a`
- **Verified against:** HEAD `d9e880a` (CI green on this sha — both jobs)
- **Maintainer rule:** append a changelog line in §9 whenever you change
  anything in here. Never delete a finding; mark it fixed with the commit sha.

---

## 1. Orientation

### What this is

A native Android agent app in Kotlin. The agent loop lives in `:core` (plain
JVM, no Android deps, so it is testable without a device). The Compose UI in
`:app` is a thin consumer of the event stream the loop emits. There is no HTTP
server between them and no second state model: one session store, and the UI
renders what the store says.

Codename "spindle" is provisional. License: MIT.

### Build reality on a constrained device

- `:core`, `:sandbox`, `:tools`, `:server`, `:cli`, `:store-sqlite`,
  `:provider-openai`, `:provider-anthropic` build and test locally on the JVM.
- **`:app` cannot build locally** — there is no Android SDK. CI (GitHub
  Actions, `android app (robolectric)` job) is the only verifier for `:app`.
- Local JVM verification command (works offline once caches are warm):

```
export JAVA_HOME=/root/.gradle/jdks/eclipse_adoptium-21-aarch64-linux.2
export PATH=$JAVA_HOME/bin:$PATH
./gradlew :core:test :store-sqlite:test :sandbox:test :tools:test \
  -Pkotlin.jvm.target.validation.mode=warning --console=plain
```

  `-Pkotlin.jvm.target.validation.mode=warning` is required locally: the
  modules target JVM 17 and the only JDK here is 21. CI uses 17 and does not
  need the flag.
- If Gradle needs the network, it must be given the proxy explicitly:
  `-Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=<port from $https_proxy>`.
  The port changes between runs; read it from the environment each time.
- **Push:** the internal git tool only operates on the outer workspace repo.
  Pushing spindle requires a PAT passed through a one-shot credential helper;
  see §7.

### Module map

| Module | Files | Lines | Role |
|---|---:|---:|---|
| `:core` | 59 | 10,090 | Domain model, agent loop, events, provider/tool/store SPIs |
| `:app` | 116 | 34,598 | Compose UI, Android platform layer, storage adapters |
| `:tools` | 29 | 6,015 | read, write, edit, bash, apply_patch, glob, grep, webfetch, websearch, todowrite, question, task, skill, git |
| `:store-sqlite` | 4 | 2,278 | Durable `SessionStore` on SQLite via JDBC |
| `:provider-openai` | 4 | 2,069 | OpenAI-compatible streaming (`chat/completions`) |
| `:provider-anthropic` | 2 | 1,125 | Anthropic-style streaming (`messages`) |
| `:sandbox` | 4 | 968 | proot argv construction, tar.gz extraction, in-app proxy |
| `:server` | 6 | 1,029 | Headless server surface |
| `:cli` | 4 | 905 | Headless harness and live smoke tests |

### The five largest app files (start here when lost)

| File | Lines | What lives there |
|---|---:|---|
| `ChatViewModel.kt` | 3,985 | Run orchestration, event application, streaming buffers, session lifecycle |
| `ui/LumenChatScreen.kt` | 3,529 | The chat surface |
| `ui/HomeScreen.kt` | 1,323 | Home strip and recents |
| `ui/GitHubScreen.kt` | 1,040 | PAT integration and repo surface |
| `ui/FilesScreen.kt` | 1,020 | File browser |

`ChatViewModel.kt` at ~4k lines is the single biggest maintenance risk in the
codebase. It mixes run lifecycle, streaming text buffering, event application,
perf sampling, notification calls and storage access. Splitting it is the
highest-leverage refactor available (see §4).

---

## 2. Known-good invariants — do not break these

These are load-bearing. Each one has a bug behind it or a test pinning it.

1. **Generation guards run teardown.** `ChatViewModel.runGeneration` is bumped
   by `stop()` and by a superseding send. Every teardown step in a run's
   `finally` is gated on `generation == runGeneration`, so a superseded run
   cannot kill the watcher, service or busy flag of the run that replaced it.
2. **`stop()` is authoritative.** It bumps the generation first, then cancels,
   flushes deltas, stops the watcher and service, and stops the sampler via
   `stopPerfNow()`. The run's own `finally` then skips its teardown because the
   generation moved.
3. **Migrations are versioned and atomic.** `AndroidDatabase` runs a
   `Migration(version, applyTo)` ladder inside a transaction and bumps
   `user_version` only after the body succeeds. A migration body must be safe on
   a database that lacks tables it assumes exist — migration 6 checks for the
   `sessions` table before altering it.
4. **Moving a session is metadata-only.** `SessionStore.moveSession` rewrites
   `project_id` and nothing else. Messages, parts, todos and snapshots are not
   touched, so history survives a move. A subtree move walks `parent_id`
   recursively so subagents follow their root.
5. **Unfiled sessions stay reachable.** A null/empty `project_id` is the inbox,
   and `sessionsInProject(null)` matches both null and empty string so rows
   written before projects existed never become invisible.
6. **`RunService.update()` never resurrects the service.** It only forwards when
   a service instance is alive; a late progress frame after teardown is dropped.
7. **Wake lock is bounded.** The run's partial wake lock takes a five-minute
   timeout and is re-armed by every progress notification. A stalled run expires
   instead of holding the CPU forever.
8. **Tool-call/tool-result pairing must survive compaction.** Providers reject a
   request whose assistant tool-call has no matching tool result. Anything that
   trims context must keep pairs together.

---

## 3. Run log — what each run did

### Run A — analysis
Not completed as a subagent fan-out: five agents each burned 200–300k tokens
and returned empty output on this device. Analysis was done by direct reading
instead. Findings are folded into §4–§6.

### Run B — bugs and stability (in progress)
*Complete and CI green.*
- `40311da` — migration 6 guarded against a missing `sessions` table; version
  assertions updated to 6; new column and index asserted.
- `b2d1736` — wake lock bounded to five minutes with re-arm on progress;
  `RunService.update()` gated on liveness so a late frame cannot resurrect the
  service.

### Run C — resource policy and proot comfort
*Complete and CI green (`2160f75`).* `ResourcePolicy` added: concurrency
semaphore, `nice` wrapping, battery/thermal pause reasons. Admission is enforced
in `AndroidShellExecutor` for both the proot and fallback shell paths, and in
`openPty` for interactive sessions. Guest comfort added: `LANG`/`LC_ALL`, `TZ`,
and a generated `/etc/hosts` bind.

### Run D — project management
*Complete and CI green.*
- `e374a68` + `c1ac636` — data foundation: `Session.projectId`, both stores,
  migrations 4 and 6, recursive subtree moves.
- `d69063a`, `0902111`, `befaee7`, `0683ddd`, `551f6d9`, `52ecc83` — the
  dashboard: per-project session counts, a one-tap resume, and the interaction
  fixes needed to make the resume tap work.

### Run E — model browser UI
*Complete and CI green (`e8d5184`, verified in the `d9e880a` run).* Search,
sort by name/context/cost, and a starred filter. The filter logic is a pure
function (`filterAndSortModels`) with unit tests that need no Compose harness.

---

## 4. Bug and risk inventory

Severity: **P0** crashes/data loss, **P1** broken or misleading behaviour,
**P2** rough edges. Status is current as of HEAD `b2d1736`.

### Fixed

| # | Severity | Finding | Fixed in |
|---|---|---|---|
| F1 | P1 | Migration 6 would `ALTER TABLE sessions` on a database that has no `sessions` table, aborting the whole upgrade | `40311da` |
| F2 | P1 | Run wake lock acquired with no timeout; a stalled run could hold the CPU awake indefinitely | `b2d1736` |
| F3 | P1 | `RunService.update()` used `startForegroundService` per progress tick, resurrecting a dead service and leaving a phantom notification | `b2d1736` |
| F4 | P1 | No CPU/priority control at all; a guest build could starve the UI | `2160f75` |
| F5 | P1 | No concurrency cap on guest processes | `2160f75` |
| F6 | P1 | Resume tap on the project dashboard never fired: the clickable lived on a container whose child Text carried the tap | `551f6d9` |
| F7 | P2 | `RetryResetTest` was flaky — asserted synchronously against an async rebuild; failed in CI and passed on an identical rerun | `d9e880a` |

### Open

| # | Severity | Finding | Where |
|---|---|---|---|
| O3 | P2 | `ChatViewModel` ~4k lines: run lifecycle, streaming buffers, event application and storage in one class | `ChatViewModel.kt` |
| O4 | P2 | Deep link to a session that no longer exists lands silently on Home with no explanation | `MainActivity.kt` |
| O5 | P2 | README status line says `0.2.2 (versionCode 7)` while the tree is at 0.2.8+ | `README.md` |
| O8 | P2 | The thermal/battery pause reason is computed but nothing calls it yet — `ResourcePolicy.pauseReason` needs wiring into the run loop | `platform/ResourcePolicy.kt` |
| O9 | P2 | The concurrency cap is per-command; a single long tool call still holds one slot for its whole duration | `platform/AndroidShellExecutor.kt` |
| O10 | P3 | Model browser sorts but does not group by provider within favourites/recents | `ui/ModelPickerScreen.kt` |

---

## 5. Resource policy and proot plan (Run C)

### The gap
The app runs a full Linux userland in proot with no admission control. On a
phone this is the difference between "the agent works while I scroll" and "the
whole device stutters". Nothing today bounds concurrency or lowers priority.

### Design constraints
- **No root.** cgroup v2 manipulation needs privileges the app does not have on
  a stock device. Anything relying on cgroups is out.
- **proot does not isolate scheduling.** proot is a ptrace shim; the guest shares
  the host scheduler, so priority must be set on the host side of the exec.
- Settings must degrade cleanly when the device refuses a flag or a syscall.

### Planned shape
- A `ResourcePolicy` in the platform layer holding: max concurrent guest
  processes, an optional CPU affinity mask, a nice level, and a thermal/battery
  pause threshold.
- Admission through a semaphore sized by the policy, so the cap is enforced at
  the one place that spawns guest work rather than scattered across callers.
- Lower priority via `Process.setThreadPriority` and/or `nice` on the spawned
  process; affinity via `taskset` only when the binary exists.
- A timed wake lock already landed in Run B; the policy should also release it
  on thermal pause instead of waiting for the timeout.
- proot comfort: `LANG`/`LC_ALL`, `TZ`, `/etc/hosts`, `/dev/shm` sizing, and
  terminfo, each added only when the installed proot accepts it.

---

## 6. UI inventory

### Settings screen — polished
Signed off. Do not change without being asked. Contains the run notification
toggle, storage clear, provider keys and diagnostics.

### Model browser — needs work
`ui/ModelPickerScreen.kt` (430 lines) + `ui/ModelPickerSheet.kt` render a flat
list from `ModelRowData`. Missing: search, provider filter, favourites grouping,
sort by cost/context, a detail view, and explicit empty/error states. Favourites
exist in the ViewModel (`ChatViewModel.kt:1460`) but the picker does not group by
them.

### Projects — needs the Run D UI
`ui/ProjectsScreen.kt` takes `List<ProjectRow>` and only switches/creates/deletes
folders. The store now carries `projectId` per session but the screen never reads
it. This is the next piece of Run D.

### Design language to match
`ui/LumenControls.kt`, `ui/LumenMotion.kt`, `ui/LumenSheet.kt`, `ui/LumenType`
and `LumenFonts`. The aesthetic is terminal-adjacent: monospace labels, dim
secondary text, bordered surfaces. New UI must use these primitives rather than
inventing components.

---

## 7. CI and push

- Workflow `.github/workflows/ci.yml`, two jobs: `jvm backend` and
  `android app (robolectric)`. Both must pass.
- Poll by sha, never trust a stale run:

```
SHA=$(git rev-parse --short=7 HEAD)
curl -s "https://api.github.com/repos/turanmertkaraca-bit/spindle/actions/runs?per_page=5" \
  | python3 -c "import json,sys
for r in json.load(sys.stdin)['workflow_runs']:
    if r['head_sha'].startswith('$SHA'): print(r['status'], r['conclusion'], r['id'])"
```

- Push needs a PAT; the internal git tool cannot reach the subdirectory repo:

```
git -c credential.helper='!f() { echo "username=x-access-token"; echo "password=$GH_KEY"; }; f' \
  push origin HEAD:main
```

  Pass the token through the environment only. Never write it to a file, a
  remote URL or a log.

---

## 8. Traps — mistakes already made here

1. **The internal git tool is bound to the outer workspace repo.** `git status`
   / `git push` with `path: spindle` still reports the outer tree. Use shell git
   inside `spindle/` for anything real.
2. **Do not background Gradle and return.** The process dies with the call. Run
   long builds in the foreground with a generous timeout.
3. **Read long files with an offset.** `SqliteSessionStore.kt` is 968 lines;
   repeated head reads only ever show lines 1–110. Use `offset`/`limit` or grep
   for the region.
4. **Robolectric shadow APIs are unverified locally.** No Robolectric jar is
   cached on this device, so an invented shadow method only fails in CI. Prefer
   asserting state the production code exposes, or check an existing test's
   usage first.
5. **Commit messages with backticks or `$` break the shell.** Write the message
   to a file and use `git commit -F`.
6. **The proxy port changes.** It was 40287, then gone, then 45423. Re-read
   `$https_proxy` instead of hardcoding.

---

## 9. Changelog

| Run | Change |
|---|---|
| A | Created this file; folded direct-reading analysis into §4–§6 |
| B | `40311da` migration guard; `b2d1736` wake lock + update gating |
| D | `e374a68` project column and moves; `c1ac636` store tests |
| C | `2160f75` ResourcePolicy: concurrency, nice, thermal pause; proot comfort flags |
| D | `d69063a` dashboard counts + resume; interaction fixes through `551f6d9` |
| E | `e8d5184` model search/sort/starred filter |
| B | `d9e880a` de-flaked RetryResetTest |
