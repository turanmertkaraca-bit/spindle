package dev.spindle.core.ui

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/**
 * A damped travelling wave along the rope, as pure math.
 *
 * After a droplet is appended the rope "turns and twists into place": a
 * sinusoidal lateral displacement races along it while an exponential envelope
 * bleeds the energy away. The caller passes elapsed seconds; nothing here owns a
 * clock.
 *
 * `envelope = if (elapsed <= 0) 1 else exp(-decay * elapsed)` and
 * `displacement = amplitude * envelope * sin((2π / wavelength) * (dy - speed *
 * elapsed)) * exp(-abs(dy) / fadeLength)`, so [speed] is the wave's travel in
 * pixels per second.
 */
object RopeWave {

    /** 1 at the append, decays toward 0. */
    fun envelope(elapsed: Float, decay: Float = 3.2f): Float =
        if (elapsed <= 0f) 1f else exp(-decay * elapsed)

    /**
     * Lateral displacement (px) at rope offset [dy] after [elapsed] seconds.
     * amplitude, wavelength, speed in px / px / px-per-second.
     */
    fun displacement(
        dy: Float,
        elapsed: Float,
        amplitude: Float,
        wavelength: Float,
        speed: Float,
        fadeLength: Float,
        decay: Float = 3.2f,
    ): Float {
        if (amplitude == 0f || wavelength <= 0f || fadeLength <= 0f) return 0f
        val env = envelope(elapsed, decay)
        if (env == 0f) return 0f
        val phase = (2f * PI.toFloat() / wavelength) * (dy - speed * elapsed)
        return amplitude * env * sin(phase) * exp(-abs(dy) / fadeLength)
    }
}
