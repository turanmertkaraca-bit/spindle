package dev.spindle.core.provider

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Wave-1 bug hunt (R2): a `Retry-After` HTTP-date was silently discarded.
 *
 * The parser used `toDoubleOrNull() ?: return null`, so any date-form value —
 * legal per RFC 7231 §7.1.1.1(2) and common from CDNs and gateways in front of
 * 429/503 — became `null`. The agent loop reads `null` as "no hint" and
 * substitutes its own ~500 ms backoff, so the client hammered an endpoint that
 * had just asked it to wait, exhausted the attempt budget in seconds, and
 * surfaced a hard failure: exactly the failure the capacity-hint work exists to
 * prevent.
 */
class RetryAfterTest {

    /** Fixed instant used as "now" so these tests never depend on the clock. */
    private fun nowAt(iso: String): () -> Long {
        val instant = java.time.Instant.parse(iso)
        return { instant.toEpochMilli() }
    }

    @Test
    fun `delay-seconds is read as seconds`() {
        assertEquals(30_000L, ProviderErrors.parseRetryAfter("30"))
        assertEquals(0L, ProviderErrors.parseRetryAfter("0"))
        assertEquals(90_000L, ProviderErrors.parseRetryAfter(" 90 "))
    }

    @Test
    fun `retry-after-ms is already milliseconds`() {
        assertEquals(1_500L, ProviderErrors.parseRetryAfter("1500", isMillis = true))
    }

    @Test
    fun `an RFC 1123 date becomes the delta from now`() {
        val now = nowAt("2026-01-01T00:00:00Z")
        // 30 seconds later, in the exact form RFC 7231 names.
        assertEquals(
            30_000L,
            ProviderErrors.parseRetryAfter("Thu, 1 Jan 2026 00:00:30 GMT", now = now),
        )
    }

    @Test
    fun `a date in the past yields null so normal backoff applies`() {
        val now = nowAt("2026-01-01T00:00:00Z")
        assertNull(
            ProviderErrors.parseRetryAfter("Thu, 1 Jan 2026 00:00:00 GMT", now = now),
            "a stale or skewed date must not become a zero-length wait",
        )
    }

    @Test
    fun `an absurd date is capped`() {
        val now = nowAt("2026-01-01T00:00:00Z")
        val parsed = ProviderErrors.parseRetryAfter("Thu, 1 Jan 2037 00:00:00 GMT", now = now)
        assertEquals(ProviderErrors.MAX_RETRY_AFTER_MS, parsed)
    }

    @Test
    fun `an absurd delay-seconds value is capped too`() {
        assertEquals(ProviderErrors.MAX_RETRY_AFTER_MS, ProviderErrors.parseRetryAfter("86400"))
    }

    @Test
    fun `garbage and blanks are null, never zero`() {
        assertNull(ProviderErrors.parseRetryAfter(null))
        assertNull(ProviderErrors.parseRetryAfter(""))
        assertNull(ProviderErrors.parseRetryAfter("   "))
        assertNull(ProviderErrors.parseRetryAfter("soon"))
        assertNull(ProviderErrors.parseRetryAfter("-5"))
        assertNull(ProviderErrors.parseRetryAfter("NaN"))
    }

    @Test
    fun `the obsolete asctime form is accepted`() {
        val now = nowAt("2026-01-01T00:00:00Z")
        // No zone in this format; RFC 7231 fixes it to GMT.
        assertEquals(
            60_000L,
            ProviderErrors.parseRetryAfter("Thu Jan  1 00:01:00 2026", now = now),
        )
    }
}
