package dev.spindle.core.ui

/**
 * The collapsed-timeline layout model, expressed as a PURE function of scroll
 * position. No timers, no springs, no platform types — so it can be unit-tested
 * on a plain JVM and behaves identically in the Compose UI.
 *
 * The rule (what "fluid and tactile" means here):
 *   The row occupying the BOTTOM EDGE of the viewport is the open one.
 *   Everything else is collapsed to a single summary line. As you scroll, the
 *   open row follows your thumb — there is never a deferred "snap".
 *
 * The newest row is special only in that, when you are already at the bottom of
 * the content, it opens — which falls out of the same rule for free.
 */
object TimelineLayout {

    /** Layout metrics, in px. Injected so tests are deterministic. */
    data class Metrics(
        val collapsedHeight: Float,
        val expandedHeight: Float,
        val gap: Float,
    ) {
        fun height(open: Boolean) = if (open) expandedHeight else collapsedHeight
    }

    /** One row's measured/intrinsic size for this frame. */
    data class Row(val key: String)

    data class Placement(
        val key: String,
        /** top offset of this row in content space */
        val top: Float,
        val height: Float,
        val open: Boolean,
    ) {
        val bottom get() = top + height
    }

    data class Result(
        val placements: List<Placement>,
        val contentHeight: Float,
        val openKey: String?,
    ) {
        fun placement(key: String) = placements.firstOrNull { it.key == key }
    }

    /**
     * Compute the layout for [rows] given the current [scrollTop] and [viewportHeight].
     *
     * @param bottomInset how far above the true bottom edge the "open line" sits.
     *        Keeps the open row from being clipped by the composer.
     */
    fun compute(
        rows: List<Row>,
        scrollTop: Float,
        viewportHeight: Float,
        metrics: Metrics,
        bottomInset: Float = 0f,
        maxOpen: Int = 1,
    ): Result {
        if (rows.isEmpty()) return Result(emptyList(), 0f, null)

        // 1) Which row sits at the bottom edge? (pure function of scrollTop)
        // We answer it against COLLAPSED heights first — a stable, jitter-free
        // choice — then expand the winner. Expanding changes heights, so we
        // recompute positions but keep the same winner for this frame.
        val line = scrollTop + viewportHeight - bottomInset

        val collapsedTops = FloatArray(rows.size)
        var y = 0f
        for (i in rows.indices) {
            collapsedTops[i] = y
            y += metrics.collapsedHeight + metrics.gap
        }

        // the row whose collapsed band contains the line, or the last row above it
        var winner = rows.lastIndex
        for (i in rows.indices) {
            val top = collapsedTops[i]
            val bottom = top + metrics.collapsedHeight
            if (line in top..bottom) { winner = i; break }
            if (line < top) { winner = (i - 1).coerceAtLeast(0); break }
        }

        // 2) Expand the winner plus up to (maxOpen-1) nearest rows above it.
        val open = HashSet<Int>()
        open += winner
        var up = winner - 1
        while (open.size < maxOpen && up >= 0) {
            open += up
            up--
        }

        // 3) Layout with those heights.
        val placements = ArrayList<Placement>(rows.size)
        var top = 0f
        for (i in rows.indices) {
            val isOpen = i in open
            val h = metrics.height(isOpen)
            placements += Placement(rows[i].key, top, h, isOpen)
            top += h + metrics.gap
        }
        val contentHeight = (top - metrics.gap).coerceAtLeast(0f)
        return Result(placements, contentHeight, rows[winner].key)
    }

    /** Max scroll offset that keeps the content pinned to the bottom. */
    fun maxScroll(contentHeight: Float, viewportHeight: Float): Float =
        (contentHeight - viewportHeight).coerceAtLeast(0f)

    /**
     * Where the scroll SHOULD be so the open row is comfortably visible — used
     * once, when a new row is appended, to bring it into view without teleporting.
     * Returns null when no adjustment is needed.
     */
    fun scrollToReveal(
        result: Result,
        openKey: String,
        scrollTop: Float,
        viewportHeight: Float,
        margin: Float,
    ): Float? {
        val p = result.placement(openKey) ?: return null
        val max = maxScroll(result.contentHeight, viewportHeight)
        val target = when {
            p.top - scrollTop < margin -> p.top - margin
            p.bottom > scrollTop + viewportHeight - margin -> p.bottom - viewportHeight + margin
            else -> return null
        }
        val clamped = target.coerceIn(0f, max)
        return if (kotlin.math.abs(clamped - scrollTop) < 0.5f) null else clamped
    }
}
