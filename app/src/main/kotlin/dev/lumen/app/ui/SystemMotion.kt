package dev.lumen.app.ui

/**
 * Reads the platform's own reduce-motion setting.
 *
 * Android has no `Settings.Secure.REDUCE_MOTION`; the accessibility switch the
 * user actually flips ("Remove animations" under Accessibility → Remove
 * animations, or Developer options → Transition/Animator duration scale) is
 * exposed through the global animator duration scale. When that scale is 0,
 * every animation the system runs is instant, and an app that keeps looping is
 * not just inconsistent with the rest of the phone — it is the exact thing the
 * user asked to stop.
 *
 * This interface exists so the decision is testable on a plain JVM:
 * `MotionPolicy` holds the rules, [SystemMotion] holds the platform read, and the
 * app's `KeyStore` seeds its stored preference from [reduceMotionEnabled].
 */
interface SystemMotion {
    /**
     * True when the platform asks for reduced motion.
     *
     * Implementations must return **false** when the value cannot be read. An
     * unreadable setting is not evidence that the user wants motion reduced, and
     * defaulting to true would silently disable every animation on a device
     * where the query simply failed.
     */
    fun reduceMotionEnabled(): Boolean
}

/**
 * Infers reduce-motion from duration scales.
 *
 * Split out from the Android read so the arithmetic is verifiable without a
 * device: the platform hands us two floats and the rule is purely about their
 * minimum.
 */
object DurationScale {
    /**
     * True when either scale is zero-ish.
     *
     * Animator and transition scales are independent settings — a user can set
     * one to 0 and leave the other at 1 — so taking the **minimum** is the only
     * safe reading: if either channel is off, the user wants no motion.
     *
     * The epsilon absorbs float noise from the settings database. A scale of
     * exactly 0 is the documented "off" value; anything below [EPSILON] is
     * treated the same way rather than requiring an exact comparison.
     */
    fun reduceMotion(animatorScale: Float, transitionScale: Float): Boolean =
        animatorScale <= EPSILON || transitionScale <= EPSILON

    /** Float noise tolerance for a "zero" duration scale. */
    const val EPSILON = 0.001f
}
