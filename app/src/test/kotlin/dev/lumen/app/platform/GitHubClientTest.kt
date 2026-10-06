package dev.lumen.app.platform

import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The status mapping is exercised through the injectable transport, so no test
 * touches the network and no test ever needs a real token.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GitHubClientTest {

    /** Records every call and returns a fixed response. */
    private class Recorder(code: Int, body: String) {
        val urls = mutableListOf<String>()
        val tokens = mutableListOf<String>()
        val client = GitHubClient { url, token ->
            urls += url
            tokens += token
            GitHubClient.Response(code, body)
        }
    }

    @Test
    fun `user 200 yields the login`() = runBlocking {
        val r = Recorder(200, "{\"login\":\"octocat\"}")
        assertEquals(GitHubUserStatus.Valid("octocat"), r.client.user("token"))
    }

    @Test
    fun `user 401 is invalid and 403 is no permission`() = runBlocking {
        assertEquals(GitHubUserStatus.Invalid, Recorder(401, "").client.user("token"))
        assertEquals(GitHubUserStatus.NoPermission, Recorder(403, "").client.user("token"))
    }

    @Test
    fun `an empty user body is unreachable, not valid`() = runBlocking {
        assertEquals(GitHubUserStatus.Unreachable, Recorder(200, "{}").client.user("token"))
    }

    @Test
    fun `a transport failure is unreachable`() = runBlocking {
        val client = GitHubClient { _, _ -> GitHubClient.Response(0, "") }
        assertEquals(GitHubUserStatus.Unreachable, client.user("token"))
    }

    @Test
    fun `a blank token never reaches the transport`() = runBlocking {
        val r = Recorder(200, "{\"login\":\"octocat\"}")
        assertEquals(GitHubUserStatus.Invalid, r.client.user(""))
        assertTrue(r.urls.isEmpty())
    }

    @Test
    fun `the token is sent out of band and never appears in the url`() = runBlocking {
        val r = Recorder(200, "{\"login\":\"octocat\"}")
        r.client.user("s3cr3t-token")
        assertTrue(r.urls.none { it.contains("s3cr3t-token") }, "the token must not be in the URL")
        assertEquals(listOf("s3cr3t-token"), r.tokens)
    }

    @Test
    fun `repo 200 parses the repository`() = runBlocking {
        val r = Recorder(200, "{\"full_name\":\"octocat/Hello-World\",\"private\":true,\"default_branch\":\"trunk\"}")
        assertEquals(
            GitHubRepoStatus.Visible("octocat/Hello-World", true, "trunk"),
            r.client.repo("octocat/Hello-World", "token"),
        )
    }

    @Test
    fun `repo 404 is not found and 403 is no permission`() = runBlocking {
        assertEquals(GitHubRepoStatus.NotFound, Recorder(404, "").client.repo("octocat/Hello-World"))
        assertEquals(GitHubRepoStatus.NoPermission, Recorder(403, "").client.repo("octocat/Hello-World"))
    }

    @Test
    fun `an invalid slug is not found without a call`() = runBlocking {
        val r = Recorder(200, "{}")
        assertEquals(GitHubRepoStatus.NotFound, r.client.repo("not a slug"))
        assertTrue(r.urls.isEmpty())
    }
}
