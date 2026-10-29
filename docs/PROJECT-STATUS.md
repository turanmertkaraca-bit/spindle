# Lumen — project status board

Single source of truth for *what is being worked on right now*. Distinct from
`HANDOFF.md` (session narrative) and `AGENTS-INDEX.md` (landed-work log): this
file is the live board. Update it as work moves; keep it short.

Verified against HEAD `d17dbd2` on branch `main`.

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
| Robolectric/CI | push → poll `ci.yml` by sha | Authoritative |

Rule unchanged: **never land a red commit on `main`.** A commit is done when CI
is green on its sha.

---

## 2. Work streams

Status: `[ ]` todo · `[~]` in progress · `[x]` done · `[—]` deferred.

### Run A — analysis (read-only)
- [~] A1 core agent loop
- [~] A2 storage integrity
- [~] A3 tools / sandbox safety
- [x] A4 platform / proot — *verified: no niceness/CPU limit exists anywhere*
- [~] A5 lifecycle / background
- [ ] A6 Compose performance
- [ ] A7 projects / models
- [ ] A8 test coverage gaps
- [ ] A9 doc drift

### Run B — stability fixes
- [ ] merge Run A findings into one ranked inventory
- [ ] fix P0s first, then P1s; each with a test that fails before the fix

### Run C — optimisation, background, proot limits
- [ ] wire `ResourcePolicy.pauseReason` into the run loop (computed today, called by nobody)
- [ ] add real CPU limiting to the proot path (none exists; see A4)
- [ ] background/foreground service correctness

### Run D — UI sweep (frozen until this session; now unfrozen)
- [ ] minimal / merged / coherent pass on the shared chrome language
- [ ] close the known token-literal drift

### Run E — docs
- [ ] refresh stale version/HEAD claims
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
  memory caps on the guest, `chrt`/`taskset` affinity pinning, and any
  enforcement of `ResourcePolicy.pauseReason`.
- `ResourcePolicy.pauseReason(context)` reads battery/thermal state and returns
  a human-readable pause reason or `null`. It is **computed but not called**
  from the run loop (`AGENTS-INDEX` item O8).
- The `grep` tool returns **false negatives** in this repo (reports "no match"
  for strings that are present). Verify by reading files, not grepping.
