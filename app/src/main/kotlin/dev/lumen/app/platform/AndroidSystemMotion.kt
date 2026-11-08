package dev.lumen.app.platform

import android.content.Context
import android.provider.Settings
import dev.lumen.app.ui.DurationScale
import dev.lumen.app.ui.SystemMotion

/**
 * The real [SystemMotion]: reads Android's animator and transition duration
 * scales.
 *
 * The user-facing switch is Accessibility → Remove animations (or Developer
 * options → *duration scale). Either one set to 0 means "no animation", so both
 * are read and combined by [DurationScale].
 *
 * The animator scale is exposed as a **string in the settings database** but the
 * live value the system actually uses is the process-wide one. We prefer
 * `Settings.Global.ANIMATOR_DURATION_SCALE`/`TRANSITION_ANIMATION_SCALE` because
 * they are readable without an Activity and survive the app being backgrounded.
 *
 * Every read is wrapped: a SecurityException or a missing row must degrade to
 * "motion allowed", never crash and never silently disable the UI.
 */
class AndroidSystemMotion(private val context: Context) : SystemMotion {

    override fun reduceMotionEnabled(): Boolean {
        val global = context.contentResolver
        val animator = scale(Settings.Global.ANIMATOR_DURATION_SCALE, global::getFloat)
        val transition = scale(Settings.Global.TRANSITION_ANIMATION_SCALE, global::getFloat)
        // If neither channel could be read, do not claim motion should be
        // reduced: an unreadable setting is not a user preference.
        if (animator == null && transition == null) return false
        return DurationScale.reduceMotion(
            animatorScale = animator ?: DEFAULT_SCALE,
            transitionScale = transition ?: DEFAULT_SCALE,
        )
    }

    /**
     * `getFloat` on these keys throws when the row exists but is stored as a
     * string (which some OEM builds and older Android versions do), so a failed
     * float read falls back to parsing the string form.
     */
    private fun scale(key: String, getFloat: (android.content.ContentResolver, String, Float) -> Float): Float? {
        val resolver = context.contentResolver
        return try {
            getFloat(resolver, key, DEFAULT_SCALE)
        } catch (_: Settings.SettingNotFoundException) {
            null
        } catch (_: SecurityException) {
            null
        } catch (_: Throwable) {
            // Wrong storage type: retry as text before giving up.
            runCatching {
                Settings.Global.getString(resolver, key)?.trim()?.toFloatOrNull()
            }.getOrNull()
        }
    }

    private companion object {
        /** Android's own default when a duration scale has never been set. */
        const val DEFAULT_SCALE = 1f
    }
}
