package dev.lumen.app

import android.app.Application
import android.content.Context
import dev.lumen.app.data.AndroidDatabase
import dev.lumen.app.data.AndroidSessionStore
import dev.lumen.app.data.AndroidSnapshotStore
import dev.lumen.app.data.KeyStore
import dev.lumen.app.data.ModelCatalogue
import dev.spindle.core.event.EventBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
        applicationScope.launch { container.catalogue.refreshAll() }
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
        runCatching { database.close() }
    }
}
