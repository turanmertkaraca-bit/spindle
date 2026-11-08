package dev.lumen.app.ui

/**
 * The app's motion policy: which animations may run, and how fast.
 *
 * ## Why this is a separate, pure object
 *
 * The owner's requirement is safety, not taste: *"won't trigger some people's
 * seizures every time the AI writes"*. A rule like "no animation may loop faster
 * than one cycle per second, and every loop must stop under reduce-motion" is
 * only trustworthy if it is enforced in one testable place instead of being
 * re-checked (and eventually forgotten) at each call site.
 *
 * Everything here is plain Kotlin with no Android dependency, so the policy is
 * verified by fast JVM tests rather than by `:app` Robolectric, which cannot run
 * in this project's dev environment.
 *
 * ## The rules
 *
 * 1. **No loop faster than 1 Hz.** A visible cycle shorter than
 *    [MIN_LOOP_PERIOD_MS] is a strobe. This is a hard floor, not a preference —
 *    photosensitive epilepsy guidance puts the danger zone around 3 Hz and up,
 *    and 1 Hz leaves margin for animation-scale changes on the device.
 * 2. **Low amplitude.** A cycle must not swing opacity from invisible to solid.
 *    [MAX_PULSE_ALPHA] / [MIN_PULSE_ALPHA] keep the change gentle.
 * 3. **Reduce-motion wins.** When motion is reduced, nothing loops at all:
 *    [pulseAlphaRange] returns a constant, so callers can read the value every
 *    frame with no branch in the draw path.
 *
 * ## Where the reduce-motion flag comes from
 *
 * Android exposes the user's "Remove animations" accessibility setting as the
 * global animator duration scale. See [SystemMotion]; the app seeds its stored
 * preference from it on first run so an explicit user choice still wins.
 */
object MotionPolicy {
    /**
     * Shortest permitted period for ANY looping animation, in milliseconds.
     * 1000 ms = 1 cycle/second. Slower is fine; faster is a strobe.
     */
    const val MIN_LOOP_PERIOD_MS = 1_000

    /**
     * The default streaming pulse period. Deliberately well above the 1 s floor:
     * a "breathing" indicator should read as alive, not as a flash. Two seconds
     * also means the eye never catches a sharp edge during a long answer.
     */
    const val STREAMING_PULSE_PERIOD_MS = 2_000

    /** Opacity at the dimmest point of the streaming pulse. */
    const val MIN_PULSE_ALPHA = 0.62f

    /** Opacity at the brightest point. Never 1.0 of a saturated colour. */
    const val MAX_PULSE_ALPHA = 1.0f

    /**
     * Coerce any requested loop period up to the safe floor.
     *
     * Callers pass the period they *want*; this guarantees they cannot ship a
     * strobe by picking a small number, and it is the single place the floor is
     * written down.
     */
    fun safeLoopPeriod(requestedMs: Int): Int = maxOf(requestedMs, MIN_LOOP_PERIOD_MS)

    /**
     * Whether a looping animation may run at all.
     *
     * False under reduce-motion, and false when the caller asked for an ambient
     * effect that is not wanted right now (idle, backgrounded, tests).
     */
    fun loopsAllowed(reduceMotion: Boolean, ambient: Boolean = true): Boolean =
        ambient && !reduceMotion

    /**
     * The `(from, to)` opacity values for the streaming indicator.
     *
     * Under reduce-motion this collapses to `(1f, 1f)`: a constant, so the draw
     * path needs no special case and nothing on screen changes between frames.
     * That matters because a reduce-motion app that still schedules frame
     * callbacks burns battery to render identical pixels.
     */
    fun pulseAlphaRange(reduceMotion: Boolean, ambient: Boolean = true): Pair<Float, Float> =
        if (loopsAllowed(reduceMotion, ambient)) MIN_PULSE_ALPHA to MAX_PULSE_ALPHA else 1f to 1f

    /**
     * The streaming pulse period, or null when the pulse must not run.
     *
     * Returning null rather than a sentinel duration is deliberate: a caller that
     * ignores the null cannot accidentally start an animation, because there is
     * no number to pass.
     */
    fun streamingPulsePeriodMs(reduceMotion: Boolean, ambient: Boolean = true): Int? =
        if (loopsAllowed(reduceMotion, ambient)) safeLoopPeriod(STREAMING_PULSE_PERIOD_MS) else null
}
