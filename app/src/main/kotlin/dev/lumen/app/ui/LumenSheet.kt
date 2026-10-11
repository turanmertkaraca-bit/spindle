package dev.lumen.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The one bottom sheet: a drag handle, rounded top, a dimmed scrim and a
 * slide-up/fade transition. Replaces the old instant, handle-less overlay so
 * every popup (quick settings, the model picker) reads as a native sheet.
 */
@Composable
internal fun LumenBottomSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    colors: LumenColors,
    modifier: Modifier = Modifier,
    maxHeight: Dp = 520.dp,
    tag: String = "lumen-sheet",
    content: @Composable ColumnScope.() -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(LumenMotion.fade) + slideInVertically(initialOffsetY = { it }),
        exit = fadeOut(LumenMotion.fade) + slideOutVertically(targetOffsetY = { it }),
    ) {
        Box(
            modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = if (colors.dark) 0.62f else 0.38f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                )
                .testTag(tag),
        ) {
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .shadow(LumenElevation.floating, LumenShapes.sheet)
                    .clip(LumenShapes.sheet)
                    .background(colors.surface)
                    .border(1.dp, colors.outline, LumenShapes.sheet)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {}
                    .heightIn(max = maxHeight),
            ) {
                Box(
                    Modifier
                        .padding(top = 8.dp)
                        .size(width = 36.dp, height = 4.dp)
                        .clip(LumenShapes.pill)
                        .background(colors.outline)
                        .align(Alignment.CenterHorizontally),
                )
                Spacer(Modifier.height(LumenSpacing.md))
                Column(
                    Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(
                            start = LumenSpacing.page,
                            end = LumenSpacing.page,
                            bottom = LumenSpacing.page,
                        ),
                    content = content,
                )
            }
        }
    }
}
