# BUG-01 · Every model shows "free"

**Status:** `[x]` **FIXED in `c4408c6`** · Owner-reported · **Root cause confirmed by reading
the code, not guessed.**

## Symptom

The model picker lists every model with a cost of `free`, including models that
are plainly not free.

## Root cause (exact)

`app/src/main/kotlin/dev/lumen/app/ui/ModelPickerScreen.kt`, in
`modelRowData()` (~line 84–97):

```kotlin
cost = if (m.inputCostPerM > 0.0 || m.outputCostPerM > 0.0) {
    buildList {
        if (m.inputCostPerM > 0.0) add(String.format(Locale.US, "%.2f", m.inputCostPerM))
        if (m.outputCostPerM > 0.0) add(String.format(Locale.US, "%.2f", m.outputCostPerM))
    }.joinToString("/")
} else {
    // A model that advertises no price is free, not "unknown": draw the word
    // so the blank cost slot never reads as a bug or a fetched-nothing state.
    "free"
}
```

So **`cost == 0.0` is rendered as the word `free`.** That conflates two
different things:

| Case | Correct label |
|---|---|
| Model genuinely costs $0 (e.g. `openrouter/free`) | `free` |
| Provider advertised no price / price not parsed | **unknown** — not "free" |

The owner sees "free" everywhere, which means most `ModelInfo`s reaching this
function have both costs at `0.0`. That is the real question: **why are prices
zero?**

## Why prices are zero — three contributing causes

Read all three before fixing; fixing only the label hides the data problem.

### (a) The live `/models` parse may not find the price fields
`provider-openai/src/main/kotlin/dev/spindle/provider/openai/OpenAiProvider.kt`
~line 153–159 reads OpenRouter-style `pricing.prompt` / `pricing.completion`:

```kotlin
inputCostPerM  = perTokenPrice(pricing?.get("prompt"))
outputCostPerM = perTokenPrice(pricing?.get("completion"))
```

If the provider's `/models` payload does not carry `pricing` (or names it
differently), every live model arrives with `0.0`.

### (b) The overlay only lets a **positive** live price win

`app/src/main/kotlin/dev/lumen/app/data/ProviderCatalogue.kt` ~line 120:

```kotlin
inputCostPerM  = live.inputCostPerM.takeIf  { it > 0.0 } ?: inputCostPerM,
outputCostPerM = live.outputCostPerM.takeIf { it > 0.0 } ?: outputCostPerM,
```

This is correct behaviour (live positive wins, else keep the embedded
snapshot), **but** it means:
- if live is `0.0` **and** the embedded snapshot is also `0.0`, the result is
  `0.0` → rendered "free";
- models that exist **only** in the live list (not in the embedded snapshot) get
  the `ModelInfo` default `0.0` with nothing to fill it.

### (c) The embedded snapshot is tiny
`ProviderCatalogue.kt` carries only **3** models for `opencode-go`, **2** for
`deepseek`, **3** for `openrouter`. Any model beyond those has no price to fall
back on.

## Fix sketch (three parts — do all three)

1. **Distinguish unknown from free in the data model.** Add an explicit signal
   rather than overloading `0.0`. Options, pick one and be consistent:
   - make prices nullable (`Double?`) where `null` = unknown — cleanest, but
     touches `ModelInfo`, the wire JSON, and the cost sort; or
   - add `val pricingKnown: Boolean` to `ModelInfo` — smaller blast radius.
2. **Only print `free` when the model is genuinely zero-cost.** Otherwise print
   `—` (or omit the cost column). Keep the tooltip/subtitle honest.
3. **Widen price coverage** so "unknown" is rare: confirm what the configured
   providers actually return from `/models` and parse it. Check whether
   `pricing` is present at all for the `opencode-go` and `deepseek` endpoints.

## Tests to add

- A model with `input=0.0, output=0.0` **and** `pricingKnown=false` renders
  "unknown", not "free".
- A model with `input=0.0, output=0.0` and `pricingKnown=true` (e.g.
  `openrouter/free`) renders "free".
- A priced model renders e.g. `0.28/0.42`.
- `ModelSort.Cost` still sinks unknown-price models to the end
  (`ModelPickerScreen.kt` ~line 486) and must not change silently.

## Where the tests live

`app/src/test/kotlin/dev/lumen/app/ui/ModelPickerScreenTest.kt` exists and
already covers filtering/sorting — extend it. Note `:app` tests are
**CI-only** (see `docs/guide/07-testing.md`).

## Verification

`:app` compiles locally; **behaviour is verified on CI only.** Push, wait for
the `android app (robolectric)` job, and confirm green before marking done.

---

## Done (c4408c6) — CI green on both jobs

Implemented the `pricingKnown` flag route (not nullable prices) because it
touches far fewer call sites and is serialization-safe.

- `ModelInfo.pricingKnown` (default **false** = unknown) + `isFree` helper that
  requires a reported, genuinely-zero price.
- Bundled snapshot: all 8 priced models marked known; `openrouter/free` is
  known-and-zero (the only true "free").
- Live `/models` parse marks `pricingKnown` only when the payload carried a
  price; the overlay is sticky (`pricingKnown || live.pricingKnown`).
- UI: `modelCostLabel()` → `"0.28/0.42"` | `"free"` | `"—"` (unknown).
- Cost sort ranks known prices above unknown, so an unpriced model cannot top a
  "most expensive" list.

Tests: `modelCostLabel` all three cases, cost-sort with an unknown model,
`ModelInfo.isFree` (unknown / confirmed-free / priced / half-free).

**Still open as a follow-up:** the underlying *why* — whether the configured
providers actually return `pricing` from `/models` at all. If they do not, the
UI is now honest ("—") but most models will still show unknown. Verify against a
real `/models` response before deciding whether more parsing is needed.
