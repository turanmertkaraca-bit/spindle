package dev.lumen.app.platform

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import dev.lumen.app.R
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The service must come up, post its foreground notification and create the
 * channel without throwing — on a bare JVM there is no real notification
 * runtime, and every platform call in [RunService] is defensive for exactly
 * this reason.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RunServiceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `start command creates the lumen-run channel and does not throw`() {
        val controller = Robolectric.buildService(RunService::class.java)
        controller.create()

        controller.get().onStartCommand(startIntent(), 0, 1)

        val manager = assertNotNull(context.getSystemService(NotificationManager::class.java))
        assertNotNull(
            manager.getNotificationChannel("lumen-run"),
            "the lumen-run notification channel should exist after onStartCommand",
        )

        controller.destroy()
    }

    @Test
    fun `stop command tears down without throwing`() {
        val controller = Robolectric.buildService(RunService::class.java)
        controller.create()
        controller.get().onStartCommand(startIntent(), 0, 1)

        controller.get().onStartCommand(
            Intent(context, RunService::class.java).setAction(RunService.ACTION_STOP),
            0,
            2,
        )
        controller.destroy()

        assertNotNull(context.getSystemService(NotificationManager::class.java))
    }

    /**
     * A progress event can land just after a run ended. Updating then used to
     * call startForegroundService and resurrect the service with nothing behind
     * it, leaving a phantom "running" notification that only goes away when the
     * user notices it. The guard is a liveness flag the service sets as it
     * starts and stops.
     */
    @Test
    fun `liveness flag gates progress updates`() {
        RunService.markAlive(false)
        assertFalse(RunService.isAlive(), "nothing is running before a start")

        // A no-op update with nothing alive must not throw.
        RunService.update(context, "still going")

        val controller = Robolectric.buildService(RunService::class.java)
        controller.create()
        assertTrue(RunService.isAlive(), "onCreate marks the service alive")
        controller.get().onStartCommand(startIntent(), 0, 1)

        controller.destroy()
        assertFalse(RunService.isAlive(), "onDestroy clears liveness")

        // A late frame after teardown must be swallowed, not resurrect anything.
        RunService.update(context, "late frame")
        assertFalse(RunService.isAlive(), "a late update must not revive the service")
    }

    private fun startIntent(): Intent =
        Intent(context, RunService::class.java)
            .setAction(RunService.ACTION_START)
            .putExtra(RunService.EXTRA_SESSION_ID, "session-1")
            .putExtra(RunService.EXTRA_TITLE, "working")

    @Test
    fun `completion notifications name the session and its outcome`() {
        RunService.notifyComplete(context, "session-done", "my chat", failed = false, durationMs = 95_000)
        RunService.notifyComplete(context, "session-fail", "my chat", failed = true)

        val manager = assertNotNull(context.getSystemService(NotificationManager::class.java))
        assertNotNull(
            manager.getNotificationChannel("lumen_run_done"),
            "a completion should lazily create the done channel",
        )
        val error = assertNotNull(
            manager.getNotificationChannel("lumen_run_error"),
            "a failure should lazily create the error channel",
        )
        assertEquals(
            Uri.parse("android.resource://" + context.packageName + "/" + R.raw.error_dong),
            error.sound,
            "the error channel should carry the bundled water-drop sound",
        )

        val posted = shadowOf(manager).allNotifications
        assertEquals(2, posted.size, "one notification per finished session")
        val failed = posted.first { it.extras.getString(Notification.EXTRA_TEXT) == "run failed" }
        assertEquals("my chat", failed.extras.getString(Notification.EXTRA_TITLE))
        val done = posted.first { it.extras.getString(Notification.EXTRA_TEXT) == "run finished \u00b7 1m 35s" }
        assertEquals("my chat", done.extras.getString(Notification.EXTRA_TITLE))
    }
}
