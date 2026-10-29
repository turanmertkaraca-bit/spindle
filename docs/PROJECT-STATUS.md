# Lumen — project status board

Single source of truth for *what is being worked on right now*. Distinct from
`HANDOFF.md` (session narrative) and `AGENTS-INDEX.md` (landed-work log): this
file is the live board. Update it as work moves; keep it short.

Verified against HEAD `f80644e` on branch `main` (CI **green** on that sha).

---

## 0. Local toolchain (verified on-device this session)

The old assumption "`:app` cannot build locally" is **obsolete**. This device can
now build and test the app module. Exact recipe:

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64
export ANDROID_HOME=/opt/android-sdk ANDROID_SDK_ROOT=/opt/android-sdk
export GRADLE_OPTS="-Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=37529 \
                    -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=37529"
./gradlew --no-daemon \
  -Pandroid.aapt2FromMavenOverride=/opt/aapt2bin/aapt2 \
  :app:testDebugUnitTest
```

What each piece is for — **do not rediscover these**:

| Piece | Why it is needed |
|---|---|
| JDK 17 at `/usr/lib/jvm/java-17-openjdk-arm64` | Not preinstalled; `apt-get install openjdk-17-jdk-headless`. |
| `ANDROID_HOME=/opt/android-sdk` | Installed via `sdkmanager`; needs `platforms;android-34` (app `compileSdk`), not 35. |
| `GRADLE_OPTS` proxy flags | The sandbox proxy is `127.0.0.1:37529`. Gradle does **not** read `gradle.local.properties` automatically and does **not** honour `HTTPS_PROXY`; the flags must be passed explicitly. Without them every dependency resolution fails with "Temporary failure in name resolution". |
| `-Pandroid.aapt2FromMavenOverride=/opt/aapt2bin/aapt2` | The Maven AAPT2 binary is **x86-64 only** and cannot execute on this aarch64 device. `/opt/aapt2bin/aapt2` is a wrapper that runs the real AAPT2 under `qemu-x86_64-static`. AGP rejects the override unless the file is literally named `aapt2`. |
| Robolectric `android-all` jar in `~/.m2` | Robolectric fetches `android-all-instrumented` at runtime through its own resolver, which bypasses the Gradle proxy. Pre-place it in `~/.m2/repository/org/robolectric/android-all-instrumented/<ver>/`. |

**Known environmental test failure.** 381 of 504 `:app` unit tests fail on this
device with:

```
java.lang.UnsatisfiedLinkError: no conscrypt_openjdk_jni-linux-aarch_64
```

`conscrypt-openjdk-uber` publishes only an `x86_64` native library, so TLS-using
tests cannot run on arm64. This is an **environment limitation, not a code
defect** — CI runs x86_64 and passes. Judge app-test health by CI, not by the
local count. 123 tests do pass locally and are a useful smoke signal.

---

## 1. Verification gates

| Gate | Command | Trust level |
|---|---|---|
| JVM modules | `./gradlew :core:test :tools:test :sandbox:test` | **Authoritative** locally |
| App compiles | `./gradlew :app:compileDebugKotlin` (with AAPT2 override) | **Authoritative** locally |
| App unit tests | `./gradlew :app:testDebugUnitTest` | Partial locally (conscrypt); authoritative in CI |
| Robolectric/CI | push → poll `ci.yml` by sha | **Authoritative — the only real check on `:app`** |

**Do not treat a local `:app` run as a pass.** Robolectric cannot execute here,
so a change can compile locally, pass locally, and still be red on CI. This
happened: five `:app` tests broke and were only caught by CI. Always confirm
the sha is green on `ci.yml` before calling work done.

Rule unchanged: **never land a red commit on `main`.** A commit is done when CI
is green on its sha.

---

## 2. Work streams

Status: `[ ]` todo · `[~]` in progress · `[x]` done · `[—]` deferred.

### Run A — analysis (read-only)
- [x] A1 core agent loop
- [x] A2 storage integrity
- [x] A3 tools / sandbox safety
- [x] A4 platform / proot — *verified: niceness exists, no CPU/memory cap*
- [x] A5 lifecycle / background
- [x] A6 Compose performance — audited in `07-perf-ui.md` (findings not yet fixed)
- [x] A7 projects / models
- [~] A8 test coverage gaps — two suites added; Robolectric gap still open (see 1)
- [x] A9 doc drift — audited in `09-docs.md`

Reports live in `docs/audit/`. `00-INVENTORY.md` is pinned at `d17dbd2` and is
therefore **partly stale**: P0-1, P0-2, P0-3, P1-6, P1-7, P1-10 and P1-23 were
already fixed before the fix runs began. Re-verify an item against HEAD before
working it.

### Run B — stability fixes (commits fed3b0e..f80644e)
- [x] merge Run A findings into one ranked inventory (`00-INVENTORY.md`)
- [x] fix P0s: quarantine on null handle, migration throw-loop, `stop()` leaving
      the run alive, `release()` over-release
- [x] fix P1s: pty slot leak, guest credential scrub, pty unbounded buffering,
      timeout deadline overflow, Debian `scrubHostEnv`, install latching,
      migrations tolerating older schemas, quadratic de-dup, WAL result unchecked,
      missing `todos`/`snapshots` indexes, unlimited run loop, `activeOrNull`
      unvalidated id, delete-before-prefs, read offset past scan cap
- [x] each shipped fix has a test where one was feasible (2 new suites)

### Run C — optimisation, background, proot limits
- [x] wire `ResourcePolicy.pauseReason` into admission control (was computed,
      called by nobody)
- [x] bound the agent loop: wall-clock + total-tool-call caps, configurable via
      `AgentConfig.maxRunMillis`
- [x] background/foreground service correctness: failed `startForeground` tears
      down the run; stop-handler failure keeps the wake lock; progress-driven
      wake-lock renewal
- [x] add real CPU/memory limiting to the proot path — **done** (`4ff37c0`).
      cgroups need root, so the lever is the shell's own `ulimit`, applied to the
      script the guest shell runs rather than to proot: `-v 2 GiB` (a runaway
      compiler dies with ENOMEM inside the guest instead of being reaped by the
      low-memory killer and taking the app with it) and `-n 4096`.
      `ResourcePolicy.limitScript` joins the guards with `;` and tolerates each
      one's failure, and is applied on the Debian, Alpine and pty paths. No
      CPU-time ulimit on purpose — a real build can exceed any fixed number and
      the per-tool deadline bounds wall clock better. Affinity/cgroup control
      remains out of reach without root; that is a platform limit, not a TODO.

### Run D — UI sweep
- [ ] minimal / merged / coherent pass on the shared chrome language
- [ ] close the known token-literal drift
- [ ] fix the audited `LumenChatScreen` findings in `07-perf-ui.md` (double
      tail-pin scroll, whole-body restart scope, un-remembered `pulse` lambda,
      per-row `Modifier` rebuild)

### Run E — docs
- [ ] refresh stale version/HEAD claims across `HANDOFF.md` etc.
- [ ] rewrite README for developers

---

## 3. Known-good facts (do not re-derive)

- proot is launched by `DebianEnvironment.prootArgv`; flags are `--rootfs`,
  `--cwd`, `-0`, `--kill-on-exit`, `--link2symlink`, and `--bind` for
  `/dev`, `/proc`, `/sys`, cwd, Download, home and `/etc/hosts`.
- Guest env includes `LANG=C.UTF-8`, `LC_ALL=C.UTF-8`, `TZ=UTC`,
  `TERM=xterm-256color`, `DEBIAN_FRONTEND`, and the proxy/CA variables.
- **Correction — niceness DOES exist.** `AndroidShellExecutor.startUnderSetsid`
  prepends `nice -n ResourcePolicy.DEFAULT_NICE` (10) and then `setsid`, so the
  final argv is `setsid nice -n 10 proot …`. Niceness is inherited by the guest
  tree and applies on the host without root. Both the `runInDebian` and the
  `openPty` paths go through it. An earlier draft of this file claimed no CPU
  limiting existed at all; that was wrong — see Run C for what is still missing
  (memory/`ulimit`, per-run throttle, and the unwired pause gate).
- What genuinely does **not** exist: cgroup control (needs root), `ulimit`
  memory caps on the guest, and `chrt`/`taskset` affinity pinning.
- `ResourcePolicy.pauseReason(context)` reads battery/thermal state and returns
  a human-readable pause reason or `null`. It is now **enforced**:
  `ResourcePolicy.acquire(timeoutMs, context)` declines a slot when a pause
  reason is present, and `AndroidShellExecutor` passes the app context on both
  admission calls. (It was previously computed and called by nobody — the
  `AGENTS-INDEX` O8 note is now obsolete.)
- The main-thread rule: `acquire()` on the UI thread only takes an already-free
  slot (`tryAcquire()`); it never blocks waiting for one.
- The `grep` tool returns **false negatives** in this repo (reports "no match"
  for strings that are present). Verify by reading files, not grepping.
