# FEAT-02 · The agent's `git` tool is not good enough for real work

**Status:** `[ ]` open · Owner-reported ("the github tool that I made for the
agent not working good enough").

Companion to `GITHUB.md` (which covers the *API* surface). This file is about the
**local git tool** the agent actually calls.

## What the tool has

`tools/src/main/kotlin/dev/spindle/tool/GitTool.kt` — 13 ops:

```
status  diff  log  show  branch  remote  add  commit  clone  fetch  pull  push  checkout
```

Good invariants it already gets right (do not regress):

- Token passed **only** via the process environment + a one-shot
  `credential.helper` — never argv, never the remote URL.
- Token **redacted from output**.
- `push` is **never forced**.
- Denied for the read-only `explore` / `plan` agents.

## Where it falls short

| Missing | Why the agent needs it |
|---|---|
| `merge` | "merge the feature branch" is a normal request |
| `rebase` | resolving divergence after a fetch |
| `stash` / `stash pop` | switching context without losing work |
| `tag` | cutting a release |
| `cherry-pick` | porting a fix |
| `blame` | "when/why did this line change" |
| `config` (read) | discovering remotes/user/branch settings |
| `diff` with a range | `diff main...HEAD` is how you review a branch |
| `log` range/author filters | browsing history usefully |
| `reset --soft` (guarded) | undoing a bad commit without losing files |
| `remote set-url` / credential check | diagnosing auth failures |

**Critically:** the fallback is `bash`, and the sandbox bash is **deliberately
stripped of `GITHUB_TOKEN`** (by design — see `docs/guide/04-shell-and-proot.md`,
env scrubbing). So a manual `git push` from bash **cannot authenticate**. If the
`git` tool lacks an op, the agent cannot work around it. That is the root of
"it doesn't work well enough".

## Fix sketch

1. **Extend the `op` enum** (and the JSON schema `description`) with the ops
   above. The dispatch is a single `when (op)` around line 120 — add branches
   there.
2. **Classify each op** as read-only vs mutating, and route mutating ones
   through the approval gate exactly like `push` today.
3. **Never add an op that can destroy uncommitted work** (`reset --hard`,
   `clean -fd`, `push --force`) without an explicit, separate confirmation
   string — or simply do not add them. The owner is not asking for them.
4. **Improve error messages.** A failed `git push` today returns raw stderr. Map
   the common failures to actionable text: auth rejected, non-fast-forward
   (tell it to `pull --rebase`), protected branch, no upstream set.
5. **Return less.** `git log` / `diff` on a big repo can be enormous — respect
   the output cap and prefer `--stat` / `-n` defaults (see
   `docs/analysis/arch/CONTEXT.md`).

## Test it the way it is already tested

`tools/src/test/kotlin/dev/spindle/tool/GitToolTest.kt` is a **JVM test**
(authoritative locally) using a fake `ShellExecutor`. For each new op assert:

- the exact argv produced;
- the token is absent from argv and redacted from output;
- mutating ops are refused for `explore`/`plan` agents.

Run: `./gradlew :tools:test`.

## Definition of "good enough" (owner-facing)

The agent should be able to, without the user touching a terminal:

- clone a private repo,
- create a branch, commit, and push it,
- open a PR for it (needs `GITHUB.md` stage 2),
- read and act on an issue,
- recover from a non-fast-forward push,
- and show a diff a human can review.

That list is the acceptance test for FEAT-01 + FEAT-02 together.
