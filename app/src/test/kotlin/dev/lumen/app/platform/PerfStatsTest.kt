package dev.lumen.app.platform

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The perf summary is the only part of the on-device sampler that can be pinned
 * off-device: frame percentiles, the jank ratio, and the one-line rendering.
 */
class PerfStatsTest {

    @Test
    fun `an empty run is zeroed but keeps the heap reading`() {
        val s = PerfStats.summarize(emptyList(), peakHeapMb = 42)
        assertEquals(0, s.frames)
        assertEquals(0.0, s.jankPercent)
        assertEquals(0.0, s.worstMs)
        assertEquals(42, s.peakHeapMb)
    }

    @Test
    fun `jank ratio counts frames slower than two vsyncs`() {
        val intervals = listOf(16.0, 16.0, 16.0, 40.0, 100.0, 8.0)
        val s = PerfStats.summarize(intervals, peakHeapMb = 12)

        assertEquals(6, s.frames)
        assertEquals(100.0, s.worstMs)
        assertEquals(2 * 100.0 / 6, s.jankPercent, 0.001)
        assertEquals(40.0, s.p95Ms)
        assertEquals(12, s.peakHeapMb)
    }

    @Test
    fun `the summary line carries the headline numbers`() {
        val line = PerfStats.summarize(listOf(16.0, 16.0), peakHeapMb = 7).line()
        assertTrue(line.startsWith("perf:"), line)
        assertTrue(line.contains("2 frames"), line)
        assertTrue(line.contains("peak heap 7MB"), line)
    }
}
