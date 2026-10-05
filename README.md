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

Backend is feature-complete, and the native Android/Compose app (`:app`,
`dev.lumen.app`) is built on it: streaming providers, durable sessions, 12 tools,
subagent delegation, retries, cancellation, per-session prompt serialization,
and context compaction. See `docs/HANDOFF.md` (read first), `docs/PLAN.md`,
`docs/SPEC.md` and `docs/PARITY.md` (what we ported from opencode and what we
deliberately did not).

## Build

```
./gradlew build      # compile + tests
./gradlew :cli:run   # headless harness (see --help)
```

## Live smoke

There is no keyless path — live calls always need a key. A paid
`OPENROUTER_API_KEY` (and the committed recorded SSE fixtures) is the primary
verified path; an OpenCode free-tier key also works for **free models on
`/chat/completions`** (e.g. `--provider opencode-go --model space-bunny-free`),
but the free tier 403s Claude/GPT, so Zen/Go `/responses` + `/messages` routing
stays blocked on a paid key.

```
./gradlew :cli:run --args="--provider openrouter --model openrouter/auto --prompt \"...\" --yes"
```

`.github/workflows/live.yml` gates this behind `workflow_dispatch` and the
matching repository secret, so it never runs on push/PR and no PR can spend
tokens. `/models` on opencode.ai is public, so a 200 there is not proof a key is
valid — verify with an inference call. Never commit or paste a key.

## License

MIT — see [LICENSE](LICENSE).
