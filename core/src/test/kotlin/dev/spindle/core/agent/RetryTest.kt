package dev.spindle.core.agent

import dev.spindle.core.provider.ProviderErrors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

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
    fun `connectivity failures are classified as network`() {
        listOf(
            "Unable to resolve host \"api.openai.com\"",
            "java.net.UnknownHostException: api.anthropic.com",
            "No address associated with hostname",
            "Network is unreachable",
            "failed to connect to /10.0.0.1",
            "connection reset by peer",
            "Software caused connection abort",
            "unexpected end of stream on okhttp3",
            "SSL handshake aborted",
            "broken pipe",
        ).forEach { assertTrue(Retry.isNetwork(it), "expected network: $it") }

        listOf(
            "invalid api key",
            "context length exceeded",
            "content filter triggered",
            "HTTP 400 Bad Request",
        ).forEach { assertFalse(Retry.isNetwork(it), "expected not-network: $it") }
    }

    @Test
    fun `resilient retry waits out a network outage and then recovers`() = runTest {
        var calls = 0
        val retries = mutableListOf<Int>()
        val value = Retry.withResilientRetry(
            fatalRetries = 2,
            onRetry = { attempt, _, _ -> retries += attempt },
        ) {
            calls++
            // Far more failures than fatalRetries: a network drop must not end it.
            if (calls < 12) throw RuntimeException("Network is unreachable")
            "reconnected"
        }
        assertEquals("reconnected", value)
        assertEquals(12, calls)
        assertEquals((1..11).toList(), retries)
    }

    @Test
    fun `resilient retry gives up on repeated non-network transient errors`() = runTest {
        var calls = 0
        val error = capture {
            Retry.withResilientRetry(
                fatalRetries = 2,
                onRetry = { _, _, _ -> },
            ) {
                calls++
                throw RuntimeException("503 unavailable")
            }
        }
        assertIs<RuntimeException>(error)
        assertEquals(3, calls)
    }

    @Test
    fun `resilient retry does not retry a fatal rejection`() = runTest {
        var calls = 0
        val error = capture {
            Retry.withResilientRetry(fatalRetries = 5) {
                calls++
                throw RuntimeException("401 unauthorized")
            }
        }
        assertIs<RuntimeException>(error)
        assertEquals(1, calls)
    }

    @Test
    fun `resilient retry propagates cancellation immediately`() = runTest {
        var calls = 0
        val error = capture {
            Retry.withResilientRetry(fatalRetries = 5) {
                calls++
                throw CancellationException("stop")
            }
        }
        assertIs<CancellationException>(error)
        assertEquals(1, calls)
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

    @Test
    fun `backoff never collapses to zero or negative for huge attempts`() {
        val base = 500L
        val cap = 8_000L
        val ceiling = cap + cap / 4

        for (attempt in listOf(63, 64, 65, 100, 1_000, Int.MAX_VALUE)) {
            repeat(20) {
                val v = Retry.backoffMs(attempt, base, cap)
                assertTrue(v > 0, "attempt=$attempt value=$v must stay positive")
                assertTrue(v >= base, "attempt=$attempt value=$v below base")
                assertTrue(v <= ceiling, "attempt=$attempt value=$v above ceiling")
            }
        }
        // Degenerate attempts still yield a usable, positive delay.
        assertTrue(Retry.backoffMs(0) > 0)
        assertTrue(Retry.backoffMs(-5) > 0)
    }

    @Test
    fun `structured retry classification uses status and declared override`() {
        assertTrue(Retry.isRetryable(429, null, "whatever"))
        assertTrue(Retry.isRetryable(500, null, "whatever"))
        assertTrue(Retry.isRetryable(503, null, "whatever"))
        assertFalse(Retry.isRetryable(400, null, "whatever"))
        assertFalse(Retry.isRetryable(401, null, "whatever"))
        assertFalse(Retry.isRetryable(403, null, "whatever"))
        assertFalse(Retry.isRetryable(404, null, "whatever"))
        // An unknown status falls back to the legacy message markers.
        assertTrue(Retry.isRetryable(418, null, "429 rate limit"))
        assertFalse(Retry.isRetryable(418, null, "context length exceeded"))
        // A declared verdict wins over both status and message.
        assertTrue(Retry.isRetryable(401, true, "401 unauthorized"))
        assertFalse(Retry.isRetryable(429, false, "429 too many requests"))
    }

    @Test
    fun `context overflow detection matches markers and status 413`() {
        assertTrue(ProviderErrors.isContextOverflow("This model's maximum context length is 8192 tokens"))
        assertTrue(ProviderErrors.isContextOverflow("context_length_exceeded"))
        assertTrue(ProviderErrors.isContextOverflow("Prompt is too long"))
        assertTrue(ProviderErrors.isContextOverflow("too many tokens"))
        assertTrue(ProviderErrors.isContextOverflow("anything at all", statusCode = 413))
        assertFalse(ProviderErrors.isContextOverflow("rate limit exceeded"))
        assertFalse(ProviderErrors.isContextOverflow("boom"))
        assertFalse(ProviderErrors.isContextOverflow("server error", statusCode = 500))
    }

    @Test
    fun `resilient retry honours a provider retry-after hint`() = runTest {
        var calls = 0
        val waits = mutableListOf<Long>()
        val value = Retry.withResilientRetry(
            fatalRetries = 3,
            retryAfterMs = { 2_500L },
            onRetry = { _, _, wait -> waits += wait },
        ) {
            calls++
            if (calls < 2) throw RuntimeException("429 too many requests")
            "ok"
        }
        assertEquals("ok", value)
        assertEquals(listOf(2_500L), waits)
    }

    @Test
    fun `retry-after hint is bounded so a garbled body cannot stall a run`() = runTest {
        val waits = mutableListOf<Long>()
        val error = capture {
            Retry.withResilientRetry(
                fatalRetries = 1,
                retryAfterMs = { 120_000L },
                onRetry = { _, _, wait -> waits += wait },
            ) {
                throw RuntimeException("503 unavailable")
            }
        }
        assertIs<RuntimeException>(error)
        // Raised from 1 to 2 minutes: a capacity hint is usually ~30s, and waiting
        // out a busy cell is better than re-hitting it early. Still bounded.
        assertEquals(listOf(Retry.MAX_WAIT_MS), waits)
    }

    @Test
    fun `resilient retry uses the structured retryable override`() = runTest {
        var calls = 0
        val error = capture {
            Retry.withResilientRetry(
                fatalRetries = 5,
                retryable = { false },
                classify = { true },
            ) {
                calls++
                throw RuntimeException("looks retryable by message")
            }
        }
        assertIs<RuntimeException>(error)
        assertEquals(1, calls, "a declared non-retryable verdict must not be retried")
    }

    @Test
    fun `a declared retryable verdict still respects the attempt budget`() = runTest {
        // Regression: a declared `retryable = true` skipped the budget check, so a
        // 429 that the adapter marked retryable retried forever and maxRetries was
        // ignored for exactly the case it exists for.
        var calls = 0
        val error = capture {
            Retry.withResilientRetry(
                fatalRetries = 3,
                retryable = { true },
                classify = { true },
                retryAfterMs = { 1L },
            ) {
                calls++
                throw RuntimeException("429 too many requests")
            }
        }
        assertIs<RuntimeException>(error)
        // attempt increments before the check `attempt > fatalRetries`, so
        // fatalRetries = 3 allows attempts 1..3 to retry and attempt 4 to give up:
        // 4 block executions in total.
        assertEquals(4, calls, "3 retries then give up on the 4th attempt")
    }

    @Test
    fun `a capacity 429 hint is honoured instead of our own backoff`() = runTest {
        // OpenCode Go says "retry shortly (after 30 s)" in the BODY. Reading only
        // the Retry-After header gave us a 500ms backoff into a full cell.
        val text = "OpenAI HTTP 429: {\"error\":{\"type\":\"server_error\"," +
            "\"message\":\"Upstream request failed: [prefill_cap_refused] the " +
            "cell is at capacity; retry shortly (after 30 s)\"}}"
        val parsed = ProviderErrors.retryHintFrom(text)
        assertEquals(30_000L, parsed)
    }

    @Test
    fun `retry hint parsing handles seconds, minutes, millis and garbage`() {
        val r = ProviderErrors
        assertEquals(45_000L, r.retryHintFrom("retry in 45 seconds"))
        assertEquals(90_000L, r.retryHintFrom("please retry after 1.5 minutes"))
        assertEquals(2_000L, r.retryHintFrom("after 2000 ms"))
        assertEquals(null, r.retryHintFrom("no hint here"))
        assertEquals(null, r.retryHintFrom(null))
        // Bounded so a garbled body cannot stall a run.
        assertEquals(300_000L, r.retryHintFrom("after 999 minutes"))
    }

    @Test
    fun `a capacity rejection gets a much larger budget than an ordinary error`() = runTest {
        // Real log: five 30s waits then the run died after ~2.5 minutes, because a
        // capacity 429 consumed the normal 5-attempt budget. A long goal run must
        // wait a busy cell out instead.
        var calls = 0
        val error = capture {
            Retry.withResilientRetry(
                fatalRetries = 5,
                retryable = { true },
                classify = { true },
                retryAfterMs = { 1L },
            ) {
                calls++
                if (calls <= 10) throw RuntimeException("429 [prefill_cap_refused] the cell is at capacity")
                throw RuntimeException("boom")
            }
        }
        assertIs<RuntimeException>(error)
        assertTrue(calls > 10, "a capacity rejection must outlive the normal budget, got $calls")
    }

    @Test
    fun `capacity is detected from the provider wording`() {
        assertTrue(Retry.isCapacity("Upstream request failed: [prefill_cap_refused] the cell is at capacity"))
        assertTrue(Retry.isCapacity("provider overloaded"))
        assertFalse(Retry.isCapacity("invalid api key"))
        assertFalse(Retry.isCapacity("context length exceeded"))
    }

    @Test
    fun `an ordinary transient error still stops at the normal budget`() = runTest {
        var calls = 0
        capture {
            Retry.withResilientRetry(
                fatalRetries = 2,
                retryable = { true },
                classify = { true },
                retryAfterMs = { 1L },
            ) {
                calls++
                throw RuntimeException("500 server exploded")
            }
        }
        assertEquals(3, calls, "fatalRetries=2 means 3 executions then give up")
    }
}

/**
 * Wave-1 bug hunt (R1): retrying had no relationship to the run's wall clock.
 *
 * The attempt budget bounds *how many* retries happen, not how long they take. A
 * capacity rejection gets 30 attempts and each may legitimately wait
 * `MAX_WAIT_MS` (two minutes), so a 429 storm beginning near the end of a
 * 30-minute run could sleep for another ~60 minutes after the budget the user was
 * promised had expired — with the spinner still spinning.
 */
class RetryDeadlineTest {

    @Test
    fun `a wait is shrunk to the remaining budget`() {
        assertEquals(
            45_000L,
            Retry.clampToDeadline(proposed = 120_000L, remainingMs = 60_000L),
            "the wait must not outlast the run",
        )
    }

    @Test
    fun `a near-spent budget refuses to wait at all`() {
        assertEquals(0L, Retry.clampToDeadline(proposed = 120_000L, remainingMs = 5_000L))
        assertEquals(0L, Retry.clampToDeadline(proposed = 120_000L, remainingMs = 0L))
        assertEquals(0L, Retry.clampToDeadline(proposed = 120_000L, remainingMs = -1L))
    }

    @Test
    fun `a generous budget leaves the wait alone`() {
        assertEquals(500L, Retry.clampToDeadline(proposed = 500L, remainingMs = 600_000L))
    }

    @Test
    fun `no deadline means no clamping`() {
        assertEquals(120_000L, Retry.clampToDeadline(proposed = 120_000L, remainingMs = null))
    }

    @Test
    fun `a spent deadline stops the retry instead of sleeping past the run`() = runTest {
        var calls = 0
        val error = assertFailsWith<IllegalStateException> {
            Retry.withResilientRetry(
                fatalRetries = 5,
                classify = { true },
                retryable = { true },
                // The run has 5s left; a wait must not be taken.
                deadlineInMs = { 5_000L },
                onRetry = { _, _, _ -> },
            ) {
                calls++
                throw IllegalStateException("429 rate limited")
            }
        }
        assertEquals(1, calls, "a spent run must not be retried again")
        assertTrue(error.message!!.contains("429"))
    }

    @Test
    fun `an unlimited budget still retries the full attempt count`() = runTest {
        var calls = 0
        assertFailsWith<IllegalStateException> {
            Retry.withResilientRetry(
                fatalRetries = 2,
                classify = { true },
                retryable = { true },
                deadlineInMs = { null },
            ) {
                calls++
                throw IllegalStateException("429 rate limited")
            }
        }
        // attempt 1..2 retried, attempt 3 exceeds fatalRetries
        assertEquals(3, calls)
    }
}
