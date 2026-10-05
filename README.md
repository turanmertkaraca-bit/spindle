# spindle

A native, **UI-agnostic** agent backend in Kotlin. The same agent loop that powers
the app, with no HTTP server, no process bridge, and no parallel state model to
reconcile. The Android/Compose UI is a thin consumer of the event stream.

> Codename: `spindle` (name TBD). Backend first; UI later.

## Why

- **No server to start.** The loop runs in-process.
- **No chat sync.** A single session store — the UI renders what the store says.
- **Own the wire.** Two provider adapters cover OpenAI-compatible (`chat/completions`)
  and Anthropic (`messages`), which is all we need for DeepSeek, OpenRouter,
  OpenCode Zen and OpenCode Go.

## Modules

| module | role |
|---|---|
| `:core` | domain model, agent loop, events, provider/tool/store SPIs |
| `:provider-openai` | OpenAI-compatible streaming (`chat/completions`) |
| `:provider-anthropic` | Anthropic-style streaming (`messages`) |
| `:store-sqlite` | durable `SessionStore` on SQLite (JDBC; Room later on Android) |
| `:tools` | read, write, edit, bash, apply_patch, glob, grep, webfetch, todowrite, question, task |
| `:cli` | headless harness + live smoke tests |

`:core` has **no Android dependencies**, so the whole backend is testable on a plain
JVM (including GitHub Actions).

## Status

Backend is feature-complete for a first UI: streaming providers, durable sessions,
11 tools, subagent delegation, retries, cancellation and context compaction.
See `docs/PLAN.md`, `docs/SPEC.md` and `docs/PARITY.md` (what we ported from
opencode and what we deliberately did not).

## Build

```
./gradlew build      # compile + tests
./gradlew :cli:run   # headless harness (see --help)
```

## Live smoke

Live provider calls need a paid key: there is no keyless path, because the
OpenCode free tier rejects third-party clients. With `OPENROUTER_API_KEY` set:

```
./gradlew :cli:run --args="--provider openrouter --model openrouter/auto --prompt \"...\" --yes"
```

`.github/workflows/live.yml` gates this exact command behind `workflow_dispatch`
and the matching repository secret, so it never runs on push/PR and no PR can
spend tokens.

## License

MIT — see [LICENSE](LICENSE).
