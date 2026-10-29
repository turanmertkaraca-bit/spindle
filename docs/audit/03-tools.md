# Tools — sandbox & path-safety audit

`ExternalDirectoryTool` (`spindle/tools/src/main/kotlin/dev/spindle/tool/ExternalDirectoryTool.kt`) gives the agent **read-only** access to directories the user has explicitly granted outside the session working directory. It is constructed with a list of absolute `grantedRoots` (empty by default, in which case every request is denied at line 63–65), accepts an `op` of `list`/`stat`/`read`, and for each call resolves the requested `path` against those roots, asks the user for permission on the resolved path + root, and then lists the directory (bounded to `EXTERNAL_MAX_ENTRIES + 1` rows before sorting), returns `BasicFileAttributes`, or reads a UTF-8 line window with `offset`/`limit`. Containment is intended to be enforced by canonicalizing with `normalize()` + `toRealPath()` and requiring the result to sit under a real granted root.

## Findings

| # | Severity | Finding | Line | Why it matters |
|---|---|---|---|---|
| 1 | P1 | Time-of-check/time-of-use: containment is verified against a *path name* in `resolve()` (and again in `containedRoot()`), but the actual access later re-traverses the same name via `Files.list`/`Files.size`/`newBufferedReader`, none of which pin the inode. Nothing is re-verified after the permission prompt. | 70, 81–85, 102, 191, 250 | A user or process with write access inside a granted root can swap an intermediate directory for a symlink between the check and the open. The final open follows the symlink, so `read` returns an arbitrary file outside all roots. This is the one hole that makes "cannot become a general sandbox escape" (line 21) untrue. |
| 2 | P2 | Hard links are not addressed. Containment is decided purely by path, and `toRealPath()` cannot reveal that a name inside a root is a second link to an inode elsewhere on the same filesystem. | 300, 310 | The session user owns its own `~/.ssh/id_rsa`, so with a granted directory on the same filesystem it can hard-link that file into the root and read it. `protected_hardlinks` does not help, because the linker is the owner. No link-count or inode check exists anywhere. |
| 3 | P2 | When the requested path does not exist, `resolve()` returns the **deepest existing ancestor** as the resolved path, so the tool silently operates on a different path than the caller asked for. The permission prompt (line 75) therefore shows the ancestor, never the raw request. | 280–289, 315–321, 75 | `list /root/missing-subdir` succeeds by listing `/root` and reporting `path: /root`, so the model believes it observed the subdirectory. The human approves a path that is not the path the model named — a prompt-fidelity problem in a consent gate. |
| 4 | P2 | `read()` performs its size guard with a separate `Files.size(path)` call and then opens the file by name; growth or replacement between the two is unchecked. A file under the limit at check time can be arbitrarily large at read time. | 190–202, 212–216, 250 | The byte cap is advisory, not enforced on the descriptor actually read. Combined with finding 1 this permits unbounded memory/CPU on a single call. |
| 5 | P2 | `readWindow()` stops after `Limits.READ_MAX_SCAN_LINES` and returns that as `totalLines`, but callers treat it as the true line count: the "past the end" rejection and the `(lines X–Y of N)` header both use it. | 217–223, 255 | On a long file the tool wrongly rejects valid offsets ("Offset N is past the end ... (M lines)") and under-reports the total, so the agent cannot page through large logs. The reported total is a scan cap, not a file property. |
| 6 | P2 | The constructor accepts any absolute path as a granted root, including `/`, the user's home, or the working directory itself; there is no floor and no deny-list for credential locations. Roots are also stored lexically (`toAbsolutePath().normalize()`) while containment is checked against real paths. Separately, `stat` imposes no type floor: FIFOs, device nodes and sockets reach `readAttributes`. | 26–28, 144–159, 304–310 | Granting `/` or a home directory turns this into a whole-filesystem reader whose only control is one prompt. Because roots are not canonicalized at construction, the string shown to the user in the prompt can differ from the path actually compared against. `stat` on a device node also discloses type/size and confirms existence of things outside the intended "read a text file" scope. |
| 7 | P3 | Type checks mix follow and no-follow semantics: `Files.exists(..., NOFOLLOW_LINKS)` at 180/90 but `Files.isDirectory`/`isRegularFile`/`Files.size` follow links. | 90, 180, 183, 186, 191 | The pre-open classification does not describe the object that will actually be opened. A FIFO or device swapped in after the checks makes `newBufferedReader` block indefinitely on the calling thread. |
| 8 | P3 | Empty or blank `path` is not rejected: `Path.of("")` is a valid empty relative path, and `root.resolve("")` is the root itself. | 269–277 | `op: "list", path: ""` dumps the entire granted root without naming any target, which is a broader exposure than the caller requested. |
| 9 | P3 | `metadata["total"]` is set to `shown.size()`, i.e. the truncated count, not the true entry count. | 138 | When `truncated=true` the agent is told there are exactly `cap` entries, so it has no signal that the listing is incomplete and cannot resume. |
| 10 | P3 | `list` swallows per-entry attribute failures with a bare `continue`, silently dropping entries. | 110–114 | Entries the user cannot stat disappear from the listing without any note, so the model may conclude a file does not exist when it is merely unreadable. |
| 11 | P3 | Two error paths bypass the `ToolOutcome`/denial discipline: `input.requireString("path")` at 66 is unguarded, and a failed `toRealPath()` (symlink loop, permission denied, I/O error) is flattened into "Path is outside all granted external roots" at 289/299–302. Whether `requireString` throws is **unverified** (helper defined outside this file). | 66, 289, 299–302 | A malformed call may escape as a stack trace instead of a model-readable error, and a permissions or loop error is indistinguishable from a policy denial, so both the user and the agent draw the wrong conclusion about why access failed. |
| 12 | P3 | Every call issues a fresh permission request keyed on the tool name plus the resolved path; whether `ToolContext` caches "always allow" per root is **unverified** (defined outside this file). | 75 | If the context caches by tool name or by granted root, one approval covers every subsequent read anywhere under that root, so the per-call consent shown in this file is weaker than it looks. |

## Path-escape analysis

**Lexical `..` and absolute paths: closed.** Resolution normalizes before comparing, then canonicalizes:

```kotlin
val candidates = if (parsed.isAbsolute) {
    listOf(parsed.normalize())
} else {
    roots.map { it.resolve(parsed).normalize() }
}
```

```kotlin
val anchor = if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
    candidate
} else {
    deepestExistingAncestor(candidate) ?: return null
}
val realAnchor = try { anchor.toRealPath() } catch (e: Exception) { return null }
for (root in roots) {
    val realRoot = try { root.toRealPath() } catch (e: Exception) { continue }
    if (realAnchor == realRoot || realAnchor.startsWith(realRoot)) return realAnchor to realRoot
}
```

`normalize()` removes `..` lexically, so `/granted/../etc` becomes `/etc` and fails the `startsWith(realRoot)` test. `toRealPath()` then resolves every symlink component, so `/granted/link -> /etc` also lands on `/etc` and is refused. A dangling or looping final element makes `toRealPath()` throw and yields `null`, i.e. denial. Note the subtlety that `NOFOLLOW_LINKS` only affects the *final* element, so intermediate symlinks are followed — which is what we want here, since the canonical result is what gets compared. In short, the static cases (`..`, absolute, symlink-in-path) are handled correctly and the "outside all granted roots" refusal at line 289 is the right default.

**Three residual escapes.** First, the **TOCTOU** in findings 1 and 4: the canonical path is computed once and then used as a plain name for `Files.list`, `Files.size` and `newBufferedReader`. Since the canonical result of a symlink is the *target's* path, the usual attack is to replace a checked intermediate directory with a symlink after the prompt; the open follows it. Second, **hard links** (finding 2) cannot be detected by any path-based check and are not mitigated at all. Third, the **non-existent-path fallback** at line 288 returns the deepest existing ancestor, so the object acted on is not the object requested — this does not cross the root boundary, but it does mean the consent prompt names a path the caller never asked for. The "downloads/external-directory exception" here is only the explicit `grantedRoots` allow-list; with `roots.isEmpty()` everything is denied (line 63), and there is no separate downloads carve-out in this file. The gap is that nothing constrains *which* roots a host may grant, so `/` or `$HOME` is accepted without objection.

## Highest-leverage fix

Make containment verification atomic with access instead of sequential. Open each target once — for `read`, open a `FileChannel`/`SecureDirectoryStream` and then verify the *open descriptor's* real path (`Files.readAttributes(..., NOFOLLOW_LINKS)` on the descriptor, or `toRealPath()` compared against the real root) before consuming a single byte; for `list`, open the directory with `SecureDirectoryStream` and enumerate relative to that handle, refusing to descend through anything that is not a plain directory. Additionally, canonicalize `grantedRoots` in the constructor with `toRealPath()`, refuse roots that equal or contain the working directory or the user's home, and document that hard links within a granted filesystem remain an accepted risk. This closes findings 1, 4 and 7 at once and removes the "cannot become a general sandbox escape" claim's false comfort without changing the tool's surface area.
