# FEAT-01 · GitHub surface is far too thin

**Status:** `[ ]` open · Owner-reported ("almost everything on GitHub should
work").

## What exists today — measured, not estimated

| Surface | Implemented | Where |
|---|---|---|
| Verify token / who am I | ✅ `GET /user` | `GitHubClient.user()` |
| Repo lookup (exists? private? default branch?) | ✅ `GET /repos/{o}/{r}` | `GitHubClient.repo()` |
| Clone / fetch / pull / push / commit / diff / log / show / branch / remote / checkout / status | ✅ | `tools/GitTool.kt` (13 ops) |
| Commit/push UI (guarded, confirm dialog) | ✅ | `ui/GitHubScreen.kt` |
| **Issues** | ❌ | — |
| **Pull requests** | ❌ | — |
| **Releases** | ❌ | — |
| **Actions / workflow runs** | ❌ | — |
| **Search** (code / repos / issues) | ❌ | — |
| **Notifications** | ❌ | — |
| **Multi-repo / org browsing** | ❌ | — |

`GitHubClient` is **two methods**. That is the entire GitHub API surface.

## Why it feels useless in practice

The owner's words: the GitHub tool "not working good enough… I end up doing them
myself". From the code, the likely reasons:

1. **The agent can only touch a repo it already cloned.** There is no way to
   list repos, read an issue, or open a PR — so any task phrased "fix issue #12"
   or "open a PR" has no tool to call. The agent must improvise, and will fail.
2. **The `git` tool has no `merge`, `rebase`, `stash`, `tag`, `cherry-pick`,
   `blame`, or `config`.** Anything beyond the 13 ops needs `bash`, which is
   stripped of credentials — so a manual `git push` via bash cannot authenticate.
3. **`GitHubScreen` is a connect + commit + push panel**, not a GitHub client.

## Fix sketch — do this in stages, each shippable

### Stage 1 — make the git tool actually complete (small, high value)
Add the missing ops to `tools/GitTool.kt` **and** its JSON `enum` + description:
`merge`, `rebase`, `stash`, `tag`, `cherry-pick`, `blame`, `config`, `log` with
`--oneline -n`, `diff` with ranges. Keep the existing invariants:
- token only via process environment + one-shot `credential.helper`
- never in argv or URL
- never force-push
- redact from output

### Stage 2 — a GitHub REST tool for the agent (the real gap)
A new `github` tool with an `op` enum, so the model can drive GitHub directly:
`list_repos`, `get_issue`, `list_issues`, `comment_issue`, `open_issue`,
`list_prs`, `get_pr`, `create_pr`, `list_releases`, `create_release`,
`list_workflow_runs`, `rerun_workflow`, `search_code`, `search_issues`.

Implementation notes:
- Reuse `GitHubClient`'s transport pattern (never log the token; header only).
- These are **GET/POST JSON** calls — put a small typed layer on `GitHubClient`
  rather than leaking JSON into the tool.
- Every mutating op (comment, create issue/PR, release, rerun) must go through
  the **existing `ApprovalPolicy`** so the user confirms — the same gate that
  protects `git push`.
- Paginate and **cap** output (GitHub lists can be huge — see
  `docs/analysis/arch/CONTEXT.md`: an unbounded issue list is a context bomb).

### Stage 3 — the screen
Grow `GitHubScreen` from "connect + push" into: repo picker (from
`list_repos`), issues list, PR list, Actions status. Keep it consistent with the
existing token/spacing language in `ui/LumenTokens.kt`.

## Tests

- `GitHubClient` is built with an injectable `get: suspend (url, token) ->
  Response`, so **it is unit-testable without network** — this is already the
  established pattern. Use it.
- `tools/GitToolTest.kt` exists (JVM, authoritative). Extend for new ops; assert
  the token never appears in argv and is redacted from output.
- Any new `github` tool: JVM-testable with a fake transport.

## Non-goals (keep)

Do not build OAuth, a full GitHub client UI, or webhooks. Token/PAT only, exactly
as today.

## Related

- `docs/analysis/features/GIT-TOOL.md`
- `docs/guide/03-tools.md` (tool conventions, containment, approval)
