package dev.lumen.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The shared control vocabulary. One switch, one segmented "pick one of N" and
 * one slider, so every surface in the app speaks the same visual grammar
 * instead of hand-rolling chips with drifting geometry.
 */

/** One option in a [LumenSegmented]: [value] is stored, [label] shown, [tag] tested. */
internal data class LumenSegment(val value: String, val label: String, val tag: String)

/** The themed switch used everywhere, so the thumb/track never drift per screen. */
@Composable
internal fun LumenSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    colors: LumenColors,
    modifier: Modifier = Modifier,
    tag: String? = null,
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor = colors.bg,
            checkedTrackColor = colors.water,
            uncheckedThumbColor = colors.dim,
            uncheckedTrackColor = colors.surface,
            uncheckedBorderColor = colors.outline,
        ),
        modifier = modifier.then(if (tag != null) Modifier.testTag(tag) else Modifier),
    )
}

/**
 * A joined segmented control for any one-of-N choice (theme, agent, budget,
 * palette). The active segment fills with the palette's `water` so "which one is
 * on" is never ambiguous — unlike the loose, uniform chips it replaces.
 */
@Composable
internal fun LumenSegmented(
    segments: List<LumenSegment>,
    selected: String,
    onSelect: (String) -> Unit,
    colors: LumenColors,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(LumenShapes.inset)
            .background(colors.surface)
            .border(1.dp, colors.outline, LumenShapes.inset),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        segments.forEachIndexed { index, seg ->
            if (index > 0) {
                Box(Modifier.width(1.dp).height(22.dp).background(colors.outline))
            }
            val active = seg.value == selected
            Text(
                seg.label,
                color = if (active) colors.bg else colors.dim,
                fontFamily = LumenFonts.label,
                fontSize = LumenType.body,
                fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .clickable { onSelect(seg.value) }
                    .background(if (active) colors.water else Color.Transparent)
                    .padding(vertical = 10.dp, horizontal = 4.dp)
                    .testTag(seg.tag),
            )
        }
    }
}

/** A slider themed to the active palette (Material's default purple is banned). */
@Composable
internal fun LumenSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    colors: LumenColors,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    tag: String? = null,
) {
    Slider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.then(if (tag != null) Modifier.testTag(tag) else Modifier),
        valueRange = valueRange,
        steps = steps,
        colors = SliderDefaults.colors(
            thumbColor = colors.water,
            activeTrackColor = colors.water,
            inactiveTrackColor = colors.outline,
            activeTickColor = Color.Transparent,
            inactiveTickColor = Color.Transparent,
        ),
    )
}

/**
 * The one loading primitive: a centered spectral spinner over a proportional
 * label. Every catalogue surface uses this so a refresh reads as intentional
 * work instead of a dead, empty screen the user taps at.
 */
@Composable
internal fun LumenLoading(
    colors: LumenColors,
    text: String,
    modifier: Modifier = Modifier,
    tag: String? = null,
) {
    Column(
        modifier.then(if (tag != null) Modifier.testTag(tag) else Modifier),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(30.dp),
            color = colors.water,
            strokeWidth = 2.5.dp,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text,
            color = colors.dim,
            fontFamily = LumenFonts.label,
            fontSize = LumenType.body,
        )
    }
}
