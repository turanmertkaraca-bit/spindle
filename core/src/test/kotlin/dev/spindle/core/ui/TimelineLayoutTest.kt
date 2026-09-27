package dev.spindle.core.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The scroll contract, as executable assertions. These run on a plain JVM in CI,
 * so every claim about "what the timeline does" is checked on every push.
 */
class TimelineLayoutTest {

    private val m = TimelineLayout.Metrics(collapsedHeight = 30f, expandedHeight = 150f, gap = 10f)
    private val rows = (0 until 12).map { TimelineLayout.Row("r$it") }

    private fun openKeyAt(scrollTop: Float, viewport: Float = 500f, maxOpen: Int = 1) =
        TimelineLayout.compute(rows, scrollTop, viewport, m, maxOpen = maxOpen).openKey

    @Test
    fun `the row at the bottom edge of the viewport is the open one`() {
        // collapsed stack: each row is 30+10=40 tall. viewport 500 -> line at 460..500.
        // At scrollTop 0, the line falls in row 11's band only if content is tall;
        // with 12 collapsed rows content = 12*30 + 11*10 = 470 < 500, so the last row wins.
        assertEquals("r11", openKeyAt(0f))

        // Scroll so the line sits on row 0's band (0..30): scrollTop = -460 is invalid,
        // so use a taller viewport to bring the line to the top.
        assertEquals("r0", openKeyAt(0f, viewport = 30f)) // line = 30 -> exactly r0 bottom
    }

    @Test
    fun `scrolling down moves the open row down the list`() {
        // With a short viewport, increasing scrollTop advances which row is open.
        // Collapsed rows are 30+10=40 tall; the open line is scrollTop + viewport.
        val vp = 120f
        assertEquals("r3", openKeyAt(0f, vp))   // line 120 lands on r3's collapsed band [120,150)
        val seen = mutableListOf<String?>()
        var s = 0f
        while (s < 400f) { seen += openKeyAt(s, vp); s += 40f }
        val distinct = seen.filterNotNull().distinct()
        assertTrue(distinct.size >= 3, "open row should advance as we scroll: $distinct")
        // it must be monotonic in the row order
        val order = distinct.map { it.removePrefix("r").toInt() }
        assertEquals(order.sorted(), order, "open row must move forward monotonically")
    }

    @Test
    fun `exactly one row is open by default`() {
        val r = TimelineLayout.compute(rows, 200f, 500f, m)
        assertEquals(1, r.placements.count { it.open })
    }

    @Test
    fun `maxOpen keeps the neighbouring rows open`() {
        val r = TimelineLayout.compute(rows, 200f, 500f, m, maxOpen = 3)
        assertEquals(3, r.placements.count { it.open })
        // the open ones are contiguous and end on the winner
        val idx = r.placements.withIndex().filter { it.value.open }.map { it.index }
        assertEquals(idx.sorted(), idx)
        assertEquals(r.openKey, r.placements[idx.last()].key)
    }

    @Test
    fun `an open row is physically taller than a collapsed one`() {
        val r = TimelineLayout.compute(rows, 200f, 500f, m)
        val open = r.placements.first { it.open }
        val shut = r.placements.first { !it.open }
        assertEquals(150f, open.height)
        assertEquals(30f, shut.height)
    }

    @Test
    fun `rows never overlap and are laid out top to bottom`() {
        val r = TimelineLayout.compute(rows, 200f, 500f, m)
        var prevBottom = -1f
        for (p in r.placements) {
            assertTrue(p.top >= prevBottom, "row ${p.key} overlaps the previous one")
            prevBottom = p.bottom
        }
        assertEquals(0f, r.placements.first().top)
    }

    @Test
    fun `content height accounts for exactly the open rows`() {
        val r = TimelineLayout.compute(rows, 0f, 500f, m)
        val expected = r.placements.sumOf { it.height.toDouble() }.toFloat() + (rows.size - 1) * m.gap
        assertEquals(expected, r.contentHeight, 0.01f)
    }

    @Test
    fun `empty input is safe`() {
        val r = TimelineLayout.compute(emptyList(), 0f, 500f, m)
        assertEquals(0f, r.contentHeight)
        assertNull(r.openKey)
        assertTrue(r.placements.isEmpty())
    }

    @Test
    fun `maxScroll never goes negative`() {
        assertEquals(0f, TimelineLayout.maxScroll(100f, 500f))
        assertEquals(200f, TimelineLayout.maxScroll(700f, 500f))
    }

    @Test
    fun `reveal scrolls a row that is below the fold into view`() {
        // Put the open row far down, scroll at top: reveal must bring it into view.
        val r = TimelineLayout.compute(rows, 0f, 500f, m)
        val target = TimelineLayout.scrollToReveal(r, "r11", scrollTop = 0f, viewportHeight = 500f, margin = 24f)
        assertNotNull(target, "a row off the bottom should be revealed")
        assertTrue(target > 0f)
        assertTrue(target <= TimelineLayout.maxScroll(r.contentHeight, 500f))
    }

    @Test
    fun `reveal does nothing when the open row fits comfortably`() {
        // scroll a little so the open row is fully inside the viewport
        val r = TimelineLayout.compute(rows, 200f, 500f, m)
        val open = r.openKey!!
        val p = r.placement(open)!!
        // sanity: the row really is fully visible
        assertTrue(p.top >= 200f - 24f && p.bottom <= 200f + 500f - 24f)
        assertNull(TimelineLayout.scrollToReveal(r, open, scrollTop = 200f, viewportHeight = 500f, margin = 24f))
    }

    @Test
    fun `reveal pulls back a row whose bottom is clipped by the fold`() {
        // at scrollTop 0 the newly-opened last row extends past the viewport
        val r = TimelineLayout.compute(rows, 0f, 500f, m)
        val open = r.openKey!!
        val p = r.placement(open)!!
        assertTrue(p.bottom > 500f, "precondition: row is clipped")
        val t = TimelineLayout.scrollToReveal(r, open, scrollTop = 0f, viewportHeight = 500f, margin = 24f)
        assertNotNull(t)
        assertTrue(t in 0f..TimelineLayout.maxScroll(r.contentHeight, 500f))
    }

    @Test
    fun `reveal result is always within valid scroll bounds`() {
        val r = TimelineLayout.compute(rows, 300f, 500f, m)
        val max = TimelineLayout.maxScroll(r.contentHeight, 500f)
        for (key in rows.map { it.key }) {
            val t = TimelineLayout.scrollToReveal(r, key, scrollTop = 300f, viewportHeight = 500f, margin = 24f)
            if (t != null) assertTrue(t in 0f..max, "reveal for $key out of bounds: $t (max $max)")
        }
    }

    @Test
    fun `scrolling is stable - the same input always gives the same open row`() {
        // No timers, no randomness: determinism is the whole point.
        val a = openKeyAt(137f)
        val b = openKeyAt(137f)
        assertEquals(a, b)
    }
}
