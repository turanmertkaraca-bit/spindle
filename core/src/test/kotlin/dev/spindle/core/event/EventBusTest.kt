package dev.spindle.core.event

import dev.spindle.core.model.SessionId
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The sequenced side of [EventBus]: every emission gets a monotonic id, the
 * bounded ring answers `Last-Event-ID` catch-up in order, and old entries are
 * evicted once the ring is full. The untagged [EventBus.events] stream must keep
 * working unchanged for existing collectors.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class EventBusTest {

    private fun event(sid: String = "ses_1", text: String = "x") =
        AgentEvent.Progress(SessionId(sid), text)

    @Test
    fun `seq increases monotonically from one`() {
        val bus = EventBus()
        assertEquals(0L, bus.lastSeq(), "no seq before the first event")

        bus.emit(event(text = "a"))
        bus.emit(event(text = "b"))
        bus.emit(event(text = "c"))

        assertEquals(3L, bus.lastSeq())
        assertEquals(listOf(1L, 2L, 3L), bus.replayAfter(0).map { it.seq })
    }

    @Test
    fun `replayAfter returns only later entries, in order`() {
        val bus = EventBus()
        (1..5).forEach { bus.emit(event(text = "$it")) }

        val after2 = bus.replayAfter(2)
        assertEquals(listOf(3L, 4L, 5L), after2.map { it.seq })
        assertTrue(after2.all { it.event is AgentEvent.Progress })

        assertTrue(bus.replayAfter(5).isEmpty(), "nothing is newer than the head")
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), bus.replayAfter(0).map { it.seq })
    }

    @Test
    fun `the ring evicts the oldest entries at capacity`() {
        val bus = EventBus()
        val capacity = EventBus.REPLAY_CAPACITY
        val total = capacity + 5
        repeat(total) { bus.emit(event(text = "$it")) }

        val retained = bus.replayAfter(0)
        assertEquals(capacity, retained.size, "the ring is capped at capacity")
        assertEquals(6L, retained.first().seq, "the five oldest entries were evicted")
        assertEquals(total.toLong(), retained.last().seq)
        assertEquals(total.toLong(), bus.lastSeq())
    }

    @Test
    fun `sequenced tags the same events the plain flow carries`() = runTest {
        val bus = EventBus()
        val tagged = mutableListOf<EventBus.Entry>()
        val plain = mutableListOf<AgentEvent>()
        val a = launch { bus.sequenced.collect { tagged += it } }
        val b = launch { bus.events.collect { plain += it } }
        runCurrent()

        bus.emit(event(text = "a"))
        bus.emit(event(text = "b"))
        runCurrent()
        a.cancel()
        b.cancel()

        assertEquals(listOf(1L, 2L), tagged.map { it.seq })
        assertEquals(listOf("a", "b"), tagged.map { (it.event as AgentEvent.Progress).message })
        assertEquals(2, plain.size, "existing collectors of events keep receiving every event")
    }
}
