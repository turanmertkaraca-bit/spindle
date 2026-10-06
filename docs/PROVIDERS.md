# Providers

Four providers ship in v1. DeepSeek, OpenRouter and the OpenAI-compatible
surfaces use `Authorization: Bearer <api key>`; the OpenCode Anthropic surface
(`/messages`) uses `x-api-key` instead (see "OpenCode Zen/Go auth surfaces").
Model references passed to the agent are `provider/model`, e.g.
`deepseek/deepseek-flash` or `opencode/claude-sonnet-4`.

## Table

| provider | base URL | wire format | model-id prefix | auth | notes |
|---|---|---|---|---|---|
| DeepSeek | `https://api.deepseek.com` | `chat/completions` | `deepseek/` | `Authorization: Bearer $DEEPSEEK_API_KEY` | ids `deepseek-flash`, `deepseek-v4-pro`; reasoning may arrive as `reasoning_content`; supports explicit `thinking` |
| OpenRouter | `https://openrouter.ai/api/v1` | `chat/completions` | `openrouter/` | `Authorization: Bearer $OPENROUTER_API_KEY` | vendor model ids contain slashes and pass through unchanged; optional `HTTP-Referer` / `X-Title` attribution |
| OpenCode Zen | `https://opencode.ai/zen/v1` | per model: `/chat/completions` + `/messages` (routed); `/responses` rejected | `opencode/` | `Authorization: Bearer` on `/chat/completions`; `x-api-key` + `anthropic-version` on `/messages` | adapter chosen per model (`OpenCodeRouter`); GPT/Grok `/responses` fails explicitly |
| OpenCode Go | `https://opencode.ai/zen/go/v1` | per model: `/chat/completions` + `/messages` (routed); `/responses` rejected | `opencode-go/` | `Authorization: Bearer` on `/chat/completions`; `x-api-key` + `anthropic-version` on `/messages` | **requires `x-opencode-session`**; custom `User-Agent`; session-affine routing |

## Details

### DeepSeek — `https://api.deepseek.com`

- OpenAI `chat/completions` wire format (SSE).
- Model ids: `deepseek-flash`, `deepseek-v4-pro` (reference as
  `deepseek/<id>`).
- `Authorization: Bearer $DEEPSEEK_API_KEY`.
- Reasoning-capable models may emit `reasoning_content`; map to
  `ProviderEvent.ReasoningDelta`.

### OpenRouter — `https://openrouter.ai/api/v1`

- OpenAI `chat/completions` wire format (SSE).
- Model ids are upstream vendor ids and may contain slashes (e.g.
  `anthropic/claude-3.5-sonnet`); reference as `openrouter/<vendor>/<model>`
  and split on the **first** slash when resolving the provider.
- `Authorization: Bearer $OPENROUTER_API_KEY`.
- Optional attribution headers: `HTTP-Referer`, `X-Title`.

### OpenCode Zen — `https://opencode.ai/zen/v1`

- Exposes multiple endpoints; which one a given model uses is described by the
  Zen docs. spindle routes `/chat/completions` (everything else) and `/messages`
  (Claude-family/Qwen) per model; `/responses` (GPT/Grok) is **not implemented**
  and returns an explicit terminal `Failure` rather than a guessed payload.
- Model ids are referenced as `opencode/<id>`.
- Auth depends on the surface: `Authorization: Bearer` for `/chat/completions`,
  `x-api-key` + `anthropic-version` for `/messages`.
- Adapter selection is per model: OpenAI-format models use the
  `:provider-openai` adapter, Anthropic-format models use `:provider-anthropic`,
  dispatched by `OpenCodeRoutingProvider`.

### OpenCode Go — `https://opencode.ai/zen/go/v1`

- Same per-model surfaces as Zen: `/chat/completions` and `/messages`, routed by
  `OpenCodeRouter`; `/responses` is rejected with an explicit `Failure`.
- Model ids are referenced as `opencode-go/<id>`.
- Auth depends on the surface: `Authorization: Bearer` for `/chat/completions`,
  `x-api-key` + `anthropic-version` for `/messages` (see the auth-surfaces
  section below).
- **Hard requirement:** every request must include a stable
  `x-opencode-session: <sessionId>` header. spindle threads
  `ChatRequest.sessionHint` (= the session id) into this header, and the value
  must not change within a conversation.
- **Hard requirement:** send a stable custom `User-Agent` (e.g.
  `spindle/<version>`), not a client default.

## OpenCode Zen/Go auth surfaces

Verified live (2026-10). The surface determines the auth header — they are **not**
interchangeable:

| surface | auth | notes |
|---|---|---|
| `/chat/completions` | `Authorization: Bearer <key>` | works with a free-tier key for **free models only** (e.g. `space-bunny-free`) |
| `/responses` | `Authorization: Bearer <key>` | GPT/Grok-style routing; **not implemented** — spindle rejects it explicitly |
| `/messages` (Anthropic) | `x-api-key: <key>` + `anthropic-version` | a Bearer here → **401 "Missing API key"** |
| `/models` | none (public) | a 200 is **not** proof a key is valid — verify with an inference call |

- **Free tier blocks Claude/GPT:** returns **403 "Model access is disabled"** on
  `/messages` (Claude/Qwen) and `/responses` (GPT/Grok), and also on
  `/chat/completions`. Per-model routing is implemented + fixture-tested
  (`1845a77`, `/messages` ✅). **Live Claude/GPT verification is deliberately out
  of scope** by owner decision (OpenCode Go subscription only; no paid API
  credits will be added) — not blocked-pending-a-key. `/responses` stays
  intentionally dropped.
- **Go additionally requires** a stable `x-opencode-session: <sessionId>` and a
  custom `User-Agent` on every request (see the Go section above); a
  missing/changing session id can drop affinity.

## Live model catalogue (app)

The app keeps a live catalogue per provider, layered over the embedded
`ProviderCatalogue` snapshot:

- On app start and on provider switch/refresh, `data/ModelCatalogue.kt` fetches
  the provider's live `/models`, **merges** it over the embedded snapshot, and
  **caches** it on-device in SharedPreferences (`lumen.models`) so it survives a
  restart. The embedded snapshot is always the floor, so an offline cold start
  (or a corrupt cache) behaves as before.
- OpenRouter's catalogue is public but fetched lazily when it is selected; a
  keyed provider with a blank key skips the request. A failed refresh keeps the
  current list and records an error rather than emptying the UI.
- `ChatState.models` / `modelsRefreshing` / `modelsError` drive the full-screen
  `ModelPickerScreen` (route `"models"`), reachable from the composer model chip
  and Settings → "Browse all models".
- Reminder: `/models` is public (no auth), so a 200 is **not** proof a key is
  valid — verify with an inference call. The headless `:cli` path instead uses
  `SimpleProviderRegistry`, which caches `models()` once behind a mutex.

## Environment variables

| variable | used by |
|---|---|
| `DEEPSEEK_API_KEY` | DeepSeek |
| `OPENROUTER_API_KEY` | OpenRouter |
| `OPENCODE_API_KEY` | OpenCode Zen, OpenCode Go |

Live provider calls are manual only — see `.github/workflows/live.yml`. Never put
keys in code or in a workflow that runs on push/PR.
