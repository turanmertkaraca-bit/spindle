package dev.lumen.app.platform

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.lumen.app.MainActivity
import dev.lumen.app.R

/**
 * Foreground service that keeps a long agent run alive while the app is
 * backgrounded and surfaces it as an ongoing notification. It owns no state:
 * the session store remains the source of truth, so it is deliberately
 * [START_NOT_STICKY] and never restarts an interrupted run on its own.
 *
 * Every notification/wake-lock call is wrapped in [runCatching] so a missing
 * permission (e.g. `POST_NOTIFICATIONS`) or an OEM quirk degrades to "no
 * notification" instead of crashing the app.
 *
 * The UI drives it through the companion seam: [start] when a run begins,
 * [update] as progress text changes and [stop] when the run ends.
 */
class RunService : Service() {

    /** Only ever acquired once; released on [stop] and again in [onDestroy]. */
    @Volatile
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopRun()
            ACTION_START -> {
                val title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "running" }
                show(notification(title))
            }
            ACTION_UPDATE -> {
                val text = intent.getStringExtra(EXTRA_TEXT).orEmpty().ifBlank { "running" }
                show(notification(text))
            }
            else -> {
                // A redelivered/unknown command still has to leave the service
                // in a legal foreground state (startForeground within 5s).
                show(notification("running"))
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    /**
     * Post/replace the ongoing notification and ensure the wake lock is held.
     *
     * `startForeground` is deliberately NOT swallowed: if it fails the platform
     * will kill the process with `ForegroundServiceDidNotStartInTimeException`,
     * so surface the failure and stop the service instead of lingering in an
     * illegal state.
     */
    private fun show(notification: Notification) {
        try {
            startForeground(NOTIFICATION_ID, notification)
        } catch (t: Throwable) {
            Log.e(TAG, "startForeground failed; stopping run service", t)
            runCatching { stopSelf() }
            return
        }
        acquireWakeLock()
    }

    @Suppress("DEPRECATION")
    private fun stopRun() {
        runCatching { stopForeground(true) }
        releaseWakeLock()
        runCatching { stopSelf() }
    }

    /** Idempotent: creates the low-importance channel once, on API 26+. */
    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching {
            val manager = getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Keeps long agent runs alive in the background"
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun notification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(NOTIFICATION_TITLE)
            .setContentText(text)
            .setSmallIcon(SMALL_ICON)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openAppIntent())
            .addAction(0, "Stop", stopIntent())
            .build()

    /** Tapping the ongoing notification returns to the app. */
    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** The notification's Stop action drives the same STOP command as the UI. */
    private fun stopIntent(): PendingIntent {
        val intent = Intent(this, RunService::class.java).apply { action = ACTION_STOP }
        return PendingIntent.getService(
            this, 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        runCatching {
            val power = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            val lock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            lock.setReferenceCounted(false)
            lock.acquire()
            wakeLock = lock
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            wakeLock?.let { if (it.isHeld) it.release() }
        }
        wakeLock = null
    }

    companion object {
        private const val TAG = "RunService"
        private const val CHANNEL_ID = "lumen-run"
        private const val CHANNEL_NAME = "Agent runs"
        private const val NOTIFICATION_ID = 4711
        private const val NOTIFICATION_TITLE = "Lumen"
        private const val WAKE_LOCK_TAG = "lumen:run"

        /** Result channels are created on demand, one per outcome/sound. */
        private const val CHANNEL_ID_DONE = "lumen_run_done"
        private const val CHANNEL_ID_ERROR = "lumen_run_error"
        private const val CHANNEL_NAME_DONE = "Run finished"
        private const val CHANNEL_NAME_ERROR = "Run failed"

        /** Title-only result notifications: never carry agent output. */
        private const val NOTIFICATION_TITLE_DONE = "Run finished"
        private const val NOTIFICATION_TITLE_FAILED = "Run failed"

        internal const val ACTION_START = "dev.lumen.app.action.RUN_START"
        internal const val ACTION_UPDATE = "dev.lumen.app.action.RUN_UPDATE"
        internal const val ACTION_STOP = "dev.lumen.app.action.RUN_STOP"
        internal const val EXTRA_SESSION_ID = "sessionId"
        internal const val EXTRA_TITLE = "title"
        internal const val EXTRA_TEXT = "text"

        /** No custom art in the repo, so fall back to a system drawable. */
        private val SMALL_ICON = android.R.drawable.stat_sys_download

        /** Begin (or re-assert) the foreground run notification. */
        fun start(context: Context, sessionId: String, title: String) {
            val intent = Intent(context, RunService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_SESSION_ID, sessionId)
                putExtra(EXTRA_TITLE, title)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        /** Replace the progress line while a run is in flight. */
        fun update(context: Context, text: String) {
            val intent = Intent(context, RunService::class.java).apply {
                action = ACTION_UPDATE
                putExtra(EXTRA_TEXT, text)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        /**
         * Stop the run. `stopService` is used rather than a `startService` STOP
         * command: it works from the background (a plain `startService` throws
         * there) and [onDestroy] releases the wake lock.
         */
        fun stop(context: Context) {
            context.stopService(Intent(context, RunService::class.java))
        }

        /**
         * Post a title-only notification when a run ends on its own: "Run
         * finished" on success or "Run failed" when the run ended in error. No
         * body text is ever attached (agent output is long and reads badly in
         * the shade). Tapping it opens [MainActivity] at [sessionId] via
         * [EXTRA_SESSION_ID].
         *
         * The error outcome uses a distinct channel whose sound is the bundled
         * [R.raw.error_dong] water-drop, so a failure is audibly different from a
         * normal completion. The channels are created lazily here rather than in
         * [ensureChannel] so an install that never finishes a run never creates
         * them. Best-effort: a missing notification permission degrades to a
         * no-op instead of crashing the process that just finished a run.
         *
         * [title] is the session title, carried only as the (non-body) ticker so
         * the notification itself stays title-only.
         */
        fun notifyComplete(context: Context, sessionId: String, title: String, failed: Boolean) {
            runCatching {
                val manager = context.getSystemService(NotificationManager::class.java) ?: return
                ensureResultChannel(context, manager, failed)
                val channelId = if (failed) CHANNEL_ID_ERROR else CHANNEL_ID_DONE
                val notification = NotificationCompat.Builder(context, channelId)
                    .setContentTitle(if (failed) NOTIFICATION_TITLE_FAILED else NOTIFICATION_TITLE_DONE)
                    .setSmallIcon(SMALL_ICON)
                    .setContentIntent(sessionIntent(context, sessionId))
                    .setTicker(title)
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(false)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .apply {
                        // Pre-channel platforms need the sound on the builder too.
                        if (failed && Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                            setSound(errorSound(context))
                        }
                    }
                    .build()
                manager.notify(resultNotificationId(sessionId), notification)
            }
        }

        /** Tap target that opens the app at [sessionId] (singleTask -> onNewIntent). */
        private fun sessionIntent(context: Context, sessionId: String): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(EXTRA_SESSION_ID, sessionId)
            }
            return PendingIntent.getActivity(
                context, resultNotificationId(sessionId), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        /**
         * Stable per-session id so two sessions finishing at once post two
         * notifications instead of overwriting each other. Kept clear of the
         * ongoing run id.
         */
        private fun resultNotificationId(sessionId: String): Int =
            sessionId.hashCode().let { if (it == NOTIFICATION_ID) it + 1 else it }

        /** The bundled water-drop sound for the error channel. */
        private fun errorSound(context: Context): Uri =
            Uri.parse("android.resource://" + context.packageName + "/" + R.raw.error_dong)

        /** Lazily create the done/error channel, with the custom sound on error. */
        private fun ensureResultChannel(context: Context, manager: NotificationManager, failed: Boolean) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val id = if (failed) CHANNEL_ID_ERROR else CHANNEL_ID_DONE
            if (manager.getNotificationChannel(id) != null) return
            val channel = NotificationChannel(
                id,
                if (failed) CHANNEL_NAME_ERROR else CHANNEL_NAME_DONE,
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = if (failed) "A background run failed" else "A background run finished"
                if (failed) {
                    val attrs = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                    setSound(errorSound(context), attrs)
                }
            }
            manager.createNotificationChannel(channel)
        }
    }
}
