package dev.spindle.core.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RetryTest {

    private suspend fun capture(block: suspend () -> Unit): Throwable? =
        try {
            block()
            null
        } catch (e: Throwable) {
            e
        }

    @Test
    fun `retryable failures are classified as retryable`() {
        listOf(
            "HTTP 429 Too Many Requests",
            "server returned 500",
            "503 service unavailable",
            "request timeout after 30s",
            "connection reset by peer",
            "the request timed out",
            "connection refused",
            "temporarily unavailable",
            "provider overloaded",
            "rate limit exceeded",
            "unexpected eof from upstream",
        ).forEach { assertTrue(Retry.isRetryable(it), "expected retryable: $it") }
    }

    @Test
    fun `terminal failures are classified as non-retryable`() {
        listOf(
            "HTTP 400 Bad Request",
            "401 unauthorized",
            "403 forbidden",
            "404 not found",
            "invalid api key supplied",
            "context length exceeded",
            "content filter triggered",
        ).forEach { assertFalse(Retry.isRetryable(it), "expected terminal: $it") }
    }

    @Test
    fun `terminal markers beat embedded status codes`() {
        assertFalse(Retry.isRetryable("context length 512 exceeded"))
        assertFalse(Retry.isRetryable("invalid api key (500)"))
        assertFalse(Retry.isRetryable("thereof"))
        assertFalse(Retry.isRetryable("error 1500"))
        assertFalse(Retry.isRetryable("the code sha512 does not match"))
        assertTrue(Retry.isRetryable("HTTP 500 internal server error"))
    }

    @Test
    fun `backoff grows with attempts and stays bounded`() {
        val base = 500L
        val cap = 8_000L
        val ceiling = cap + cap / 4

        for (attempt in 1..10) {
            val expectedFloor = (base shl (attempt - 1)).coerceAtMost(cap)
            repeat(50) {
                val v = Retry.backoffMs(attempt, base, cap)
                assertTrue(v >= expectedFloor, "attempt=$attempt value=$v floor=$expectedFloor")
                assertTrue(v <= ceiling, "attempt=$attempt value=$v ceiling=$ceiling")
            }
        }

        repeat(100) {
            assertTrue(Retry.backoffMs(2, base, cap) > Retry.backoffMs(1, base, cap))
            assertTrue(Retry.backoffMs(4, base, cap) > Retry.backoffMs(2, base, cap))
            assertTrue(Retry.backoffMs(6, base, cap) > Retry.backoffMs(3, base, cap))
        }

        repeat(20) {
            assertTrue(Retry.backoffMs(100, base, cap) <= ceiling)
        }
    }

    @Test
    fun `withRetry retries exactly maxRetries times then rethrows`() = runTest {
        var calls = 0
        val retries = mutableListOf<Int>()

        val error = capture {
            Retry.withRetry(
                maxRetries = 3,
                onRetry = { attempt, _ -> retries += attempt },
            ) {
                calls++
                throw RuntimeException("503 unavailable")
            }
        }

        assertIs<RuntimeException>(error)
        assertTrue(error.message!!.contains("503"))
        assertEquals(4, calls)
        assertEquals(listOf(1, 2, 3), retries)
    }

    @Test
    fun `withRetry returns once a flaky block recovers`() = runTest {
        var calls = 0
        val retries = mutableListOf<Int>()

        val value = Retry.withRetry(
            maxRetries = 5,
            onRetry = { attempt, _ -> retries += attempt },
        ) {
            calls++
            if (calls < 3) throw RuntimeException("connection reset")
            "recovered"
        }

        assertEquals("recovered", value)
        assertEquals(3, calls)
        assertEquals(listOf(1, 2), retries)
    }

    @Test
    fun `withRetry does not retry a non-retryable error`() = runTest {
        var calls = 0
        var retries = 0

        val error = capture {
            Retry.withRetry(
                maxRetries = 5,
                onRetry = { _, _ -> retries++ },
            ) {
                calls++
                throw RuntimeException("400 bad request")
            }
        }

        assertIs<RuntimeException>(error)
        assertEquals(1, calls)
        assertEquals(0, retries)
    }

    @Test
    fun `withRetry propagates cancellation immediately without retrying`() = runTest {
        var calls = 0
        var retries = 0

        val error = capture {
            Retry.withRetry(
                maxRetries = 5,
                onRetry = { _, _ -> retries++ },
            ) {
                calls++
                throw CancellationException("stop")
            }
        }

        assertIs<CancellationException>(error)
        assertEquals(1, calls)
        assertEquals(0, retries)
    }

    @Test
    fun `withRetry honours a custom classifier`() = runTest {
        var calls = 0
        val value = Retry.withRetry(
            maxRetries = 3,
            classify = { it.message?.contains("flaky") == true },
        ) {
            calls++
            if (calls < 2) throw RuntimeException("flaky")
            "ok"
        }
        assertEquals("ok", value)
        assertEquals(2, calls)
    }
}
