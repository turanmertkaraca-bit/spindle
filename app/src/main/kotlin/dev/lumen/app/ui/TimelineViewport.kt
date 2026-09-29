package dev.lumen.app.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import kotlin.math.max
import kotlin.math.min

/**
 * A stable snapshot of what the timeline is showing right now. Reading it is
 * cheap and, unlike touching `layoutInfo` from several effects, it keeps the
 * rules (anchor, reveal, follow) in one place — the source of the old jitter was
 * three effects each recomputing geometry on every frame of a growing node.
 */
internal data class TimelineViewport(
    val firstVisibleIndex: Int,
    val lastVisibleIndex: Int,
    val viewportStart: Float,
    val viewportEnd: Float,
    val focusedTop: Float?,
    val focusedCenter: Float?,
    val focusedHeight: Float?,
    val focusedIndex: Int,
) {
    val center: Float get() = (viewportStart + viewportEnd) / 2f
    val hasFocus: Boolean get() = focusedTop != null

    companion object {
        fun read(state: LazyListState, focusedIndex: Int): TimelineViewport {
            val info: LazyListLayoutInfo = state.layoutInfo
            val focused: LazyListItemInfo? =
                info.visibleItemsInfo.firstOrNull { it.index == focusedIndex }
            return TimelineViewport(
                firstVisibleIndex = info.visibleItemsInfo.firstOrNull()?.index ?: 0,
                lastVisibleIndex = info.visibleItemsInfo.lastOrNull()?.index ?: -1,
                viewportStart = info.viewportStartOffset.toFloat(),
                viewportEnd = info.viewportEndOffset.toFloat(),
                focusedTop = focused?.offset?.toFloat(),
                focusedCenter = focused?.let { (it.offset + it.size / 2f).toFloat() },
                focusedHeight = focused?.size?.toFloat(),
                focusedIndex = focusedIndex,
            )
        }
    }

    /** How far to scroll so the focused node's centre lands on the focus line. */
    fun centerDelta(): Float? = focusedCenter?.let { it - center }

    /**
     * How far to scroll so the focused node's TOP sits on the focus line, so a
     * growing answer always flows downward and never pushes the node off-centre.
     */
    fun topDelta(): Float? = focusedTop?.let { it - center }
}

/** Clamp a requested target index into the list. */
internal fun clampIndex(index: Int, count: Int): Int = max(0, min(index, max(0, count - 1)))
