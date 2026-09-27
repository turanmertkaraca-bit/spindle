# Providers

Four providers ship in v1. All use `Authorization: Bearer <api key>`. Model
references passed to the agent are `provider/model`, e.g.
`deepseek/deepseek-flash` or `opencode/claude-sonnet-4`.

## Table

| provider | base URL | wire format | model-id prefix | auth | notes |
|---|---|---|---|---|---|
| DeepSeek | `https://api.deepseek.com` | `chat/completions` | `deepseek/` | `Authorization: Bearer $DEEPSEEK_API_KEY` | ids `deepseek-flash`, `deepseek-v4-pro`; reasoning may arrive as `reasoning_content`; supports explicit `thinking` |
| OpenRouter | `https://openrouter.ai/api/v1` | `chat/completions` | `openrouter/` | `Authorization: Bearer $OPENROUTER_API_KEY` | vendor model ids contain slashes and pass through unchanged; optional `HTTP-Referer` / `X-Title` attribution |
| OpenCode Zen | `https://opencode.ai/zen/v1` | `/chat/completions`, `/messages`, `/responses` (per model) | `opencode/` | `Authorization: Bearer $OPENCODE_API_KEY` | adapter chosen per model; endpoint varies by model |
| OpenCode Go | `https://opencode.ai/zen/go/v1` | `/chat/completions`, `/messages`, `/responses` (per model) | `opencode-go/` | `Authorization: Bearer $OPENCODE_API_KEY` | **requires `x-opencode-session`**; custom `User-Agent`; session-affine routing |

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
  Zen docs. Supported surfaces: `/chat/completions`, `/messages`, `/responses`.
- Model ids are referenced as `opencode/<id>`.
- `Authorization: Bearer $OPENCODE_API_KEY`.
- Adapter selection is per model: OpenAI-format models use the
  `:provider-openai` adapter, Anthropic-format models use `:provider-anthropic`.

### OpenCode Go — `https://opencode.ai/zen/go/v1`

- Same endpoint surfaces as Zen: `/chat/completions`, `/messages`, `/responses`,
  selected per model.
- Model ids are referenced as `opencode-go/<id>`.
- `Authorization: Bearer $OPENCODE_API_KEY`.
- **Hard requirement:** every request must include a stable
  `x-opencode-session: <sessionId>` header. spindle threads
  `ChatRequest.sessionHint` (= the session id) into this header, and the value
  must not change within a conversation.
- **Hard requirement:** send a stable custom `User-Agent` (e.g.
  `spindle/<version>`), not a client default.

## Environment variables

| variable | used by |
|---|---|
| `DEEPSEEK_API_KEY` | DeepSeek |
| `OPENROUTER_API_KEY` | OpenRouter |
| `OPENCODE_API_KEY` | OpenCode Zen, OpenCode Go |

Live provider calls are manual only — see `.github/workflows/live.yml`. Never put
keys in code or in a workflow that runs on push/PR.
