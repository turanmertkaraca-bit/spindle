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
import java.util.concurrent.atomic.AtomicBoolean

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

    /**
     * Set when [teardownRun] cannot safely drop the wake lock because the stop
     * handler failed. The run is then still going, so [onDestroy] releases the
     * lock instead of leaving the CPU awake forever. Cleared by a new run.
     */
    @Volatile
    private var wakeLockReleases: Boolean = false

    /** The active run's session title, so progress updates keep a name in the shade. */
    @Volatile
    private var currentTitle: String = "Lumen"

    /** The active run's session id, so a progress tap deep-links back to it. */
    @Volatile
    private var currentSessionId: String? = null

    /**
     * Guards [teardownRun] so a stop command, a failed foreground start and
     * [onDestroy] cannot each run the teardown for the same run. Reset when a
     * new run starts so the instance can be reused.
     */
    @Volatile
    private var tornDown = false

    /** Held while [teardownRun] runs so re-entry cannot double-invoke it. */
    private val teardownLock = Any()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        markAlive(true)
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                // A STOP intent can arrive at an instance created by
                // startForegroundService (the notification's Stop action), so
                // satisfy the 5s foreground duty before tearing down.
                show(notification("stopping\u2026"))
                teardownRun()
            }
            ACTION_START -> {
                // A reused instance must be able to tear down the next run, and
                // the shared stop guard must allow the handler once more.
                tornDown = false
                stopInvoked.set(false)
                currentSessionId = intent.getStringExtra(EXTRA_SESSION_ID)?.takeIf { it.isNotBlank() }
                currentTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "Lumen" }
                show(notification("starting\u2026"))
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
        teardownRun()
        // [teardownRun] deliberately keeps the wake lock when the stop handler
        // threw, so a possibly-live run keeps its CPU guarantee. The service is
        // going away now, so the lock cannot be renewed: release it here or it
        // drains the battery until its own timeout expires.
        if (wakeLockReleases) releaseWakeLock()
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
            // Background start restrictions can deny foreground status. Without a
            // notification the service cannot legally stay up, so end the run here
            // as well: teardownRun stops the agent and drops the wake lock instead
            // of leaving a headless run behind once the service dies.
            Log.e(TAG, "startForeground failed; stopping the run", t)
            teardownRun()
            markAlive(false)
            runCatching { stopSelf() }
            return
        }
        refreshWakeLock()
    }

    /**
     * End the run behind this service exactly once: stop the agent, drop the
     * notification and the wake lock, and clear the liveness/title/session
     * state. Every termination path routes through here so "stop" actually
     * stops the run instead of only stopping the service.
     *
     * Idempotent: [tornDown] is checked and set under [teardownLock], so a stop
     * command racing [onDestroy] (or a failed foreground start) performs the
     * teardown work once. The stop handler itself is invoked through
     * [invokeStopHandlerOnce], the guard shared with the companion [stop], so
     * the agent is stopped exactly once per run even when both paths run.
     * Does not call `stopSelf()` — the callers decide when the service itself
     * goes away.
     */
    private fun teardownRun() {
        synchronized(teardownLock) {
            if (tornDown) return
            tornDown = true
        }
        val stopped = invokeStopHandlerOnce()
        // A throwing stop handler means the agent may still be running: dropping
        // the wake lock and notification here would leave it going headless with
        // no CPU guarantee. The lock is therefore kept; its own 5-minute timeout
        // bounds the drain, and [onDestroy] releases it when the service dies.
        if (stopped) {
            releaseWakeLock()
        } else {
            Log.e(TAG, "teardown: stop handler failed; run may still be live, keeping the wake lock")
            wakeLockReleases = true
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        markAlive(false)
        currentTitle = "Lumen"
        currentSessionId = null
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
            .setContentTitle(currentTitle)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openAppIntent())
            .addAction(0, "Stop", stopIntent())
            .build()

    /** Tapping the ongoing notification returns to the app. */
    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            currentSessionId?.let { putExtra(EXTRA_SESSION_ID, it) }
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
            // Bounded, not indefinite: a guest command with no deadline
            // (`Tool.timeoutMs <= 0`) or a provider stream that goes silent can
            // leave a run holding the CPU awake for hours. The timeout makes the
            // lock expire on its own so a stalled run cannot silently drain the
            // battery; a live run re-asserts it on every progress update.
            lock.acquire(WAKE_LOCK_TIMEOUT_MS)
            wakeLock = lock
        }
    }

    /**
     * Re-arm the timed wake lock while the run is still making progress.
     *
     * [acquireWakeLock] only takes the lock once, so without this a long but
     * healthy run would lose its lock when the timeout expires and stall in
     * doze. Called from [show], i.e. on every progress notification.
     */
    private fun refreshWakeLock() {
        // A held lock is renewed rather than left to expire: re-asserting it on
        // progress updates keeps a long but healthy run's CPU guarantee alive
        // without ever holding an indefinite lock. A run that stops making
        // progress stops renewing, so the timeout still bounds a stalled run.
        val lock = wakeLock
        if (lock != null && lock.isHeld) {
            runCatching { lock.acquire(WAKE_LOCK_TIMEOUT_MS) }
            return
        }
        acquireWakeLock()
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
        private const val WAKE_LOCK_TAG = "lumen:run"

        /**
         * How long one wake-lock grant lasts. Long enough that a busy run never
         * loses the CPU mid-step, short enough that a stalled run stops draining
         * the battery within a few minutes instead of holding the lock forever.
         * Every progress notification re-arms it.
         */
        private const val WAKE_LOCK_TIMEOUT_MS = 5L * 60L * 1_000L

        /** Result channels are created on demand, one per outcome/sound. */
        private const val CHANNEL_ID_DONE = "lumen_run_done"
        private const val CHANNEL_ID_ERROR = "lumen_run_error"
        private const val CHANNEL_NAME_DONE = "Run finished"
        private const val CHANNEL_NAME_ERROR = "Run failed"

        internal const val ACTION_START = "dev.lumen.app.action.RUN_START"
        internal const val ACTION_UPDATE = "dev.lumen.app.action.RUN_UPDATE"
        internal const val ACTION_STOP = "dev.lumen.app.action.RUN_STOP"
        internal const val EXTRA_SESSION_ID = "sessionId"
        internal const val EXTRA_TITLE = "title"
        internal const val EXTRA_TEXT = "text"

        /** Begin (or re-assert) the foreground run notification. */
        fun start(context: Context, sessionId: String, title: String) {
            val intent = Intent(context, RunService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_SESSION_ID, sessionId)
                putExtra(EXTRA_TITLE, title)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        /**
         * True while a run service instance is alive in this process.
         *
         * [update] is driven by agent `Progress` events, which can arrive just
         * after a run ends (a late frame, or a stop racing the stream). Using
         * `startForegroundService` there would spin the service back up with no
         * run behind it and post a phantom "running" notification that only goes
         * away when the user notices it. Tracking liveness lets [update] be a
         * no-op when nothing is actually running.
         */
        @Volatile
        private var alive: Boolean = false

        /**
         * Exactly-once truth for [RunControl.stopHandler], shared by the
         * instance's [teardownRun] and the companion [stop].
         *
         * [teardownRun]'s per-instance [tornDown] cannot cover the companion
         * path: `stop()` invoked the handler directly and then `stopService`
         * led to [onDestroy] -> [teardownRun], invoking it a second time. This
         * flag is set by [invokeStopHandlerOnce] before the call, so whichever
         * path runs first wins and the other is a no-op — including when
         * [stop] runs with no service instance at all. Reset in [ACTION_START].
         */
        private val stopInvoked = AtomicBoolean(false)

        /**
         * Invoke [RunControl.stopHandler] at most once per run, whichever of
         * [teardownRun] or [stop] gets here first. Returns false when the
         * handler threw, i.e. the agent may still be live; the failure is
         * logged so it is never silent. Callers use the result to decide
         * whether dropping the wake lock would leave a run going headless.
         */
        internal fun invokeStopHandlerOnce(): Boolean {
            if (!stopInvoked.compareAndSet(false, true)) return true
            return try {
                RunControl.stopHandler?.invoke()
                true
            } catch (t: Throwable) {
                Log.w(TAG, "stop handler failed during run teardown", t)
                false
            }
        }

        /** Set by the service itself as it starts and stops. */
        internal fun markAlive(running: Boolean) {
            alive = running
        }

        /** Exposed for tests: whether update() would currently forward. */
        internal fun isAlive(): Boolean = alive

        /** Replace the progress line while a run is in flight. */
        fun update(context: Context, text: String) {
            // Only touch an already-running service: never resurrect one.
            if (!alive) return
            val intent = Intent(context, RunService::class.java).apply {
                action = ACTION_UPDATE
                putExtra(EXTRA_TEXT, text)
            }
            // A plain startService is enough to deliver a command to a live
            // service and does not carry the "must call startForeground" duty
            // that startForegroundService would impose.
            runCatching { context.startService(intent) }
        }

        /**
         * Stop the run. `stopService` is used rather than a `startService` STOP
         * command: it works from the background (a plain `startService` throws
         * there) and [onDestroy] releases the wake lock.
         *
         * Stopping the service alone used to leave the agent running with no
         * foreground service, wake lock or notification, because the stop
         * handler was only invoked from the [ACTION_STOP] branch of
         * [onStartCommand]. The handler is therefore invoked here as well, so
         * the run is stopped whether or not a service instance exists. The call
         * goes through [invokeStopHandlerOnce], the same guard [teardownRun]
         * uses, so the subsequent [onDestroy] does not invoke it a second time
         * while a stop with no live instance still stops the run.
         */
        fun stop(context: Context) {
            invokeStopHandlerOnce()
            context.stopService(Intent(context, RunService::class.java))
        }

        /**
         * Post a result notification when a run ends on its own. The session
         * title is the headline and the outcome (plus how long it ran) is the
         * body, so a shade full of runs is scannable instead of a stack of
         * identical "Run finished" rows. Agent output is never attached — it is
         * long and reads badly in the shade. Tapping it opens [MainActivity] at
         * [sessionId] via [EXTRA_SESSION_ID].
         *
         * The error outcome uses a distinct channel whose sound is the bundled
         * [R.raw.error_dong] water-drop, so a failure is audibly different from a
         * normal completion. The channels are created lazily here rather than in
         * [ensureChannel] so an install that never finishes a run never creates
         * them. Best-effort: a missing notification permission degrades to a
         * no-op instead of crashing the process that just finished a run.
         */
        fun notifyComplete(
            context: Context,
            sessionId: String,
            title: String,
            failed: Boolean,
            durationMs: Long? = null,
        ) {
            runCatching {
                val manager = context.getSystemService(NotificationManager::class.java) ?: return
                ensureResultChannel(context, manager, failed)
                val channelId = if (failed) CHANNEL_ID_ERROR else CHANNEL_ID_DONE
                val outcome = if (failed) "run failed" else "run finished"
                val body = durationLabel(durationMs)?.let { "$outcome \u00b7 $it" } ?: outcome
                val notification = NotificationCompat.Builder(context, channelId)
                    .setContentTitle(title.ifBlank { "Lumen" })
                    .setContentText(body)
                    .setSmallIcon(R.drawable.ic_launcher_monochrome)
                    .setContentIntent(sessionIntent(context, sessionId))
                    .setTicker("$title \u2014 $outcome")
                    .setCategory(NotificationCompat.CATEGORY_STATUS)
                    .setShowWhen(true)
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

        /** "2m 10s" / "45s" / null when the run time is unknown or negligible. */
        private fun durationLabel(durationMs: Long?): String? {
            if (durationMs == null || durationMs < 1_000) return null
            val totalSeconds = durationMs / 1_000
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            return if (minutes > 0) "${minutes}m ${seconds}s" else "${seconds}s"
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
