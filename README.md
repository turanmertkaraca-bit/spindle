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
| `:tools` | read, write, edit, bash, apply_patch, glob, grep, webfetch, websearch, todowrite, question, task, skill, external-directory |
| `:cli` | headless harness + live smoke tests |

`:core` has **no Android dependencies**, so the whole backend is testable on a plain
JVM (including GitHub Actions).

## Status

**`0.1.1`** (`versionCode 2`). Backend is feature-complete, and the native
Android/Compose app (`:app`, `dev.lumen.app`) is built on it: streaming providers
with per-model Zen/Go routing (`/messages` for Claude/Qwen, **fixture-tested**;
GPT/Grok `/responses` deliberately rejected), durable sessions, 14 tools (incl.
`skill` + read-only `external-directory`), opt-in `json_schema` structured output
(engine-only; no host opts in yet), subagent delegation, retries, cancellation,
per-session prompt serialization, and context compaction. The UI has had the base
polish pass plus two refinement passes (shared tokens/motion, calm
empty/loading/error states, one-line tool cards, shared screen chrome, lighter
surfaces; light+dark screenshot sweep over every screen, ~85 PNGs, 0 render
errors). Live Zen/Go Claude/GPT is **out of scope by owner decision** (Go
subscription only, no paid API credits), not blocked pending a key. See
`docs/HANDOFF.md` (read first), `docs/PLAN.md`, `docs/SPEC.md` and
`docs/PARITY.md` (what we ported from opencode and what we deliberately did
not).

## Build

```
./gradlew build      # compile + tests
./gradlew :cli:run   # headless harness (see --help)
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

```
./gradlew :cli:run --args="--provider openrouter --model openrouter/auto --prompt \"...\" --yes"
```

`.github/workflows/live.yml` gates this behind `workflow_dispatch` and the
matching repository secret, so it never runs on push/PR and no PR can spend
tokens. `/models` on opencode.ai is public, so a 200 there is not proof a key is
valid — verify with an inference call. Never commit or paste a key.

## License

MIT — see [LICENSE](LICENSE).
