package dev.lumen.app.ui

import dev.lumen.app.ui.RunEndPolicy.Cause
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The "never end silently" contract.
 *
 * The owner's bug report was *"it dropped the chat without an interrupt alert, it
 * just stopped"*. These tests exist so that outcome can never be a matter of luck
 * again: every cause either produces a message the user can read or is a cause the
 * user themselves triggered.
 */
class RunEndPolicyTest {

    @Test
    fun `a user stop is silent`() {
        val n = RunEndPolicy.notice(Cause.USER_STOP)
        assertNull(n.message, "alerting the user about a stop they pressed is noise")
        assertFalse(n.notify)
    }

    @Test
    fun `a superseded run is silent because the new run speaks for itself`() {
        val n = RunEndPolicy.notice(Cause.SUPERSEDED)
        assertNull(n.message)
        assertFalse(n.notify)
    }

    /**
     * THE regression. A run whose coroutine ended without the loop reporting any
     * outcome used to produce nothing at all: idle screen, no hint, no sound.
     */
    @Test
    fun `a vanished run always tells the user and notifies`() {
        val n = RunEndPolicy.notice(Cause.VANISHED)
        val message = assertNotNull(n.message, "a run that vanished must never be silent")
        assertTrue(message.contains("unexpectedly"), message)
        assertTrue(n.notify, "the user may be in another app; the shade must fire")
    }

    @Test
    fun `a loop error is reported and notifies`() {
        val n = RunEndPolicy.notice(Cause.LOOP_ERROR, "429 rate limited")
        val message = assertNotNull(n.message)
        assertTrue(message.contains("run failed"), message)
        assertTrue(message.contains("429"), message)
        assertTrue(n.notify)
    }

    @Test
    fun `a normal finish reports without a notification`() {
        val n = RunEndPolicy.notice(Cause.FINISHED)
        val message = assertNotNull(n.message)
        assertTrue(message.contains("finished"), message)
        assertFalse(n.notify, "a normal finish is already covered by the completion notification path")
    }

    @Test
    fun `a project switch says which project`() {
        val n = RunEndPolicy.notice(Cause.PROJECT_SWITCH, "you switched to the \u201cwork\u201d project")
        val message = assertNotNull(n.message)
        assertTrue(message.contains("work"), message)
        assertTrue(n.notify)
    }

    @Test
    fun `a blank detail never renders as null`() {
        val n = RunEndPolicy.notice(Cause.PROJECT_SWITCH, "   ")
        val message = assertNotNull(n.message)
        assertFalse(message.contains("null"), message)
        assertTrue(message.isNotBlank())
    }

    @Test
    fun `closing the chat reports why`() {
        val n = RunEndPolicy.notice(Cause.CHAT_CLOSED)
        val message = assertNotNull(n.message)
        assertTrue(message.contains("closed"), message)
        assertTrue(n.notify)
    }

    // ---- classification ----

    @Test
    fun `classify prefers the user's own action`() {
        assertEquals(
            Cause.USER_STOP,
            RunEndPolicy.classify(
                userStopped = true, superseded = true,
                loopReturned = false, loopFailed = true, reason = null,
            ),
        )
    }

    @Test
    fun `classify detects a vanished run`() {
        // The loop never returned and nothing reported a failure: this is the
        // silent-stop shape.
        assertEquals(
            Cause.VANISHED,
            RunEndPolicy.classify(
                userStopped = false, superseded = false,
                loopReturned = false, loopFailed = false, reason = null,
            ),
        )
    }

    @Test
    fun `classify detects a loop error`() {
        assertEquals(
            Cause.LOOP_ERROR,
            RunEndPolicy.classify(
                userStopped = false, superseded = false,
                loopReturned = false, loopFailed = true, reason = null,
            ),
        )
    }

    @Test
    fun `classify detects a normal finish`() {
        assertEquals(
            Cause.FINISHED,
            RunEndPolicy.classify(
                userStopped = false, superseded = false,
                loopReturned = true, loopFailed = false, reason = null,
            ),
        )
    }

    @Test
    fun `classify reads a project-switch reason`() {
        assertEquals(
            Cause.PROJECT_SWITCH,
            RunEndPolicy.classify(
                userStopped = false, superseded = false, loopReturned = false, loopFailed = false,
                reason = "you switched to the \"work\" project",
            ),
        )
    }

    /**
     * The property that matters, stated directly: for every cause except the two
     * the user themselves caused, there is something to show.
     */
    @Test
    fun `only user-caused ends may be silent`() {
        val allowedSilent = setOf(Cause.USER_STOP, Cause.SUPERSEDED)
        Cause.entries.forEach { cause ->
            val notice = RunEndPolicy.notice(cause)
            if (cause in allowedSilent) {
                assertNull(notice.message, "$cause should be silent")
            } else {
                assertNotNull(notice.message, "$cause must never be silent")
                assertTrue(notice.message!!.isNotBlank(), "$cause produced a blank message")
            }
        }
    }
}
