package dev.lumen.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The new approval settings must survive a fresh KeyStore over the same prefs. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KeyStoreTest {

    private fun newStore() = KeyStore(ApplicationProvider.getApplicationContext())

    /** A store with the private prefs wiped, so GitHub defaults are deterministic. */
    private fun freshStore(): KeyStore {
        ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("lumen.keys", Context.MODE_PRIVATE).edit().clear().commit()
        return newStore()
    }

    @Test
    fun `ask before tools defaults off and round-trips`() {
        val store = newStore()
        assertFalse(store.askBeforeTools, "unattended is the default")
        store.askBeforeTools = true
        assertTrue(newStore().askBeforeTools, "a new instance sees the persisted value")
        store.askBeforeTools = false
        assertFalse(newStore().askBeforeTools)
    }

    @Test
    fun `allowed patterns default empty and round-trip as a set`() {
        val store = newStore()
        assertEquals(emptySet(), store.allowedPatterns)
        store.allowedPatterns = setOf("git", "/tmp/file.txt", "read")
        assertEquals(setOf("git", "/tmp/file.txt", "read"), newStore().allowedPatterns)
        store.allowedPatterns = emptySet()
        assertTrue(newStore().allowedPatterns.isEmpty())
    }

    @Test
    fun `allowed patterns are capped to the newest entries`() {
        val store = newStore()
        store.allowedPatterns = (1..KeyStore.MAX_ALLOWED_PATTERNS + 25).map { "cmd$it" }.toSet()
        val persisted = newStore().allowedPatterns
        assertEquals(KeyStore.MAX_ALLOWED_PATTERNS, persisted.size)
        assertTrue("cmd1" !in persisted, "the oldest pattern drops off")
        assertTrue("cmd${KeyStore.MAX_ALLOWED_PATTERNS + 25}" in persisted, "the newest is kept")
    }

    @Test
    fun `agent mode defaults to build and round-trips`() {
        val store = newStore()
        assertEquals("build", store.agentMode)
        store.agentMode = "plan"
        assertEquals("plan", newStore().agentMode, "a new instance sees the persisted mode")
    }

    @Test
    fun `max cost defaults to unlimited and round-trips`() {
        val store = newStore()
        assertEquals(0.0, store.maxCostUsd, "unlimited is the default")
        store.maxCostUsd = 2.0
        assertEquals(2.0, newStore().maxCostUsd, "a new instance sees the persisted ceiling")
        store.maxCostUsd = 0.0
        assertEquals(0.0, newStore().maxCostUsd)
    }

    @Test
    fun `github token round-trips and is encrypted at rest`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = freshStore()
        assertFalse(store.hasGithubToken, "no token by default")

        store.githubToken = "test-github-token"
        assertTrue(store.hasGithubToken)
        assertEquals("test-github-token", store.githubToken)
        assertEquals("test-github-token", KeyStore(context).githubToken, "a new instance decrypts it")

        val prefs = context.getSharedPreferences("lumen.keys", Context.MODE_PRIVATE)
        assertNull(prefs.getString("githubToken", null), "no cleartext legacy value")
        val sealed = prefs.getString("githubTokenEnc", null)
        assertNotNull(sealed)
        assertTrue("test-github-token" !in sealed, "the stored blob is not cleartext")
    }

    @Test
    fun `github login and repo round-trip as non-secrets`() {
        val store = freshStore()
        assertEquals("", store.githubLogin)
        assertEquals("", store.githubRepo)
        store.githubLogin = "octocat"
        store.githubRepo = "octocat/Hello-World"
        val reopened = newStore()
        assertEquals("octocat", reopened.githubLogin)
        assertEquals("octocat/Hello-World", reopened.githubRepo)
    }

    @Test
    fun `legacy cleartext github token migrates to encrypted`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("lumen.keys", Context.MODE_PRIVATE)
        prefs.edit().clear().putString("githubToken", "test-legacy-token").commit()

        val store = KeyStore(context)
        assertEquals("test-legacy-token", store.githubToken, "the legacy token is still readable")
        assertNull(prefs.getString("githubToken", null), "legacy cleartext is removed")
        assertNotNull(prefs.getString("githubTokenEnc", null), "it is re-sealed")
    }

    @Test
    fun `clearing the github token removes both representations`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = KeyStore(context)
        store.githubToken = "test-clear-token"
        store.githubToken = null
        assertFalse(store.hasGithubToken)

        val prefs = context.getSharedPreferences("lumen.keys", Context.MODE_PRIVATE)
        assertNull(prefs.getString("githubToken", null))
        assertNull(prefs.getString("githubTokenEnc", null))
    }
}
