package dev.lumen.app.ui

/**
 * Decides what to say when a run ends.
 *
 * ## Why this is a separate, pure object
 *
 * The owner's report — *"it dropped the chat without an interrupt alert, it just
 * stopped"* — is a **silence** bug. Silence has many causes (a user Stop, a
 * project switch, a crash inside the loop, a lost terminal event, a deprecated
 * coroutine), and the only way to make "never silent" a property rather than a
 * hope is to compute the message in one place and test every cause.
 *
 * `:app` behaviour is CI-only in this project, so anything embedded in the
 * ViewModel is effectively unverified. These rules are plain Kotlin and are
 * checked by JVM tests on every build.
 */
object RunEndPolicy {

    /**
     * Why a run ended, from the app's point of view.
     *
     * Ordered from most to least intentional. The mapping to a message is
     * exhaustive on purpose: adding a case forces a decision about what the user
     * is told.
     */
    enum class Cause {
        /** The user pressed Stop. Silent by definition — they did it. */
        USER_STOP,

        /** The user changed project, so the run's cwd no longer applies. */
        PROJECT_SWITCH,

        /** The user closed or deleted the chat the run belonged to. */
        CHAT_CLOSED,

        /** A newer send superseded this run. The new run speaks for itself. */
        SUPERSEDED,

        /** The loop itself reported a failure. */
        LOOP_ERROR,

        /** The loop returned normally. */
        FINISHED,

        /**
         * The coroutine ended and the loop never reported ANY outcome.
         *
         * This is the case that produced the owner's report, and the only one
         * that had no user-visible trace at all.
         */
        VANISHED,
    }

    /** What the user should be told, or null for "say nothing". */
    data class Notice(
        val message: String?,
        /** True when the shade notification must fire (backgrounded safety). */
        val notify: Boolean,
    )

    private val silent = Notice(message = null, notify = false)

    /**
     * The notice for [cause].
     *
     * Rules encoded here:
     *  - A user Stop is silent: an alert for a thing you just did is noise.
     *  - A superseded run is silent: the new run's own outcome is the news.
     *  - Everything else MUST say something. A long agent run that ends without a
     *    word is the failure mode being fixed.
     *  - Anything triggered by something other than an in-app Stop also notifies,
     *    because the user may be in another app when it happens.
     */
    fun notice(cause: Cause, detail: String? = null): Notice {
        val suffix = detail?.takeIf { it.isNotBlank() }?.let { " \u00b7 $it" }.orEmpty()
        return when (cause) {
            Cause.USER_STOP -> silent
            Cause.SUPERSEDED -> silent
            Cause.PROJECT_SWITCH ->
                Notice("run stopped \u00b7 $detail", notify = true).withFallback(cause)
            Cause.CHAT_CLOSED ->
                Notice("run stopped \u00b7 the chat was closed", notify = true)
            Cause.LOOP_ERROR ->
                Notice("run failed$suffix", notify = true)
            Cause.FINISHED ->
                Notice("run finished$suffix", notify = false)
            Cause.VANISHED ->
                Notice(
                    "the run stopped unexpectedly before it finished its answer",
                    notify = true,
                )
        }
    }

    /** Keep a blank detail from producing "run stopped · null". */
    private fun Notice.withFallback(cause: Cause): Notice =
        if (message.isNullOrBlank() || message.contains("null")) {
            notice(cause, "the workspace changed")
        } else {
            this
        }

    /**
     * Classify how a run ended.
     *
     * Deliberately takes plain flags rather than app types so the classification
     * itself is testable, and so the decision is impossible to make implicitly in
     * a `finally` block.
     */
    fun classify(
        userStopped: Boolean,
        superseded: Boolean,
        loopReturned: Boolean,
        loopFailed: Boolean,
        reason: String?,
    ): Cause = when {
        userStopped -> Cause.USER_STOP
        superseded -> Cause.SUPERSEDED
        reason?.contains("project", ignoreCase = true) == true -> Cause.PROJECT_SWITCH
        reason?.contains("chat", ignoreCase = true) == true -> Cause.CHAT_CLOSED
        loopFailed -> Cause.LOOP_ERROR
        loopReturned -> Cause.FINISHED
        else -> Cause.VANISHED
    }
}
