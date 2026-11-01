# spindle

A native, **UI-agnostic** agent backend in Kotlin. The same agent loop that powers
the app, with no HTTP server required, no process bridge, and no parallel state
model to reconcile: the loop runs in-process and the Android/Compose UI is a thin
consumer of the event stream. An optional `:server` module can host the same loop
over HTTP; the app does not use it.

> Codename: `spindle` (name TBD). Backend first; UI later.

## Why

- **No server to start.** The loop runs in-process.
- **No chat sync.** A single session store — the UI renders what the store says.
- **Own the wire.** Two provider adapters cover OpenAI-compatible (`chat/completions`)
  and Anthropic (`messages`), which is all we need for DeepSeek, OpenRouter,
  OpenCode Zen and OpenCode Go.

## Start here

| Document | For |
|---|---|
| [`docs/HANDOFF.md`](docs/HANDOFF.md) | **Read first.** Session narrative, version, how to work, CI poll, invariants. |
| [`docs/guide/README.md`](docs/guide/README.md) | Per-subsystem developer guide — one file per area (agent loop, storage, tools, shell/proot, service, UI, testing, conventions). |
| [`docs/PROJECT-STATUS.md`](docs/PROJECT-STATUS.md) | What is left: Run D token-literal drift and Run E docs (this file). |
| [`docs/PLAN.md`](docs/PLAN.md) | Milestones M0–M10 + merge track m11–m15. |
| [`docs/SPEC.md`](docs/SPEC.md) | Engine contracts (loop, events, SPIs, wire). |
| [`docs/PARITY.md`](docs/PARITY.md) | What was ported from opencode vs deliberately dropped. |

## Modules

| module | role |
|---|---|
| `:core` | domain model, agent loop, events, provider/tool/store SPIs — **no Android deps, plain JVM testable** |
| `:provider-openai` | OpenAI-compatible streaming (`chat/completions`) |
| `:provider-anthropic` | Anthropic-style streaming (`messages`) |
| `:store-sqlite` | durable `SessionStore` on SQLite (JDBC) |
| `:tools` | read, write, edit, bash, apply_patch, glob, grep, git, webfetch, websearch, todowrite, question, task, skill, external-directory |
| `:sandbox` | process/shell sandbox abstractions |
| `:server` | optional `com.sun.net.httpserver` host for the loop (not required by the app) |
| `:cli` | headless harness + live smoke tests |
| `:app` | the Android/Compose UI (`dev.lumen.app`) |

## Current version

**`0.2.8`** (`versionCode 13`) — untagged; the newest tag is `v0.2.7`.

Backend is feature-complete, and the native Android/Compose app (`:app`) is built
on it: streaming providers with per-model routing, durable sessions, 14 tools
(incl. `skill` + read-only `external-directory`), opt-in `json_schema` structured
output (engine-only), subagent delegation, retries, cancellation, per-session
prompt serialization, and context compaction.

## Build / test

Local JVM loop (everything except `:app`):

```sh
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64
export ANDROID_HOME=/opt/android-sdk ANDROID_SDK_ROOT=/opt/android-sdk
export GRADLE_OPTS="-Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=37529 \
  -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=37529"

./gradlew --no-daemon -Pandroid.aapt2FromMavenOverride=/opt/aapt2bin/aapt2 \
  :core:test :sandbox:test :tools:test :provider-openai:test \
  :provider-anthropic:test :store-sqlite:test :server:test :cli:classes \
  --console=plain
```

`:app` **compiles** locally (SDK 34/35 is present) but its Robolectric tests only
run on CI — see `docs/guide/07-testing.md` for why a local `:app` build is a
compile check only, never a behavioural pass.

```sh
./gradlew --no-daemon -Pandroid.aapt2FromMavenOverride=/opt/aapt2bin/aapt2 \
  :app:compileDebugKotlin
```

Headless harness:

```sh
./gradlew :cli:run   # see --help
```

## Live smoke

There is no keyless path — live calls always need a key. A paid
`OPENROUTER_API_KEY` (and the committed recorded SSE fixtures) is the primary
verified path; an OpenCode free-tier key also works for **free models on
`/chat/completions`** (e.g. `--provider opencode-go --model space-bunny-free`).
The free tier 403s Claude/GPT on every surface, so live Zen/Go Claude/GPT parity
is **deliberately closed / deprioritized** — the owner has an OpenCode Go
subscription only and will not add paid API credits (do not chase it). Routing
itself is wired and fixture-tested (`/messages` for Claude/Qwen); `/responses`
(GPT/Grok) is intentionally rejected, not mis-routed.

```sh
./gradlew :cli:run --args="--provider openrouter --model openrouter/auto --prompt \"...\" --yes"
```

`.github/workflows/live.yml` gates this behind `workflow_dispatch` and the
matching repository secret, so it never runs on push/PR and no PR can spend
tokens. `/models` on opencode.ai is public, so a 200 there is not proof a key is
valid — verify with an inference call. Never commit or paste a key.

## License

MIT
