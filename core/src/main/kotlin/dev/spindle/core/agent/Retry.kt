package dev.spindle.core.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** Classifies a provider/network failure as retryable or terminal. */
object Retry {
    fun isRetryable(message: String): Boolean {
        val m = message.lowercase()
        // Explicit non-retryable signals win over the retryable markers: a 500
        // buried in "invalid api key (500)" or a 512 in "context length 512
        // exceeded" must not be retried.
        if (Regex("\\b40[0134]\\b").containsMatchIn(m)) return false
        if ("invalid api key" in m) return false
        if ("unauthorized" in m) return false
        if ("context length" in m) return false
        if ("content filter" in m) return false
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
            Regex("\\beof\\b").containsMatchIn(m) -> true
            else -> false
        }
    }

    /**
     * True when the failure is a connectivity problem (dropped wifi, a switch to
     * hotspot, DNS unavailable, a dead socket) rather than a provider rejection.
     * These must never end a run on a mobile device: we wait and reconnect.
     */
    fun isNetwork(message: String): Boolean {
        val m = message.lowercase()
        return when {
            "unable to resolve host" in m -> true
            "unknownhost" in m -> true
            "no address associated" in m -> true
            "network is unreachable" in m -> true
            "network unreachable" in m -> true
            "failed to connect" in m -> true
            "failed to resolve" in m -> true
            "connect timed out" in m -> true
            "connection reset" in m -> true
            "connection refused" in m -> true
            "connection abort" in m -> true
            "broken pipe" in m -> true
            "unexpected end of stream" in m -> true
            "unexpected end of file" in m -> true
            "software caused connection" in m -> true
            "ssl handshake" in m -> true
            "sslexception" in m -> true
            "host is down" in m -> true
            "i/o error" in m -> true
            "timeout" in m -> true
            "timed out" in m -> true
            Regex("\\beof\\b").containsMatchIn(m) -> true
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

    /**
     * "Never interrupt itself" retry: a connectivity failure (wifi dropped, a
     * switch to hotspot, DNS gone) is retried indefinitely with a bounded backoff
     * until the link returns; other transient failures (429/5xx) still get only
     * [fatalRetries] attempts. Auth/context/filter rejections are fatal. The only
     * way out is the caller cancelling (the user pressing stop), which propagates
     * immediately.
     */
    suspend fun <T> withResilientRetry(
        fatalRetries: Int,
        onRetry: suspend (attempt: Int, error: Throwable, waitMs: Long) -> Unit = { _, _, _ -> },
        isNetworkError: (Throwable) -> Boolean = { isNetwork(it.message ?: "") },
        classify: (Throwable) -> Boolean = { isRetryable(it.message ?: "") },
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
                val network = isNetworkError(e)
                // Fatal rejections (bad key, context length, content filter) end
                // the run at once; they will never succeed by waiting.
                if (!network && !classify(e)) throw e
                if (!network && attempt > fatalRetries) throw e
                // Connectivity: patient, capped backoff. Other transient: normal.
                val wait = if (network) {
                    backoffMs(attempt, base = 1_000, cap = 30_000)
                } else {
                    backoffMs(attempt)
                }
                onRetry(attempt, e, wait)
                delay(wait)
            }
        }
    }
}
