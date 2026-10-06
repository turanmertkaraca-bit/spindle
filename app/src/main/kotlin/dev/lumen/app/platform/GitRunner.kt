package dev.lumen.app.platform

import android.content.Context
import dev.spindle.core.tool.ShellExecutor
import java.io.File
import java.nio.file.Path

/** Outcome of a git operation. */
sealed class GitResult {
    data class Ok(val output: String) : GitResult()
    data class Failed(val exitCode: Int, val message: String) : GitResult()

    /** `git` is not present in the active shell environment. */
    data object NotInstalled : GitResult()
}

/** Git readiness for the connected status card. */
data class GitProbe(val installed: Boolean, val version: String?)

/**
 * Runs git through the app's [ShellExecutor] (the Alpine/Debian userland on
 * device, the host shell in tests). Commands run under [workspace].
 *
 * SECRET RULE: the GitHub token is NEVER placed in argv or in a remote URL —
 * either would persist to `.git/config` and leak into error text. It is passed
 * only through the process environment and consumed by a one-shot
 * `credential.helper`, and any occurrence in captured output is redacted
 * before it reaches the UI.
 */
class GitRunner(
    private val shell: ShellExecutor,
    private val workspace: Path,
) {
    /** Device constructor: the real shell executor and a default workspace. */
    constructor(context: Context, workspace: Path = defaultWorkspace(context)) :
        this(AndroidShellExecutor(context), workspace)

    /** Probe `git --version`; never throws. */
    suspend fun probe(): GitProbe {
        ensureWorkspace()
        val result = shell.run("git --version", workspace, PROBE_TIMEOUT_MS)
        val text = result.output.trim()
        val installed = result.exitCode == 0 && text.contains("git version", ignoreCase = true)
        return GitProbe(installed, if (installed) text.lineSequence().firstOrNull()?.trim() else null)
    }

    suspend fun isInstalled(): Boolean = probe().installed

    /**
     * Install git via the sandbox package manager when the environment layer is
     * present. Returns [GitResult.Failed] with a readable message otherwise.
     */
    suspend fun installGit(): GitResult {
        ensureWorkspace()
        val result = shell.run("pkg install git", workspace, INSTALL_TIMEOUT_MS)
        return if (result.exitCode == 0) {
            GitResult.Ok(result.output.trim().ifBlank { "git installed" })
        } else {
            GitResult.Failed(result.exitCode, result.output.trim().ifBlank { "package manager unavailable" })
        }
    }

    /**
     * Clone `owner/name` into [dest] (relative to the workspace) authenticating
     * with [token] via the environment, never via the URL. On success the remote
     * is normalised to a credential-free URL and any helper config is dropped.
     */
    suspend fun clone(ownerRepo: String, dest: String, token: String): GitResult {
        val slug = sanitizeSlug(ownerRepo)
            ?: return GitResult.Failed(-1, "invalid repository (use owner/name)")
        val dir = sanitizeDest(dest)
            ?: return GitResult.Failed(-1, "invalid destination")
        ensureWorkspace()
        if (!probe().installed) return GitResult.NotInstalled

        val url = "https://github.com/$slug.git"
        // Defined with single quotes around the helper so the outer shell does
        // not expand $GITHUB_TOKEN; git runs it later with the env var set.
        val helper = "!f(){ echo username=x-access-token; echo password=\"\$GITHUB_TOKEN\"; };f"
        val command = "git -c credential.helper='$helper' clone ${quote(url)} ${quote(dir)}"

        val result = shell.run(command, workspace, CLONE_TIMEOUT_MS, mapOf("GITHUB_TOKEN" to token))
        if (result.exitCode != 0) {
            val message = redact(result.output, token).trim().ifBlank { "clone failed" }
            return GitResult.Failed(result.exitCode, message)
        }

        // Belt-and-suspenders: the clone URL had no secret, but normalise it and
        // remove any helper that might have been configured elsewhere.
        shell.run(
            "git -C ${quote(dir)} remote set-url origin ${quote(url)}",
            workspace,
            SHORT_TIMEOUT_MS,
        )
        shell.run(
            "git -C ${quote(dir)} config --unset-all credential.helper 2>/dev/null || true",
            workspace,
            SHORT_TIMEOUT_MS,
        )

        val branch = shell.run(
            "git -C ${quote(dir)} rev-parse --abbrev-ref HEAD",
            workspace,
            SHORT_TIMEOUT_MS,
        ).output.trim().lineSequence().firstOrNull().orEmpty()

        val summary = "cloned $slug" + if (branch.isNotBlank()) " ($branch)" else ""
        return GitResult.Ok(redact(summary, token))
    }

    /** `git status` in the workspace (short, with branch). */
    suspend fun status(): GitResult {
        ensureWorkspace()
        if (!probe().installed) return GitResult.NotInstalled
        val result = shell.run("git status --short --branch", workspace, SHORT_TIMEOUT_MS)
        return if (result.exitCode == 0) {
            GitResult.Ok(result.output.trim().ifBlank { "working tree clean" })
        } else {
            GitResult.Failed(result.exitCode, result.output.trim().ifBlank { "not a git repository" })
        }
    }

    /** The public web URL for `owner/name`, or null when the slug is invalid. */
    fun openUrl(ownerRepo: String): String? =
        sanitizeSlug(ownerRepo)?.let { "https://github.com/$it" }

    private fun ensureWorkspace() {
        runCatching { workspace.toFile().mkdirs() }
    }

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    /** Accept `owner/name` (optional `.git`), refusing anything shell-unsafe. */
    private fun sanitizeSlug(slug: String): String? {
        val clean = slug.trim().removeSuffix(".git")
        val parts = clean.split("/")
        if (parts.size != 2) return null
        return if (SLUG.matches(parts[0]) && SLUG.matches(parts[1])) clean else null
    }

    /** A workspace-relative destination: no absolute paths, no `..` climbs. */
    private fun sanitizeDest(dest: String): String? {
        val clean = dest.trim().trim('/')
        if (clean.isEmpty()) return null
        if (clean.split('/').any { it.isEmpty() || it == ".." || it == "." }) return null
        return if (DEST.matches(clean)) clean else null
    }

    /** Redact the token from any captured output before it is shown. */
    private fun redact(text: String, token: String): String =
        if (token.isNotBlank()) text.replace(token, "***") else text

    companion object {
        private val SLUG = Regex("^[A-Za-z0-9_.-]+$")
        private val DEST = Regex("^[A-Za-z0-9._/-]+$")

        private const val PROBE_TIMEOUT_MS = 10_000L
        private const val SHORT_TIMEOUT_MS = 20_000L
        private const val INSTALL_TIMEOUT_MS = 180_000L
        private const val CLONE_TIMEOUT_MS = 300_000L

        /** Default workspace root used before the integration agent wires the real one. */
        fun defaultWorkspace(context: Context): Path =
            File(context.filesDir, "workspace").apply { mkdirs() }.toPath()
    }
}
