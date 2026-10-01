package dev.lumen.app.platform

import android.view.Choreographer
import java.util.Locale

/**
 * The rolled-up result of one sampled agent run. Pure data so it can be
 * summarized off-device and unit-tested.
 */
data class PerfSummary(
    val frames: Int,
    val jankPercent: Double,
    val p95Ms: Double,
    val worstMs: Double,
    val peakHeapMb: Int,
) {
    /** One line, shaped for the diagnostics log (which the UI can copy out). */
    fun line(): String = String.format(
        Locale.US,
        "perf: %d frames · %.0f%% janky · p95 %.0fms · worst %.0fms · peak heap %dMB",
        frames, jankPercent, p95Ms, worstMs, peakHeapMb,
    )
}

/** Pure frame-interval math: no Android types, so it is JVM-testable. */
object PerfStats {
    /** A frame slower than ~2 vsync intervals (16.7ms) is counted as janky. */
    const val JANK_MS = 33.0

    fun summarize(frameIntervalsMs: List<Double>, peakHeapMb: Int = 0): PerfSummary {
        if (frameIntervalsMs.isEmpty()) return PerfSummary(0, 0.0, 0.0, 0.0, peakHeapMb)
        val sorted = frameIntervalsMs.sorted()
        val janky = sorted.count { it > JANK_MS }
        val p95Index = ((sorted.size - 1) * 0.95).toInt().coerceIn(0, sorted.size - 1)
        return PerfSummary(
            frames = sorted.size,
            jankPercent = janky * 100.0 / sorted.size,
            p95Ms = sorted[p95Index],
            worstMs = sorted.last(),
            peakHeapMb = peakHeapMb,
        )
    }
}

/**
 * Samples UI frame timing and Java-heap pressure while an agent run is live, so
 * the on-device perf/ANR sweep has real numbers instead of impressions.
 *
 * Choreographer is main-thread only, so [start]/[stop] must be called on the
 * main thread (they are: the run job is on the main dispatcher). Every call is
 * defensive — a device without a Looper degrades to an empty summary rather
 * than crashing the run.
 */
class PerfSampler {

    private val intervalsNanos = ArrayList<Long>(1024)
    private var peakHeapMb = 0
    private var running = false
    private var lastFrameNanos = 0L

    private val callback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            if (lastFrameNanos != 0L) intervalsNanos.add(frameTimeNanos - lastFrameNanos)
            lastFrameNanos = frameTimeNanos
            sampleHeap()
            runCatching { Choreographer.getInstance().postFrameCallback(this) }
        }
    }

    /** Begin sampling. A second call while running is a no-op. */
    fun start() {
        if (running) return
        runCatching {
            intervalsNanos.clear()
            peakHeapMb = 0
            lastFrameNanos = 0L
            running = true
            Choreographer.getInstance().postFrameCallback(callback)
        }
    }

    /** Stop and return the summary for the elapsed run. */
    fun stop(): PerfSummary {
        if (!running) return PerfStats.summarize(emptyList(), peakHeapMb)
        running = false
        runCatching { Choreographer.getInstance().removeFrameCallback(callback) }
        val ms = intervalsNanos.map { it / 1_000_000.0 }
        return PerfStats.summarize(ms, peakHeapMb)
    }

    private fun sampleHeap() {
        val rt = Runtime.getRuntime()
        val usedMb = ((rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)).toInt()
        if (usedMb > peakHeapMb) peakHeapMb = usedMb
    }
}
