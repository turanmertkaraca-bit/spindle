# Tools (`:tools`)

Verified against HEAD `4ff37c0`. 30 files / 6,178 lines, no Android dependency.

## The registry

`DefaultTools.registry(shell, skillsRoot, externalRoots)` builds the 14 built-ins:

```
ReadTool, WriteTool, EditTool, BashTool, ApplyPatchTool, GlobTool, GrepTool,
TodoWriteTool, WebFetchTool, WebSearchTool, QuestionTool, TaskTool, SkillTool,
ExternalDirectoryTool
```

`GitTool` is **not** in `DefaultTools`. The app wires it explicitly in
`ChatViewModel` (~line 3641) because git needs app-specific credentials. When
you look for git behaviour, search the app, not the registry.

`:server` and `:cli` have their own registration. A tool added to `DefaultTools`
appears there automatically; the reverse is not true.

## Containment — the invariant that protects the user

Every path argument goes through `resolveInsideCwd` (`Support.kt:143`). It
resolves symlinks and normalises before checking, then throws
`IllegalArgumentException("Path escapes the working directory: …")` on any
escape. There are four separate `throw` sites in `Support.kt`; **do not add
another code path that builds a `Path` from a tool argument without going
through this function.**

`ExternalDirectoryTool` is the deliberate, explicit exception: it only reaches
roots the user granted (`externalRoots`), not arbitrary paths.

## Output limits

Tool output is clipped/spilled at `maxToolOutputChars` (`AgentLoop`, default
60,000) before persistence, because the result is re-sent to the provider on
every subsequent turn — an unbounded tool result is a permanent context tax.

`WebFetch` additionally caps the fetched body. `BashTool` bounds head/tail
separately so both the start (the command) and the end (the error) survive
truncation.

## Approval

Each tool call is classified by `ApprovalPolicy` into allow / ask / deny.
`PermissionGate` is only the **ask transport**; the decision layer is separate.
A tool must not implement its own confirmation UI.

## Per-tool notes

- **BashTool** — needs a `ShellExecutor`. On the host that is
  `HostShellExecutor` (which returns `null` from `openPty`, by contract). The app
  supplies `AndroidShellExecutor`, which is where sandboxing, admission control
  and resource limits live — see `04-shell-and-proot.md`.
- **EditTool** — exact-match replace; must fail loudly on a missing or ambiguous
  needle rather than editing the wrong occurrence.
- **ApplyPatchTool** — multi-file; partial application is a bug. Validate the
  whole patch before writing anything.
- **GrepTool / GlobTool** — must respect ignore rules and stay inside `cwd`.
- **SkillTool** — reads `skillsRoot`; `null` disables it (the host has no skills).
- **TaskTool** — spawns a subagent; honours `AgentConfig.allowSubagents`.
- **QuestionTool** — delegates to the injected `QuestionGate`; must return an
  empty list, not throw, when nobody can answer.

## Testability

`GrepTool` (quadratic de-dup was fixed) and `GrepTool`/`GlobTool` have unit tests
in `tools/src/test` — 105 tests, green on a plain JVM. When adding a tool:

1. Return a `ToolResult` with `isError` set rather than throwing for expected
   failures (a throw is reserved for programming errors).
2. Set `timeoutMs = 0` only if the tool genuinely manages its own deadline;
   otherwise the loop wraps it.
3. Add the path-resolution test — it is the one that prevents a sandbox escape.
