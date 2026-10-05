package dev.lumen.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.lumen.app.data.KeyStore
import dev.spindle.core.event.AgentEvent
import dev.spindle.core.event.DeltaKind
import dev.spindle.core.event.EventBus
import dev.spindle.core.model.PartId
import dev.spindle.core.model.Session
import dev.spindle.core.model.SessionId
import dev.spindle.core.model.SessionState
import dev.spindle.core.store.InMemorySessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A retried provider attempt must retract the failed attempt's live text without
 * buffering the normal stream. Driving the ViewModel's real event collector
 * (Robolectric + an unconfined main so launched coroutines settle inline) proves
 * [AgentEvent.PartReset] clears the streamed row and lets the retry re-accumulate.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RetryResetTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun keys(): KeyStore =
        KeyStore(ApplicationProvider.getApplicationContext<Context>())

    @Test
    fun `PartReset drops the failed attempt's streamed text and lets the retry re-accumulate`() {
        val dir = Files.createTempDirectory("lumen-reset").toFile()
        val store = InMemorySessionStore()
        val sid = SessionId("ses_reset")
        runBlocking { store.createSession(Session(sid, "t", dir.path, 0, 0)) }

        val bus = EventBus()
        val vm = ChatViewModel(dir.toPath(), keys(), store, eventBus = bus)
        vm.openSession(sid.value)

        val partId = PartId("prt_text")
        // Attempt 1 streams live; a state event flushes the coalesced deltas into
        // the timeline exactly as a frame-sized flush would during a real run.
        bus.emit(AgentEvent.PartDelta(sid, "msg_1", partId, DeltaKind.TEXT, "stale "))
        bus.emit(AgentEvent.PartDelta(sid, "msg_1", partId, DeltaKind.TEXT, "attempt"))
        bus.emit(AgentEvent.StateChanged(sid, SessionState.RUNNING))
        assertTrue(
            vm.state.value.steps.any { it.body == "stale attempt" },
            "live deltas must reach the timeline before the reset",
        )

        // The retry signal drops the part and refreshes the timeline.
        bus.emit(AgentEvent.PartReset(sid, "msg_1", partId))
        assertFalse(
            vm.state.value.steps.any { it.id == partId.value },
            "PartReset must remove the failed attempt's row",
        )

        // The retried attempt rebuilds the same part from its own deltas.
        bus.emit(AgentEvent.PartDelta(sid, "msg_1", partId, DeltaKind.TEXT, "fresh"))
        bus.emit(AgentEvent.Error(sid, "streamed"))
        val bodies = vm.state.value.steps.filter { it.id == partId.value }.map { it.body }
        assertEquals(listOf("fresh"), bodies, "the retry's text must start clean")
    }
}
