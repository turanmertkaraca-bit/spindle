package dev.lumen.app.platform

import android.content.ContentResolver
import android.content.Context
import android.provider.Settings
import dev.lumen.app.ui.DurationScale
import dev.lumen.app.ui.SystemMotion

/**
 * The real [SystemMotion]: reads Android's animator and transition duration
 * scales.
 *
 * The user-facing switch is Accessibility → Remove animations (or Developer
 * options → *duration scale*). Either one set to 0 means "no animation", so both
 * are read and combined by [DurationScale].
 *
 * The live values live in `Settings.Global`, which is readable without an
 * Activity and survives the app being backgrounded. Every read is wrapped: a
 * `SecurityException`, a missing row or a row stored as text must degrade to
 * "motion allowed" — never crash, and never silently disable the whole UI.
 */
class AndroidSystemMotion(private val context: Context) : SystemMotion {

    override fun reduceMotionEnabled(): Boolean {
        val animator = globalScale(Settings.Global.ANIMATOR_DURATION_SCALE)
        val transition = globalScale(Settings.Global.TRANSITION_ANIMATION_SCALE)
        // If neither channel could be read, do not claim motion should be
        // reduced: an unreadable setting is not a user preference, and defaulting
        // to "reduce" would strip every animation on a device where the query
        // merely failed.
        if (animator == null && transition == null) return false
        return DurationScale.reduceMotion(
            animatorScale = animator ?: DEFAULT_SCALE,
            transitionScale = transition ?: DEFAULT_SCALE,
        )
    }

    /**
     * Read one global duration scale.
     *
     * `Settings.Global.getFloat` throws [Settings.SettingNotFoundException] when
     * the row is absent, and `ClassCastException` when it exists but was written
     * as a string — which some OEM builds and older Android versions do. Both are
     * handled here so the caller only ever sees a value or null.
     */
    private fun globalScale(key: String): Float? {
        val resolver: ContentResolver = context.contentResolver
        return try {
            Settings.Global.getFloat(resolver, key)
        } catch (_: Settings.SettingNotFoundException) {
            null
        } catch (_: SecurityException) {
            null
        } catch (_: Throwable) {
            // Wrong storage type: retry as text before giving up.
            runCatching { Settings.Global.getString(resolver, key)?.trim()?.toFloatOrNull() }
                .getOrNull()
        }
    }

    private companion object {
        /** Android's own default when a duration scale has never been set. */
        const val DEFAULT_SCALE = 1f
    }
}
