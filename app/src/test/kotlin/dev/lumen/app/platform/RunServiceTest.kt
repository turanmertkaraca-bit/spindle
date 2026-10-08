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
import kotlin.test.assertNotNull
import kotlin.test.assertNull

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

    private fun startIntent(): Intent =
        Intent(context, RunService::class.java)
            .setAction(RunService.ACTION_START)
            .putExtra(RunService.EXTRA_SESSION_ID, "session-1")
            .putExtra(RunService.EXTRA_TITLE, "working")

    @Test
    fun `completion notifications are title-only with distinct channels`() {
        RunService.notifyComplete(context, "session-done", "my chat", failed = false)
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
        val failed = posted.first {
            it.extras.getString(Notification.EXTRA_TITLE) == "Run failed"
        }
        assertNull(
            failed.extras.getCharSequence(Notification.EXTRA_TEXT),
            "the result notification must never carry a body",
        )
    }
}
