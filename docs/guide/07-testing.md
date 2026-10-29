# Building and testing

Verified against HEAD `4ff37c0`.

## Environment

- JDK 17, `ANDROID_HOME=/opt/android-sdk`, `GRADLE_OPTS` with an https proxy.
- `aapt2` must be overridden:
  `-Pandroid.aapt2FromMavenOverride=/opt/aapt2bin/aapt2`
  (the Maven aapt2 is the wrong architecture for this box).

A known-good invocation:

```sh
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64
export ANDROID_HOME=/opt/android-sdk ANDROID_SDK_ROOT=/opt/android-sdk
export GRADLE_OPTS="-Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=37529 \
  -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=37529"

./gradlew --no-daemon -Pandroid.aapt2FromMavenOverride=/opt/aapt2bin/aapt2 \
  :core:test :tools:test :sandbox:test
```

A full `:app` test run takes ~4 minutes; the JVM modules take ~2.

## What is authoritative where

| check | local | CI | trust |
|---|---|---|---|
| `:core:test` (242) | yes | yes | **local is authoritative** |
| `:tools:test` (105) | yes | yes | **local is authoritative** |
| `:sandbox:test` (8) | yes | yes | **local is authoritative** |
| `:store-sqlite:test` | yes | yes | **local is authoritative** |
| `:app:compileDebugKotlin` | yes | yes | compile only |
| `:app` Robolectric tests | **cannot run** | yes | **CI only** |

## The trap this project has already fallen into

Robolectric cannot initialise in the dev sandbox — the aarch64 host has no
conscrypt native. So `:app` Robolectric tests **do not execute locally**. Five
real regressions during the stability wave compiled cleanly, "passed" as far as
the local run could tell, and were red on CI.

Rules that follow:

1. **Never claim an `:app` test passes** on the strength of a local run. The only
   honest local statement is "compiles".
2. Prefer a **plain JVM test** for anything that does not truly need a `Context`.
   This is why `ResourcePolicy.pauseReasonFor` takes a plain `Battery` and why
   `ResourceLimitTest` is not Robolectric — it made new policy logic genuinely
   verifiable locally.
3. After pushing, **wait for CI** and read both jobs
   (`android app (robolectric)`, `jvm backend`) before calling the work done.

## Writing a test that can run locally

- Put pure logic in a function that takes data, not an Android `Context`.
- If you need a battery, pass a value type (see `ResourcePolicy.Battery`).
- Wrap platform calls in `runCatching` — `Looper.getMainLooper()` throws on a
  plain JVM, and code that reads it must treat "unknown" as a safe default
  (`ResourcePolicy.acquire` treats an unknown thread as a worker).
- Provide an internal `resetForTest()` for object-level mutable state; the
  thermal latch is cleared there.

## Gotchas

- `--no-daemon`: the daemon tends to wedge in this sandbox.
- Always pass `aapt2FromMavenOverride`, or `:app` fails on a native exec.
- Test XML lands in `<module>/build/test-results/`; grep it rather than parsing
  console output, which truncates.
