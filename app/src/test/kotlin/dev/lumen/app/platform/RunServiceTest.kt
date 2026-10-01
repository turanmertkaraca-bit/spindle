package dev.lumen.app.platform

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertNotNull

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

        controller.startCommand(startIntent(), 0, 1)

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
        controller.startCommand(startIntent(), 0, 1)

        controller.startCommand(
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
}
