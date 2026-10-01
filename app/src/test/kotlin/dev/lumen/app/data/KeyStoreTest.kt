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
    fun `agent mode defaults to build and round-trips`() {
        val store = newStore()
        assertEquals("build", store.agentMode)
        store.agentMode = "plan"
        assertEquals("plan", newStore().agentMode, "a new instance sees the persisted mode")
    }
}
