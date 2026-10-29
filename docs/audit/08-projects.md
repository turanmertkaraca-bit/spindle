# Projects — store & screen audit

Every project is one direct child directory of `filesDir/projects`, and the active project's folder is handed to the agent as its working directory, so workspaces are isolated by directory rather than by any in-app filtering. `ProjectStore` owns creation, listing, activation, deletion and a one-time migration of the legacy `filesDir/workspace` tree, while `ProjectsScreen` is a thin picker over a pre-built `List<ProjectRow>`: it renders name/session-count/active badge, offers "New" (name dialog), "Delete" (confirm dialog), "Resume newest session" and a switch tap — every action is a callback upward (`onCreate`/`onSwitch`/`onDelete`/`onOpenSession`), and the screen never touches the filesystem or the store directly. Note: the requested path `app/.../ProjectStore.kt` does not exist; the file is at `app/.../data/ProjectStore.kt` and all store citations below refer to it.

## Findings

| # | Severity | Finding | File:line | Why it matters |
|---|---|---|---|---|
| 1 | P1 | Display-name metadata and the active pointer are rewritten *before* the folder delete is attempted, and never rolled back when it fails | ProjectStore.kt:115-120 | A partial/failed `deleteTree` leaves the project folder still on disk but with its name pref removed (it silently renames itself to its slug id) and the active pointer moved elsewhere — user sees a mangled "ghost" project and lost "active" state |
| 2 | P1 | Duplicate display names are allowed; only the id is de-duplicated | ProjectStore.kt:95, 158-166; ProjectsScreen.kt:247-264 | Creating "Work" twice yields ids `work` and `work-2` but both rows display "Work" with no path/id hint, so the user cannot tell which folder holds which files |
| 3 | P1 | Create failure is swallowed: store returns `null`, screen closes the dialog and ignores the result | ProjectStore.kt:86-97; ProjectsScreen.kt:162-166 | On a failed `mkdirs()` the dialog just disappears with no project and no message; the screen's `error` slot (line 102-111) is never fed by the create path |
| 4 | P1 | No rename API in the store, despite the class doc claiming rename "never has to move bytes" | ProjectStore.kt:23-25 vs 86-121 | The design justifies storing the name in prefs for cheap renames, but there is no `rename(id, name)`; users must delete and recreate a project (losing all files) to change a label |
| 5 | P2 | `activeOrNull()` resolves the persisted id without `isSafeId`, unlike `setActive`/`delete` | ProjectStore.kt:65-70 vs 73-79, 104-113 | A tampered or restored pref value such as `..` resolves to `filesDir` and would be adopted as the agent's working directory; the guard exists everywhere except on the restore path (unverified whether anything besides `setActive` writes `KEY_ACTIVE`) |
| 6 | P2 | `ensure()` throws `checkNotNull` when the fallback folder cannot be created | ProjectStore.kt:48-50 | On full or read-only storage this is an uncaught startup crash instead of a degraded "no workspace" state |
| 7 | P2 | Migration sets `KEY_MIGRATED = true` even when individual children fail to move/copy, and is never retried | ProjectStore.kt:42-45, 131-141 | Files that hit a busy/failed rename+copy stay in the old `workspace` tree forever, invisible to the agent and to the picker; collisions are also silently skipped (`dest.exists() → continue`) |
| 8 | P2 | `create()` does not make the new project active | ProjectStore.kt:86-97; ProjectsScreen.kt:162-166 | The user creates "research" and is left in the previous project; the natural expectation is that "New" switches you into the new folder (nothing in the screen compensates) |
| 9 | P2 | The active row's switch clickable is disabled and there is no "open" action | ProjectsScreen.kt:239-243 | The highlighted row is inert — tapping it does nothing, so the only ways into the active project are "Resume" or backing out; the border highlight implies interactivity that is not there |
| 10 | P2 | Every delete affordance carries the same `contentDescription = "delete project"` | ProjectsScreen.kt:266-278 | With several projects, TalkBack announces identical labels with no project name, so a screen-reader user cannot know which folder the "×" will destroy |
| 11 | P2 | No ordering, recency or metadata: `list()` is always name-ascending and `Project` carries only `id`/`name`/`dir` | ProjectStore.kt:8-12, 55-62 | There is no created/last-opened timestamp in the store, so "recently used", pinning or manual ordering cannot be implemented; the screen's `sessionCount`/`lastSession` come from `ProjectRow` elsewhere (line 42 import), so the store is not the source of truth for the dashboard's own data |
| 12 | P3 | On deleting the active project, the next active is the alphabetically first survivor | ProjectStore.kt:116 | Arbitrary jump; the previous project or the most recently used would be less disorienting |
| 13 | P3 | The 48-character name cap is hardcoded in the UI while the store has `MAX_NAME = 48` | ProjectsScreen.kt:340; ProjectStore.kt:193 | Two copies drift; a store-side change to the cap silently truncates mid-dialog |
| 14 | P3 | Class doc contradicts `create()`'s comment about where the display name lives | ProjectStore.kt:23-25 vs 93-95 | Doc says a hidden `.project` marker inside the folder; the code deliberately uses prefs "so the Files view and the agent only ever see the user's own files" — the doc misleads future readers about the on-disk contract |

## Isolation analysis

Path construction is `root = File(app.filesDir, "projects")` (line 34) and each project is `File(root, id)` (line 91); `id` is a slug of the typed name:

```kotlin
private fun slug(name: String): String {
    val base = name.lowercase()
        .replace(Regex("[^a-z0-9._-]+"), "-")
        .trim('-', '.', '_')
        .take(MAX_ID)
    return base.ifBlank { "project" }
}
```

A name therefore cannot escape the root through `create()`: `/` and `\` and whitespace collapse to `-`, and the trim removes leading/trailing dots, so `".."` → `""` → `"project"` and `"../../etc"` → `"--etc"` → `"etc"`. Dots are allowed *inside* a slug (`a..b`), but never as the whole id, and the lowercasing plus `uniqueId` (`File(root, candidate).exists()` loop, lines 158-166) keeps ids unique. The read/activate/delete paths additionally gate on:

```kotlin
private fun isSafeId(id: String): Boolean =
    id.isNotBlank() && !id.contains('/') && !id.contains('\\') && id != "." && id != ".."
```

`setActive` (line 74) and `delete` (line 105) call it; `delete` further canonicalises and requires the folder to be a direct child:

```kotlin
if (canonicalDir.parentFile != canonicalRoot) return false
```

and `deleteTree` refuses to follow symlinks (`Files.isSymbolicLink(file.toPath())` deletes the link, not its target, lines 172-183), so a planted symlink to another project or to app data cannot be used to wipe foreign bytes. The real hole is `activeOrNull()`, which builds `File(root, id)` from prefs with **no** `isSafeId` check — the only reason it is not exploitable today is that `setActive` is the sole writer of `KEY_ACTIVE`, so the safety of the active cwd rests on pref integrity rather than on validation at the read site.

What actually stops the agent in project A from reading project B is **not** in these two files: the store's own doc says "the tool layer already refuses paths outside the cwd" (lines 16-18), and the screen merely asserts "The agent only sees the active folder" (line 95). Enforcement is delegated entirely to the tool layer plus the cwd it is given. Two consequences to verify elsewhere: (a) if that layer compares paths by string prefix, `projects/playground` also prefixes a sibling `projects/playground-2`, so a prefix check would leak across projects; (b) dot-prefixed directories are filtered out of `list()` (line 58) but are still ordinary paths, so a hidden folder under `projects/` would be invisible in the picker yet reachable by path if the tool layer's check is permissive. On delete, the folder and all contents go through `deleteTree`, but no cascade exists for data stored elsewhere — session rows keyed to the deleted project id live in another store and are not cleaned up here (unverified), and prefs keep no tombstone, so a re-created slug (`playground-2` reuse is possible after deletion) can inherit a stale name key from an unrelated project if the `remove(nameKey(id))` at line 119 failed to apply.

## Project-management gaps

The store supports exactly four verbs — `ensure`, `list`, `setActive`, `create`, `delete` — and the screen exposes the last three. What a project-management surface would expect and neither layer has:

- **No rename.** The name lives in prefs specifically to make rename cheap (doc lines 23-25), but no setter exists. `ProjectNameDialog` is already parameterised as `title`/`confirm` and is only ever invoked with `"New project"`/`"Create"` (screen lines 156-167) — dead generality that a rename flow could use for free.
- **No per-project settings or metadata.** `Project` is `id`, `name`, `dir` only. No description, colour/icon, created-at, last-opened-at, default model, permission scope, or system prompt per project.
- **No archive/trash/undo.** `delete` is permanent and the dialog says so ("This cannot be undone.", line 178); there is no soft-delete, no trash folder, no restore.
- **No ordering or pinning.** `list()` always sorts `compareBy({ it.name.lowercase() }, { it.id })`; there is no manual order, no "recent" ordering, no favourites, and no persistence for either.
- **No lifecycle beyond create/delete.** No duplicate/clone, no import/export, no move of files between projects, no size or quota display, no empty-state per folder beyond the session count the screen computes elsewhere.
- **Single global active project.** `KEY_ACTIVE` is one string; there is no per-project "last active session" or remembered scroll/route, and `create` does not set it at all.
- **No safety affordances on delete.** The last project is protected in both layers (`all.size <= 1` at store line 107; `canDelete = projects.size > 1` at screen line 146), but there is no "move files to another project first" option, no typed confirmation for large folders, and no size warning.

## Highest-leverage fix

Make the store the single source of truth for project metadata and make every prefs mutation transactional with the filesystem: add `rename(id, newName)` (reusing the existing name-in-prefs design so nothing moves on disk), call `setActive(id)` at the end of a successful `create`, and in `delete` remove the name/active keys **only after** `deleteTree` reports success — restoring the previous name and active pointer when it does not. That one change closes findings 1, 4 and 8, gives the screen a rename affordance it already has the dialog plumbing for, and stops a failed delete from producing a nameless ghost folder or an orphaned active pointer. Validate the id in `activeOrNull()` with the existing `isSafeId` at the same time (finding 5) so the active working directory is never derived from an unchecked pref.
