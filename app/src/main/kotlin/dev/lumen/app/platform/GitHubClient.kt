package dev.lumen.app.platform

import dev.lumen.app.data.ProviderCatalogue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/** Outcome of probing `GET /user` with a token. */
sealed class GitHubUserStatus {
    /** The token authenticated; [login] is the account name. */
    data class Valid(val login: String) : GitHubUserStatus()

    /** 401 — the token is missing, expired or malformed. */
    data object Invalid : GitHubUserStatus()

    /** 403 — the token is valid but lacks permission (or is rate-limited). */
    data object NoPermission : GitHubUserStatus()

    /** No response (offline / DNS / timeout) or an unexpected status. */
    data object Unreachable : GitHubUserStatus()
}

/** Outcome of looking up a repository. */
sealed class GitHubRepoStatus {
    data class Visible(
        val fullName: String,
        val private: Boolean,
        val defaultBranch: String,
    ) : GitHubRepoStatus()

    /** 404 — the repo does not exist or the token cannot see it. */
    data object NotFound : GitHubRepoStatus()

    /** 401/403 — the token lacks access to this repository. */
    data object NoPermission : GitHubRepoStatus()

    data object Unreachable : GitHubRepoStatus()
}

/**
 * A minimal GitHub REST client built on [HttpURLConnection] (OkHttp is not on
 * this module's classpath). Every call runs on [Dispatchers.IO] and is
 * defensive: failures come back as a status, never as a throw.
 *
 * SECURITY: the token is only ever set as an `Authorization` header. It is
 * never logged, never included in a URL and never echoed in a result.
 *
 * The [get] seam is injectable so the status mapping is unit-testable without
 * touching the network; production uses the default live transport.
 */
class GitHubClient(
    private val get: suspend (url: String, token: String) -> Response = ::liveGet,
) {
    /** A raw HTTP response: only the status code and body are needed. */
    data class Response(val code: Int, val body: String)

    /** Verify a token and resolve the account login via `GET /user`. */
    suspend fun user(token: String): GitHubUserStatus {
        if (token.isBlank()) return GitHubUserStatus.Invalid
        val r = get(USER_URL, token)
        return when (r.code) {
            200 -> {
                val login = runCatching { JSONObject(r.body).optString("login") }.getOrDefault("")
                if (login.isBlank()) GitHubUserStatus.Unreachable else GitHubUserStatus.Valid(login)
            }
            401 -> GitHubUserStatus.Invalid
            403 -> GitHubUserStatus.NoPermission
            else -> GitHubUserStatus.Unreachable
        }
    }

    /**
     * Look up `owner/name`. [token] is optional but required to see private
     * repositories; it is sent as a header only.
     */
    suspend fun repo(slug: String, token: String = ""): GitHubRepoStatus {
        val (owner, name) = splitSlug(slug) ?: return GitHubRepoStatus.NotFound
        val r = get("https://api.github.com/repos/$owner/$name", token)
        return when (r.code) {
            200 -> runCatching {
                val o = JSONObject(r.body)
                GitHubRepoStatus.Visible(
                    fullName = o.optString("full_name", "$owner/$name"),
                    private = o.optBoolean("private", false),
                    defaultBranch = o.optString("default_branch", "main"),
                )
            }.getOrDefault(GitHubRepoStatus.Unreachable)
            404 -> GitHubRepoStatus.NotFound
            401, 403 -> GitHubRepoStatus.NoPermission
            else -> GitHubRepoStatus.Unreachable
        }
    }

    private companion object {
        const val USER_URL = "https://api.github.com/user"
        val SLUG = Regex("^[A-Za-z0-9_.-]+$")

        /** Accept `owner/name` (with an optional trailing `.git`), else null. */
        fun splitSlug(slug: String): Pair<String, String>? {
            val parts = slug.trim().removeSuffix(".git").split("/")
            if (parts.size != 2) return null
            val owner = parts[0]
            val name = parts[1]
            return if (SLUG.matches(owner) && SLUG.matches(name)) owner to name else null
        }
    }
}

/** The production transport; never throws, never logs the token. */
private suspend fun liveGet(url: String, token: String): GitHubClient.Response =
    withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 20_000
                useCaches = false
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                // GitHub rejects requests without a User-Agent.
                setRequestProperty("User-Agent", ProviderCatalogue.USER_AGENT)
                if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() } ?: ""
            GitHubClient.Response(code, body)
        } catch (io: IOException) {
            GitHubClient.Response(0, "")
        } catch (t: Throwable) {
            GitHubClient.Response(0, "")
        } finally {
            runCatching { conn?.disconnect() }
        }
    }
