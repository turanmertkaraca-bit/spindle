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
 * Bounded frame-interval accumulator. Pure (no Android types) so the cap can be
 * JVM-tested; it stops growing at [maxSamples] while still counting frames and
 * remembering the worst interval.
 */
internal class FrameSamples(private val maxSamples: Int = MAX_SAMPLES) {

    private val intervalsNanos = ArrayList<Long>(1024)
    private var totalFrames = 0
    private var worstNanos = 0L

    val size: Int get() = intervalsNanos.size

    /** True once no more samples will be retained. */
    val saturated: Boolean get() = intervalsNanos.size >= maxSamples

    fun reset() {
        intervalsNanos.clear()
        totalFrames = 0
        worstNanos = 0L
    }

    fun add(deltaNanos: Long) {
        totalFrames++
        if (deltaNanos > worstNanos) worstNanos = deltaNanos
        if (intervalsNanos.size < maxSamples) intervalsNanos.add(deltaNanos)
    }

    fun summary(peakHeapMb: Int): PerfSummary {
        val ms = intervalsNanos.map { it / 1_000_000.0 }
        val base = PerfStats.summarize(ms, peakHeapMb)
        return base.copy(
            frames = maxOf(base.frames, totalFrames),
            worstMs = maxOf(base.worstMs, worstNanos / 1_000_000.0),
        )
    }

    companion object {
        /** ~16 minutes at 60fps: enough for diagnostics, bounded in memory. */
        const val MAX_SAMPLES = 60_000
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
 *
 * The sampler self-terminates after [FrameSamples.MAX_SAMPLES] frames even if
 * [stop] is never called, so a lost teardown cannot leave a Choreographer
 * callback running (and accumulating) forever.
 */
class PerfSampler internal constructor(private val samples: FrameSamples) {

    constructor() : this(FrameSamples())

    private var peakHeapMb = 0
    private var running = false
    private var lastFrameNanos = 0L

    private val callback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            if (lastFrameNanos != 0L) samples.add(frameTimeNanos - lastFrameNanos)
            lastFrameNanos = frameTimeNanos
            sampleHeap()
            if (samples.saturated) {
                running = false
                return
            }
            runCatching { Choreographer.getInstance().postFrameCallback(this) }
        }
    }

    /**
     * Begin (or restart) sampling. Unlike the old no-op behaviour, a call while
     * already running resets the window, so a superseding run does not inherit
     * and keep growing a previous run's samples.
     */
    fun start() {
        runCatching { Choreographer.getInstance().removeFrameCallback(callback) }
        runCatching {
            samples.reset()
            peakHeapMb = 0
            lastFrameNanos = 0L
            running = true
            Choreographer.getInstance().postFrameCallback(callback)
        }
    }

    /** Stop and return the summary for the elapsed run. Idempotent. */
    fun stop(): PerfSummary {
        val wasRunning = running
        running = false
        runCatching { Choreographer.getInstance().removeFrameCallback(callback) }
        if (!wasRunning && samples.size == 0) return PerfStats.summarize(emptyList(), peakHeapMb)
        return samples.summary(peakHeapMb)
    }

    private fun sampleHeap() {
        val rt = Runtime.getRuntime()
        val usedMb = ((rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)).toInt()
        if (usedMb > peakHeapMb) peakHeapMb = usedMb
    }
}
