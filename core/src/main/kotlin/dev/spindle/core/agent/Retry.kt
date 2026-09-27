package dev.spindle.core.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** Classifies a provider/network failure as retryable or terminal. */
object Retry {
    fun isRetryable(message: String): Boolean {
        val m = message.lowercase()
        return when {
            // HTTP-ish status markers the adapters embed in Failure messages.
            Regex("\\b429\\b").containsMatchIn(m) -> true
            Regex("\\b5\\d\\d\\b").containsMatchIn(m) -> true
            "timeout" in m -> true
            "timed out" in m -> true
            "connection reset" in m -> true
            "connection refused" in m -> true
            "temporarily unavailable" in m -> true
            "overloaded" in m -> true
            "rate limit" in m -> true
            "eof" in m -> true
            // Explicit non-retryable signals.
            Regex("\\b40[0134]\\b").containsMatchIn(m) -> false
            "invalid api key" in m -> false
            "unauthorized" in m -> false
            "context length" in m -> false
            "content filter" in m -> false
            else -> false
        }
    }

    /** Exponential backoff with jitter: attempts start at 1. */
    fun backoffMs(attempt: Int, base: Long = 500, cap: Long = 8_000): Long {
        val exp = (base shl (attempt - 1)).coerceAtMost(cap)
        val jitter = (Math.random() * (exp / 4)).toLong()
        return exp + jitter
    }

    /**
     * Run [block]; retry while [classify] says the thrown error is retryable and
     * attempts remain. [onRetry] is called before each wait so the caller can emit
     * progress. Cancellation always propagates immediately.
     */
    suspend fun <T> withRetry(
        maxRetries: Int,
        classify: (Throwable) -> Boolean = { isRetryable(it.message ?: "") },
        onRetry: suspend (attempt: Int, error: Throwable) -> Unit = { _, _ -> },
        block: suspend () -> T,
    ): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                attempt++
                if (attempt > maxRetries || !classify(e)) throw e
                onRetry(attempt, e)
                delay(backoffMs(attempt))
            }
        }
    }
}
