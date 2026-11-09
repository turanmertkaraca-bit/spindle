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
import android.os.Looper
import org.robolectric.Shadows.shadowOf
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse

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

        fun timeline() = vm.state.value.steps
        val partId = PartId("prt_text")

        // Attempt 1 streams live; a state event flushes the coalesced deltas into
        // the timeline exactly as a frame-sized flush would during a real run.
        bus.emit(AgentEvent.PartDelta(sid, "msg_1", partId, DeltaKind.TEXT, "stale "))
        bus.emit(AgentEvent.PartDelta(sid, "msg_1", partId, DeltaKind.TEXT, "attempt"))
        bus.emit(AgentEvent.StateChanged(sid, SessionState.RUNNING))
        awaitCondition("the live deltas to reach the timeline") {
            lastTimeline = timeline()
            timeline().any { it.body == "stale attempt" }
        }

        // The retry signal drops the part and refreshes the timeline.
        bus.emit(AgentEvent.PartReset(sid, "msg_1", partId))
        assertFalse(
            timeline().any { it.id == partId.value },
            "PartReset must remove the failed attempt's row",
        )

        // The retried attempt rebuilds the same part from its own deltas. The rebuild
        // happens on Dispatchers.Default, so wait for it rather than assuming.
        bus.emit(AgentEvent.PartDelta(sid, "msg_1", partId, DeltaKind.TEXT, "fresh"))
        bus.emit(AgentEvent.Error(sid, "streamed"))
        awaitCondition("the retried text to start clean") {
            lastTimeline = timeline()
            timeline().filter { it.id == partId.value }.map { it.body } == listOf("fresh")
        }

        assertEquals(
            listOf("fresh"),
            timeline().filter { it.id == partId.value }.map { it.body },
            "the retry's text must start clean",
        )
    }

    /**
     * Poll until [condition] holds, or fail with the timeline that was seen.
     *
     * Deliberately NOT "poll and return the last value": that shape is what made
     * this test fail twice (d9e880a, then c616516). A build that landed 20 ms late
     * handed back an empty list, which then read as a product bug. A test helper
     * must either observe the condition or fail saying what it observed.
     *
     * The rebuild runs on `Dispatchers.Default` (real threads) inside
     * `viewModelScope`, so there is no frame callback to await; polling the observed
     * state is the honest way to wait. The window is generous because CI is shared.
     */
    private fun awaitCondition(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000L
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(20)
        }
        throw AssertionError(
            "$what did not happen within 15s; timeline was " +
                lastTimeline.map { it.id to it.body },
        )
    }

    /** The most recently observed timeline, for failure messages. */
    @Volatile
    private var lastTimeline: List<dev.lumen.app.ui.model.UiStep> = emptyList()
}
