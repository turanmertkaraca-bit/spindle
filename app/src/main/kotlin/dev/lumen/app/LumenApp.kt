package dev.lumen.app

import android.app.Application
import android.content.Context
import dev.lumen.app.data.AndroidDatabase
import dev.lumen.app.data.AndroidSessionStore
import dev.lumen.app.data.AndroidSnapshotStore
import dev.lumen.app.data.KeyStore
import dev.lumen.app.data.ModelCatalogue
import dev.spindle.core.agent.RunRecovery
import dev.spindle.core.event.EventBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Process owner for everything that must outlive an Activity: the agent-run
 * scope, the shared stores and the event bus. A run launched in
 * [applicationScope] keeps going when the Activity is destroyed (Back/Home,
 * rotation, process backgrounding), so only an explicit user stop cancels it.
 * The shared [events] bus lets that run keep feeding whichever ViewModel is open
 * when the user returns.
 */
class LumenApp : Application() {

    /** Outlives every ViewModel; a run launched here is never scoped to a screen. */
    val applicationScope: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    /** Process-wide run event stream, shared by every ChatViewModel instance. */
    val events = EventBus()

    /** Session ids with a live in-process run, so orphan reconciliation spares them. */
    val runningSessions: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Reconcile orphaned RUNNING sessions/parts once per process, before any
        // UI exists, so a process killed mid-run never leaves a session wedged as
        // busy (the previous UI-only call could be skipped entirely).
        applicationScope.launch {
            runCatching { RunRecovery.reconcile(container.store, runningSessions) }
        }
        applicationScope.launch { container.catalogue.refreshAll() }
        applicationScope.launch { retentionJanitor() }
    }

    /**
     * Bounded retention, run once at startup and then on a slow cadence. Local
     * history and whole-file snapshots must not grow without limit, and FTS/WAL
     * housekeeping should happen even when no run reaches its end boundary.
     *
     * Every step is individually best-effort: a failed prune must never crash the
     * process, so each call is wrapped in [runCatching].
     */
    private suspend fun retentionJanitor() {
        while (true) {
            runCatching { container.store.prune(keepSessions = KEEP_SESSIONS) }
            runCatching { container.store.sweepSubagentSessions(SUBAGENT_TTL_MS) }
            runCatching { container.snapshots.pruneBounded() }
            runCatching { container.store.maintain() }
            delay(JANITOR_INTERVAL_MS)
        }
    }

    private companion object {
        /** Sessions retained (newest first); older ones are dropped whole. */
        const val KEEP_SESSIONS = 100

        /** Hidden subagent children older than a day are swept with their subtrees. */
        const val SUBAGENT_TTL_MS = 24L * 60L * 60L * 1_000L

        /** Run retention every 6 hours. */
        const val JANITOR_INTERVAL_MS = 6L * 60L * 60L * 1_000L
    }
}

/**
 * Process singletons built once at Application start. The stores share a single
 * [AndroidDatabase] (opened lazily on the first IO call) and are closed only
 * with the process.
 */
class AppContainer(context: Context) : AutoCloseable {

    /** Application context, exposed so the ViewModel factory can build platform helpers. */
    val context: Context = context.applicationContext

    private val database = AndroidDatabase(context)

    val keys = KeyStore(context)
    val store = AndroidSessionStore(database)
    val snapshots = AndroidSnapshotStore(database)
    val catalogue = ModelCatalogue(keys, context)

    override fun close() {
        // Go through the stores' mutex-guarded close rather than calling the
        // shared database directly, so a close cannot race an in-flight query.
        runCatching { store.close() }
        runCatching { snapshots.close() }
    }
}
