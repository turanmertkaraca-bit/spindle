package dev.spindle.tool

import dev.spindle.core.provider.ToolSpec
import dev.spindle.core.tool.ShellExecutor
import dev.spindle.core.tool.ShellResult
import dev.spindle.core.tool.Tool
import dev.spindle.core.tool.ToolContext
import dev.spindle.core.tool.ToolOutcome
import dev.spindle.core.tool.ToolProgress
import kotlinx.serialization.json.JsonObject
import java.nio.file.Path

/**
 * Git access for the agent, running through the app's [ShellExecutor] in the
 * session working directory.
 *
 * Why this exists: the agent already has `bash`, but the app deliberately
 * strips host secrets (including `GITHUB_TOKEN`) from the shell environment, so
 * an agent-run `git push` / private `git clone` can never authenticate. This
 * tool is the sanctioned path: for network operations it injects the stored
 * GitHub token through the process environment only, consumed by a one-shot
 * `credential.helper`, and redacts it from any captured output.
 *
 * SECRET RULE (mirrors GitRunner): the token is NEVER placed in argv or in a
 * remote URL — either would persist to `.git/config` and leak into error text.
 * It rides only in the environment of the single command that needs it. Before
 * any command runs, confirm the caller has approved it via the permission gate.
 */
class GitTool(
    private val shell: ShellExecutor = HostShellExecutor(),
    private val token: () -> String? = { null },
) : Tool {

    override val spec = ToolSpec(
        name = "git",
        description = "Run git in the project directory. Network operations (clone, fetch, " +
            "pull, push) authenticate automatically with the GitHub token saved in Settings, " +
            "so private repos work; the token is never written into the command, argv or the " +
            "remote URL. `push` is never forced (there is no force flag, by design). Use " +
            "`pull` with rebase:true to recover from a rejected non-fast-forward push. " +
            "Prefer this over shelling out to git, which runs without credentials.",
        parametersJson = """
            {
              "type": "object",
              "properties": {
                "op": {
                  "type": "string",
                  "enum": ["status", "diff", "log", "show", "branch", "remote", "add", "commit",
                           "clone", "fetch", "pull", "push", "checkout",
                           "merge", "rebase", "stash", "tag", "blame", "cherry-pick", "config"],
                  "description": "Git operation to run"
                },
                "message": {"type": "string", "description": "For commit: the commit message (required)"},
                "ref": {"type": "string", "description": "For show/checkout: a commit, branch or tag"},
                "repo": {"type": "string", "description": "For clone: owner/name or an https repo URL"},
                "dest": {"type": "string", "description": "For clone: destination directory, relative to the project"},
                "path": {"type": "string", "description": "For diff/add: a file or directory, relative to the project"},
                "staged": {"type": "boolean", "description": "For diff: show staged changes (--staged)"},
                "count": {"type": "integer", "description": "For log: number of commits (default 20, max 100)"},
                "dir": {"type": "string", "description": "Subdirectory to run git in, relative to the working directory. Use this when the repo is nested (e.g. a project inside a workspace that is itself a repo)."},
                "remote": {"type": "string", "description": "For fetch/pull/push: remote name (default origin)"},
                "branch": {"type": "string", "description": "For push: branch name (default: current upstream)"},
                "onto": {"type": "string", "description": "For merge/rebase/cherry-pick: the branch or commit to apply"},
                "name": {"type": "string", "description": "For tag: the tag name. For stash: an optional label. For config: the key."},
                "rebase": {"type": "boolean", "description": "For pull: rebase local commits instead of merging (use after a non-fast-forward push)"},
                "tags": {"type": "boolean", "description": "For push: also push tags"},
                "value": {"type": "string", "description": "For config: the value to set (omit to read the current one)"},
                "list": {"type": "boolean", "description": "For stash: list stashes instead of creating one. For tag: list tags."},
                "action": {"type": "string", "enum": ["list", "pop", "apply", "drop"], "description": "For stash: what to do. Omit to create a new stash."}
              },
              "required": ["op"],
              "additionalProperties": false
            }
        """.trimIndent(),
    )

    // Each op passes its own deadline to the shell; the loop must not add one.
    override val timeoutMs = 0L

    override suspend fun run(input: JsonObject, ctx: ToolContext): ToolOutcome {
        val op = (input.stringOrNull("op") ?: "").lowercase()
        if (op !in OPS) {
            return ToolOutcome("Unsupported op '$op' (expected ${OPS.joinToString(", ")})", isError = true)
        }

        ctx.checkAborted()

        // Nested repositories are the norm when one project sits inside another
        // checkout: without this, `git status` in the outer directory reports the
        // OUTER repo and the agent silently edits/commits the wrong project.
        val runDir = try {
            input.stringOrNull("dir")?.trim()?.takeIf { it.isNotBlank() }
                ?.let { resolveInsideCwd(ctx, it) }
                ?: ctx.cwd
        } catch (e: IllegalArgumentException) {
            return ToolOutcome(e.message ?: "invalid dir", isError = true)
        }

        val command = try {
            build(op, input, runDir)
        } catch (e: IllegalArgumentException) {
            return ToolOutcome(e.message ?: "invalid arguments", isError = true)
        }

        // Gate every git call behind the same approval flow as bash, with a
        // scope key the user can widen ("git *" or an exact command).
        val pattern = if (op == "commit") "git commit" else "git $op"
        if (!ctx.requestPermission("git", command, pattern)) {
            return ToolOutcome("denied by user", isError = true)
        }

        ctx.checkAborted()

        val secret = if (op in NETWORK_OPS) token()?.takeIf { it.isNotBlank() } else null
        if (op in NETWORK_OPS && secret == null) {
            return ToolOutcome(
                "No GitHub token is configured. Add one in Settings \u2192 GitHub, " +
                    "or use `clone` with a public https URL.",
                isError = true,
            )
        }

        ctx.emit(ToolProgress("Running: $command"))

        val result = try {
            if (secret != null) {
                shell.run(command, runDir, NETWORK_TIMEOUT_MS, mapOf("GITHUB_TOKEN" to secret))
            } else {
                shell.run(command, runDir, LOCAL_TIMEOUT_MS)
            }
        } catch (e: Exception) {
            return ToolOutcome("Failed to run git: ${e.message}", isError = true)
        }

        return outcome(result, secret)
    }

    /** Build the shell command for [op]; throws [IllegalArgumentException] on bad input. */
    private fun build(op: String, input: JsonObject, cwd: Path): String {
        val helper = "git -c credential.helper=$HELPER"
        return when (op) {
            "status" -> "git status --short --branch"
            "diff" -> buildString {
                append("git diff --no-color")
                if (input.boolOrNull("staged") == true) append(" --staged")
                input.stringOrNull("path")?.takeIf { it.isNotBlank() }?.let {
                    append(" -- ").append(quote(insideCwd(cwd, it)))
                }
            }
            "log" -> {
                val n = (input.intOrNull("count") ?: DEFAULT_LOG).coerceIn(1, MAX_LOG)
                "git log -n $n --no-color --pretty=format:%h%x20%an%x20%ar%x20%s"
            }
            "show" -> "git show --no-color --stat ${quote(ref(input))}"
            "branch" -> "git branch --no-color"
            "remote" -> "git remote -v"
            "add" -> {
                val path = input.stringOrNull("path")?.takeIf { it.isNotBlank() }
                if (path == null) "git add -A" else "git add -- ${quote(insideCwd(cwd, path))}"
            }
            "commit" -> buildString {
                val message = input.stringOrNull("message")?.trim()
                require(!message.isNullOrBlank()) { "commit requires a non-blank 'message'" }
                append("git add -A && git commit -m ").append(quote(message))
            }
            "clone" -> {
                val repo = input.stringOrNull("repo")?.trim()
                require(!repo.isNullOrBlank()) { "clone requires 'repo'" }
                val url = repoUrl(repo)
                val dest = input.stringOrNull("dest")?.trim()?.takeIf { it.isNotBlank() }
                    ?: url.substringAfterLast('/').removeSuffix(".git")
                val dir = insideCwd(cwd, dest)
                "$helper clone ${quote(url)} ${quote(dir)}"
            }
            "fetch" -> "$helper fetch ${remoteArg(input)}".trimEnd()
            "pull" -> buildString {
                append(helper).append(" pull")
                // `--rebase` is the documented recovery from a rejected push, and
                // without it the only way to recover is a bash `git pull --rebase`,
                // which cannot authenticate. That made a diverged branch a dead end.
                if (input.boolOrNull("rebase") == true) append(" --rebase")
                remoteArg(input)
            }.trimEnd()
            "push" -> {
                val remote = input.stringOrNull("remote")?.trim()?.takeIf { it.isNotBlank() }?.let { nameArg(it) }
                val branch = input.stringOrNull("branch")?.trim()?.takeIf { it.isNotBlank() }?.let { nameArg(it) }
                buildString {
                    append(helper).append(" push")
                    if (input.boolOrNull("tags") == true) append(" --tags")
                    remote?.let { append(' ').append(quote(it)) }
                    branch?.let { append(' ').append(quote(it)) }
                }
            }
            "checkout" -> "git checkout ${quote(ref(input))}"
            "merge" -> {
                val onto = input.stringOrNull("onto")?.trim()
                require(!onto.isNullOrBlank()) { "merge requires 'onto' (the branch or commit to merge)" }
                "git merge --no-edit ${quote(nameArg(onto))}"
            }
            "rebase" -> {
                val onto = input.stringOrNull("onto")?.trim()
                require(!onto.isNullOrBlank()) { "rebase requires 'onto' (the branch or commit to rebase onto)" }
                "git rebase ${quote(nameArg(onto))}"
            }
            "stash" -> {
                // `list` is read-only; anything else stashes. Popping is
                // `stash pop` via 'action', which the agent needs to get work back.
                val action = input.stringOrNull("action")?.trim()?.lowercase()
                when (action) {
                    "list", null -> if (input.boolOrNull("list") == true || action == "list") {
                        "git stash list"
                    } else {
                        val label = input.stringOrNull("name")?.trim()
                        if (label.isNullOrBlank()) "git stash push --include-untracked"
                        else "git stash push --include-untracked -m " + quote(label)
                    }
                    "pop" -> "git stash pop"
                    "apply" -> "git stash apply"
                    "drop" -> "git stash drop"
                    else -> throw IllegalArgumentException(
                        "stash 'action' must be one of list/pop/apply/drop (or omit to stash)",
                    )
                }
            }
            "tag" -> {
                if (input.boolOrNull("list") == true) {
                    "git tag --list"
                } else {
                    val name = input.stringOrNull("name")?.trim()
                    require(!name.isNullOrBlank()) { "tag requires 'name' (or list: true)" }
                    "git tag -a ${quote(nameArg(name))} -m " + quote(name)
                }
            }
            "blame" -> {
                val path = input.stringOrNull("path")?.trim()
                require(!path.isNullOrBlank()) { "blame requires 'path'" }
                "git blame --no-color ${quote(insideCwd(cwd, path))}"
            }
            "cherry-pick" -> {
                val onto = input.stringOrNull("onto")?.trim()
                require(!onto.isNullOrBlank()) { "cherry-pick requires 'onto' (the commit)" }
                require(REF.matches(onto) && !onto.startsWith("-")) { "invalid ref '$onto'" }
                "git cherry-pick ${quote(onto)}"
            }
            "config" -> {
                val key = input.stringOrNull("name")?.trim()
                require(!key.isNullOrBlank()) { "config requires 'name' (the key, e.g. user.email)" }
                val value = input.stringOrNull("value")?.trim()
                if (value.isNullOrBlank()) "git config --get " + quote(key)
                else "git config " + quote(key) + " " + quote(value)
            }
            else -> throw IllegalArgumentException("Unsupported op '$op'")
        }
    }

    private fun remoteArg(input: JsonObject): String =
        input.stringOrNull("remote")?.trim()?.takeIf { it.isNotBlank() }
            ?.let { " ${quote(nameArg(it))}" } ?: ""

    private fun ref(input: JsonObject): String {
        val ref = input.stringOrNull("ref")?.trim()
        require(!ref.isNullOrBlank()) { "'ref' is required" }
        require(REF.matches(ref) && !ref.startsWith("-")) { "invalid ref '$ref'" }
        return ref
    }

    /** A remote/branch name that cannot be mistaken for a git option. */
    private fun nameArg(value: String): String {
        require(NAME.matches(value) && !value.startsWith("-")) { "invalid name '$value'" }
        return value
    }

    /** Accept `owner/name` or a bare `https://host/path` repo URL (no embedded credentials). */
    private fun repoUrl(repo: String): String {
        if (repo.startsWith("http://") || repo.startsWith("https://")) {
            require(!repo.contains('@')) { "repo URL must not embed credentials" }
            require(URL.matches(repo)) { "invalid repo URL" }
            return repo.removeSuffix("/")
        }
        val clean = repo.removeSuffix(".git")
        val parts = clean.split("/")
        require(parts.size == 2 && parts.all { SLUG.matches(it) }) {
            "invalid repository (use owner/name or an https URL)"
        }
        return "https://github.com/$clean.git"
    }

    /**
     * Resolve a project-relative path and refuse anything that escapes the cwd.
     *
     * Delegates to the shared [resolveInsideCwd], which also canonicalises the
     * deepest existing ancestor: a symlink pointing outside the workspace is the
     * obvious way to break out of a plain `startsWith` check.
     */
    private fun insideCwd(cwd: Path, raw: String): String {
        val base = cwd.toAbsolutePath().normalize()
        val resolved = base.resolve(raw).normalize()
        require(resolved == base || resolved.startsWith(base)) {
            "path escapes the working directory: $raw"
        }
        requireRealInside(base, resolved, raw)
        return resolved.toString()
    }

    /** Redact the token and cap the captured output before it reaches the model. */
    private fun outcome(result: ShellResult, secret: String?): ToolOutcome {
        val raw = result.output
        val text = if (secret.isNullOrBlank()) raw else raw.replace(secret, "***")
        val clipped = if (text.length > MAX_OUTPUT_CHARS) {
            text.take(MAX_OUTPUT_CHARS) + "\n\u2026[output truncated at $MAX_OUTPUT_CHARS chars]"
        } else {
            text
        }
        val hinted = if (result.exitCode == 127 || clipped.contains("git: not found") ||
            clipped.contains("command not found")
        ) {
            "\n\n(Git is not installed in the sandbox. Install it, or ask the user to use " +
                "Settings \u2192 GitHub \u2192 Install git.)"
        } else {
            ""
        }
        return ToolOutcome(
            output = "[exit ${result.exitCode}]\n$clipped$hinted",
            isError = result.exitCode != 0,
            metadata = mapOf(
                "exitCode" to result.exitCode.toString(),
                "truncated" to (text.length > MAX_OUTPUT_CHARS).toString(),
            ),
        )
    }

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private companion object {
        val OPS = listOf(
            "status", "diff", "log", "show", "branch", "remote", "add", "commit",
            "clone", "fetch", "pull", "push", "checkout",
            "merge", "rebase", "stash", "tag", "blame", "cherry-pick", "config",
        )
        val NETWORK_OPS = setOf("clone", "fetch", "pull", "push")

        /**
         * Ops that can destroy uncommitted work, or rewrite published history.
         *
         * These are deliberately NOT offered: the owner did not ask for them, and
         * the safe recoveries (merge, rebase, stash, cherry-pick) already cover the
         * real workflows. A missing `reset --hard` is a feature.
         */

        // Single quotes keep the outer shell from expanding $GITHUB_TOKEN; git
        // runs the helper later with the variable set in its environment.
        const val HELPER = "'!f(){ echo username=x-access-token; echo password=\"\$GITHUB_TOKEN\"; };f'"

        const val DEFAULT_LOG = 20
        const val MAX_LOG = 100
        const val MAX_OUTPUT_CHARS = 50_000

        const val LOCAL_TIMEOUT_MS = 30_000L
        const val NETWORK_TIMEOUT_MS = 300_000L

        private val SLUG = Regex("^[A-Za-z0-9_.-]+$")
        private val URL = Regex("^https?://[A-Za-z0-9_.:/@%~-]+$")
        private val REF = Regex("^[A-Za-z0-9._/@{}^~-]+$")
        private val NAME = Regex("^[A-Za-z0-9._/-]+$")
    }
}
