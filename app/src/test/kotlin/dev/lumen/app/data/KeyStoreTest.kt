package dev.lumen.app.data

import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The new approval settings must survive a fresh KeyStore over the same prefs. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KeyStoreTest {

    private fun newStore() = KeyStore(ApplicationProvider.getApplicationContext())

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
}
