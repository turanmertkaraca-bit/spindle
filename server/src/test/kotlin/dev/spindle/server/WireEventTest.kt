package dev.spindle.server

import dev.spindle.core.event.AgentEvent
import dev.spindle.core.model.PartId
import dev.spindle.core.model.SessionId
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The wire mapping must stay exhaustive: [WireEvent.of] is a `when` over the
 * sealed [AgentEvent] hierarchy, so a new event (e.g. `PartReset`) that is not
 * handled fails to compile, and one that is handled must round-trip over SSE.
 */
class WireEventTest {

    private val json = Json { encodeDefaults = true }

    @Test
    fun `PartReset maps to the wire and round-trips`() {
        val reset = AgentEvent.PartReset(SessionId("ses_1"), "msg_1", PartId("prt_1"))

        val wire = WireEvent.of(reset)

        assertEquals(WireEvent.PartReset("ses_1", "msg_1", "prt_1"), wire)
        val encoded = json.encodeToString(WireEvent.serializer(), wire)
        assertTrue(encoded.contains("\"partId\":\"prt_1\""), encoded)
        assertEquals(wire, json.decodeFromString(WireEvent.serializer(), encoded))
    }
}
