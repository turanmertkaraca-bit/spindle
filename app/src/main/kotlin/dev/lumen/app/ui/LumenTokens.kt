package dev.lumen.app.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The shared "failed/error" red, independent of the prism spectrum. Mirrors the
 * chat screen's danger colour closely enough to read as one signal.
 */
val LumenAlert: Color = Color(0xFFE5484D)

/**
 * The single source of truth for corner radii. Cards, chips, fields and bubbles
 * all draw from here so the surface language stays consistent as screens grow.
 * The AMOLED/prism look stays exactly as before — only the literals are named.
 */
object LumenShapes {
    /** Pinned and inset cards: tool, changes, todo, permission, question, files. */
    val card = RoundedCornerShape(12.dp)

    /** A nested panel inside a card (thinking, notices, diffs, image chips). */
    val panel = RoundedCornerShape(10.dp)

    /** A row or inset block that sits inside a card. */
    val inset = RoundedCornerShape(8.dp)

    /** A small chip or inline action. */
    val small = RoundedCornerShape(6.dp)

    /** A tiny glyph tile. */
    val glyph = RoundedCornerShape(5.dp)

    /** A top-bar control pill. */
    val control = RoundedCornerShape(11.dp)

    /** Fully rounded pills and capsule buttons. */
    val pill = RoundedCornerShape(50)

    /** The composer's capsule input field. */
    val field = RoundedCornerShape(50)

    /** The single rounded composer card holding the input and its control row. */
    val composer = RoundedCornerShape(26.dp)

    /** A message bubble with one corner tucked toward its author. */
    val bubbleYou = RoundedCornerShape(18.dp, 18.dp, 6.dp, 18.dp)
    val bubbleAgent = RoundedCornerShape(18.dp, 18.dp, 18.dp, 6.dp)

    /** A bottom sheet anchored to the screen edge. */
    val sheet = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)
}

/**
 * The single source of truth for elevation. True-black surfaces read best flat,
 * so pinned cards carry no shadow at all — their hairline border is enough — and
 * only genuinely floating affordances lift off the page.
 */
object LumenElevation {
    val none: Dp = 0.dp

    /** Pinned cards are flat; the rule border separates them, not a shadow. */
    val card: Dp = 0.dp

    /** Floating affordances (cue, suggestions, peek sheet) sit above the page. */
    val floating: Dp = 4.dp
}

/**
 * The shared spacing scale. Named so the transcript can breathe with one voice
 * instead of scattering dp literals; additive only, so existing call sites keep
 * their geometry.
 */
object LumenSpacing {
    val xxs: Dp = 2.dp
    val xs: Dp = 4.dp
    val sm: Dp = 6.dp
    val md: Dp = 10.dp
    val lg: Dp = 14.dp
    val xl: Dp = 20.dp

    /** The horizontal gutter between the transcript and the screen edges. */
    val gutter: Dp = 12.dp
}
