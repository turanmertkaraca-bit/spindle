# Lumen / spindle — developer guide

One file per subsystem, so you can read only the part you are changing. This
index says what exists, where it lives, and which file to open next.

Every page below was written against HEAD `4ff37c0` (CI green on that sha) and
names the commit ranges that last touched the area. If a page's stamp predates
HEAD, re-check its claims before trusting it — the code moves.

## The shape of the repo

Nine Gradle modules. `:core` has **no Android dependency**, so the whole agent
backend runs and is tested on a plain JVM.

| module | kt files | lines | role |
|---|---:|---:|---|
| `:core` | 59 | 10,150 | domain model, agent loop, events, provider/tool/store SPIs |
| `:sandbox` | 4 | 968 | process/shell sandbox abstractions |
| `:tools` | 30 | 6,178 | the 14 default tools |
| `:provider-openai` | 4 | 2,069 | OpenAI-compatible streaming (`chat/completions`) |
| `:provider-anthropic` | 2 | 1,125 | Anthropic-style streaming (`messages`) |
| `:store-sqlite` | 4 | 2,278 | durable `SessionStore` on SQLite (JDBC) |
| `:server` | 6 | 1,029 | optional HTTP host for the loop; **the app does not use it** |
| `:cli` | 4 | 905 | headless harness + live smoke tests |
| `:app` | 120 | 36,220 | the Android/Compose UI (`dev.lumen.app`) |

## Pages

| Page | Covers | Open it when |
|---|---|---|
| [`01-agent-loop.md`](01-agent-loop.md) | `:core/agent` — the loop, step budgets, tools execution, retries, compaction, subagents | you are changing run behaviour or anything that could loop forever |
| [`02-storage.md`](02-storage.md) | `:store-sqlite`, `:app/data` — schema, migrations, leases, snapshots, projects | you touch SQL, migrations or session persistence |
| [`03-tools.md`](03-tools.md) | `:tools` — the 14 tools, path containment, approval, output limits | you add or change a tool |
| [`04-shell-and-proot.md`](04-shell-and-proot.md) | `:app/platform` — guest shell, proot/Debian, admission control, resource limits | you touch command execution or sandbox policy |
| [`05-background-service.md`](05-background-service.md) | `:app/platform/RunService` — foreground service, wake lock, notifications | you touch run lifecycle in the background |
| [`06-ui.md`](06-ui.md) | `:app/ui` — Compose screens, design tokens, known perf findings | you work on the UI |
| [`07-testing.md`](07-testing.md) | how to build and test here, and what CI is authoritative for | before you claim anything works |
| [`08-conventions.md`](08-conventions.md) | invariants, gotchas and hard-won rules | always, once |

## Sibling docs (pre-existing, different purpose)

- `../HANDOFF.md` — session narrative; **partly stale**, see `../audit/09-docs.md`.
- `../SPEC.md` — the original specification.
- `../audit/` — the read-only audit that seeded the stability work. Pinned at
  `d17dbd2`, so several findings are already fixed.
- `../PROJECT-STATUS.md` — the live board. Start here for "what is left".

## The one rule that matters

**A local `:app` test run is not a pass.** Robolectric cannot execute in the dev
sandbox (missing conscrypt native for aarch64), so a change can compile, "pass"
locally, and still be red on CI. Five real regressions shipped that way during
the stability wave. See [`07-testing.md`](07-testing.md).
