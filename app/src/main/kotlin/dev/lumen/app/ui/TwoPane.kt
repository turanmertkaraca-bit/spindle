package dev.lumen.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The narrowest viewport that can host a list and a detail side by side. 600dp
 * is the conventional tablet/foldable breakpoint; it is deliberately a plain
 * constant so this file needs no `material3-window-size-class` dependency.
 */
val TwoPaneMinWidth: Dp = 600.dp

/** The fixed width of the list rail once two-pane mode activates. */
val TwoPaneListWidth: Dp = 320.dp

/**
 * A function-first adaptive list/detail split.
 *
 * * Wide (`maxWidth >= 600dp`) and a non-null [detail]: a [Row] with the list at
 *   a fixed [listWidth] and the detail taking the remaining space.
 * * Otherwise: the list alone, full width.
 *
 * A narrow screen never receives the detail by default — the caller is expected
 * to keep its own drill-in. Pass [focusDetailOnNarrow] to stack the detail over
 * the still-composed list instead (the list keeps its state underneath); this is
 * what the files cockpit uses so its full-screen editor survives on phones.
 *
 * Tags: the wide [Row] is `two-pane`, the detail container is `two-pane-detail`,
 * and the single-pane list container is `two-pane-list`. Callers are free to tag
 * their own children.
 */
@Composable
fun AdaptiveTwoPane(
    list: @Composable () -> Unit,
    detail: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
    listWidth: Dp = TwoPaneListWidth,
    focusDetailOnNarrow: Boolean = false,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val openDetail = detail
        if (openDetail != null && maxWidth >= TwoPaneMinWidth) {
            Row(Modifier.fillMaxSize().testTag("two-pane")) {
                Box(Modifier.width(listWidth).fillMaxHeight()) { list() }
                Box(Modifier.weight(1f).fillMaxHeight().testTag("two-pane-detail")) { openDetail() }
            }
        } else if (openDetail != null && focusDetailOnNarrow) {
            Box(Modifier.fillMaxSize()) {
                list()
                // A transparent scrim swallows touches so the still-composed list
                // underneath never receives them while the stacked detail is on top.
                Box(
                    Modifier
                        .matchParentSize()
                        .testTag("two-pane-scrim")
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {},
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .testTag("two-pane-detail"),
                ) { openDetail() }
            }
        } else {
            Box(Modifier.fillMaxSize().testTag("two-pane-list")) { list() }
        }
    }
}
