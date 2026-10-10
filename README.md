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

**`0.2.2`** (`versionCode 7`). Backend is feature-complete, and the native
Android/Compose app (`:app`, `dev.lumen.app`) is built on it: streaming providers
with per-model Zen/Go routing (`/messages` for Claude/Qwen, **fixture-tested**;
GPT/Grok `/responses` deliberately rejected), durable sessions, 14 tools (incl.
`skill` + read-only `external-directory`), opt-in `json_schema` structured output
(engine-only; no host opts in yet), subagent delegation, retries, cancellation,
per-session prompt serialization, and context compaction.

The 0.1.2 wave adds:

- **Composer** — one rounded input card with a bottom control row (attach,
  vision/eye, build/plan chips, model chip, key/theme, send), a compact usage
  line (`≈ N new · next $X · ctx N`); the new-content cue now reads `↓ latest`.
- **Mentions → files** — file/directory mentions in assistant text are tappable
  and open the file in the Files viewer at the line (not just an in-chat peek);
  directories resolve via `:core` `ReferenceResolver.resolveKinds`, and newly
  created files link once the workspace revision bumps.
- **Files live refresh** — the list refreshes when the agent writes/edits
  (direct `FileEdited` + the indirect watcher); opening a touched file scrolls to
  and highlights the newest edited range (suppressed for newly created files).
- **Home** — a status strip (provider·model, sandbox, budget, GitHub) plus quick
  controls (ask-before-tools switch, Files/Terminal).
- **API keys** — provider cards with default model, Show/Hide, Paste, a real
  "Test key" probe, inline error, and non-destructive editing.
- **GitHub** — PAT connect/validate (`GitHubClient`), clone/status (`GitRunner`)
  with the token sealed in `KeyStore` and never placed in a URL or log;
  `GitHubScreen` + a Settings link + a Home status pill. **PAT only, no OAuth;
  commit/push automation is not built.**
- **Internet verified** — `websearch` (browser-style POST to DuckDuckGo, honest
  blocked-vs-no-results) and `webfetch` (unresolved hosts allowed only when a
  proxy resolves them, HTTP status surfaced) both returned `[tool ok]` in a live
  OpenRouter CLI run.

The 0.1.3 UI/UX fix wave adds:

- **Readable boundaries** — an opaque `outline` colour role + a `row` shape give
  every card/row/input a minimal 1dp gray rounded boundary, with raised panel
  surfaces so the layout reads as layers.
- **Connected tool runs** — tool cards and thinking rows are joined into visual
  runs (`UiStep.linkedAbove` via `StepMapper.linkRuns` + a thin rail) instead of
  isolated cards; raw tool JSON is never shown (question/prompt/query and other
  friendly args are surfaced).
- **Pinned streaming tail** — the newest line stays glued to the bottom when the
  usage meter, cards or the IME shrink the viewport.
- **API keys** — the key screen is redesigned with compact provider radio rows, a
  visible field boundary, and unified inline test feedback.
- **In-chat quick settings** — a sheet over the transcript (provider, model,
  theme, budget, ask-before-tools + key/full-settings links) so tuning a chat no
  longer jumps to the full Settings page.

The UI has had the base polish pass plus four refinement passes (shared
tokens/motion, calm empty/loading/error states, one-line tool cards, shared
screen chrome, lighter surfaces, the 0.1.2 composer/home/key/GitHub rework, and
the 0.1.3 boundary/connected-run/quick-settings wave; the light+dark screenshot
sweep covers every screen including GitHub and the quick-settings sheet — the
last CI-verified sweep reported 0 render errors). Live Zen/Go Claude/GPT is
**out of scope by owner decision** (Go
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
