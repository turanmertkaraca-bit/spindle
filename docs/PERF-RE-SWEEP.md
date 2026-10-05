# Perf re-sweep — long-session stress harness (JVM, off-device)

Status: **PASS** · Date: 2026-10-05 · Commit: `ea2e38e` (branch `main`)

This is the off-device part of the `m15` "on-device perf/ANR re-sweep" item
(`docs/PLAN.md`). It runs the existing JVM long-session stress harness against a
**live** provider. It is **not** an on-device measurement (see Scope below).

## Configuration

| Field | Value |
| --- | --- |
| Provider | OpenRouter (`https://openrouter.ai/api/v1`, `OpenAiProvider`, id `openrouter`) |
| Model | `openrouter/auto` (harness default) |
| Turns | 25 |
| Max steps / turn | 4 |
| Harness JVM max heap | Reported `maxMB = 1826` (`Runtime.maxMemory()`); no `SPINDLE_STRESS_XMX` override |
| Gradle daemon heap | `-Xmx1g` (`gradle.properties`), `--no-daemon` single-use daemon |
| Key source | `/root/live-keys.env` sourced into the shell env only; never written/printed (env-only, per instructions) |

Working-tree caveat: HEAD is `ea2e38e`, but the tree was **not clean** at run
time — seven pre-existing uncommitted changes were present (not made by this
run): `app/.../CanvasScreen.kt`, `core/.../AgentConfig.kt`, `core/.../Wire.kt`,
`core/.../Provider.kt`, `core/.../WireTest.kt`, `provider-openai/.../OpenAiProvider.kt`,
`provider-openai/.../OpenAiProviderTest.kt` (+140 lines). Gradle reported
`compileKotlin UP-TO-DATE`, so those already-compiled changes are what actually
ran. The result therefore reflects `ea2e38e` + those working-tree edits, not the
committed tree alone; no code was modified by this task.

## Exact command used

Key value omitted deliberately; `OPENROUTER_API_KEY` comes from the sourced env.

```
bash -lc '. /root/env.sh && . /root/live-keys.env && cd /data/user/0/ai.opencode.app/files/projects/playground \
  && OPENROUTER_API_KEY="$OPENROUTER_API_KEY" ./gradlew :cli:stress \
     --console=plain --no-daemon --args="--turns 25 --max-steps 4"'
```

Run completed first try: `BUILD SUCCESSFUL in 4m 58s` (no retry needed).

## Harness banner and terminal output (quoted, no secrets)

```
[stress] model=openrouter/auto turns=25 maxSteps=4
[stress] workDir=/tmp/spindle-stress-1336132061709572570
[stress] db=/tmp/spindle-stress-1336132061709572570/spindle.db
...
summary:
  heap    min=6.04MB max=6.25MB last=6.25MB slope=0.00MB/turn
  fds     start=38 end=39 min=38 max=39 nonDecreasing=true
  rss     start=83.73MB end=113.54MB
  db      start=168.80KB end=192.00KB wal=0.00KB
  rows    messages=72 parts=78 snapshots=7 todos=1
  bus     eventsReceived=635 subscriberDelayMs=3
  errors  thrown=0 errorTurns=0

[stress] PASS — long session stable over 25 turns
```

## Observed indicators

- **Retained heap** (forced GC before each sample): after warmup it is flat —
  `min=6.04 MB max=6.25 MB last=6.25 MB`, least-squares `slope=0.00 MB/turn`.
  Sample 0 (pre-loop) was 13.7 MB; turns 1–25 hold ~6.0–6.25 MB. No growth.
- **File descriptors**: `38 -> 39` over the whole run; `max=39`, monotonic but
  the total delta is +1, well inside the +24 slack (and below the +2 monotonic
  trip). No fd leak.
- **DB/WAL**: DB+WAL+SHM total `168.80 KB -> 192.00 KB` (+23.2 KB over 25
  turns). WAL is `0.00 KB` at every sampled turn after turn 0 because the run's
  end-of-run `maintain()` checkpoints WAL; sample 0 shows the pre-run
  `wal=132.8 KB`. Rows grew as expected: 72 messages, 78 parts, 7 snapshots,
  1 todo. Far below the 64 MB limit.
- **Latency per turn** (wall time for `loop.prompt`, ms): min 1233, max 15711,
  mean 5189, p50 4533, p90 9221, p95 12792. These are dominated by live
  OpenRouter/model and network round-trips (and tool steps), not by the engine;
  no per-turn latency assertion exists in the harness.
- **RSS**: 83.73 MB -> 113.54 MB (includes JVM, SQLite native, HTTP stack).
- **Errors**: `thrown=0`, `errorTurns=0`.

Representative per-turn samples (from harness stdout):

```
turn  1 | heap     6.0MB | rss    97.4MB | fds  39 | db   88.0KB | wal    0.0KB | msgs   2 | parts    3 | snaps   0 | todos  0 | events    11 |   9221ms
turn 12 | heap     6.2MB | rss   108.6MB | fds  39 | db  132.0KB | wal    0.0KB | msgs  35 | parts   40 | snaps   3 | todos   1 | events   390 |   7780ms
turn 24 | heap     6.2MB | rss   113.5MB | fds  39 | db  160.0KB | wal    0.0KB | msgs  70 | parts   76 | snaps   7 | todos   1 | events   629 |  15711ms
turn 25 | heap     6.2MB | rss   113.5MB | fds  39 | db  160.0KB | wal    0.0KB | msgs   72 | parts   78 | snaps   7 | todos    1 | events   635 |   2131ms
```

## Assertions the harness makes (`StressHarness.kt`)

All passed (`violations` was empty -> exit 0, `[stress] PASS`):

- retained heap peak after warmup <= 256 MB
- retained heap slope after warmup (turns >= 3) <= 4.0 MB/turn
- retained heap drift (last - min after warmup) <= 128 MB
- fd growth start -> end <= +24, and not monotonically non-decreasing by > +2
- DB total (db + wal + shm) <= 64 MB
- no turn threw; no turn ended in `SessionState.ERROR`
- final session state is `IDLE`
- final assistant message exists and has text
- persisted user-message count == `--turns` (25) — i.e. no data loss

## Verdict

**PASS.** Over 25 turns / 4 max-steps on live OpenRouter, every harness
invariant held: retained heap flat (~6 MB, slope 0.00 MB/turn), file descriptors
stable (+1), DB/WAL bounded and checkpointed, no exceptions, no error sessions,
final state `IDLE`, and exactly 25 user messages persisted. Latency is
network/model-bound and unremarkable.

## Scope — what this does and does NOT prove

**Does prove:** the spindle engine's object graph (AgentLoop + OpenAiProvider +
SqliteSessionStore + SqliteSnapshotStore + DefaultTools + EventBus, the same
wiring the app builds) does not leak retained heap, fds, or DB/WAL over a
25-turn tool-using session on the JVM, and keeps session state/message counts
correct against a live paid provider.

**Does NOT prove:**
- Any on-device behavior. This is a desktop/server-arm64 JVM (JDK 17) harness,
  not the Android app on a phone. `Runtime` heap, `/proc/self/fd` and `/proc`
  RSS are JVM/host measurements; Android ART heap, GC behavior, thermal limits,
  Doze, background restrictions and Activity/frame timing are out of scope here.
- UI/ANR/jank, frame timing, battery or thermal behavior — those require the
  on-device `PerfSampler` sweep, which remains **pending** per `PLAN.md` m15.
- Real on-device storage (Room/app DB) or the Android Sandbox/`ShellExecutor`;
  this harness uses the JVM `:store-sqlite` store and host tools on a temp DB.
- Long-run behavior beyond 25 turns; no soak/endurance test was run.
