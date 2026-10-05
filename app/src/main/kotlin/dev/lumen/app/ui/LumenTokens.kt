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

    /** A message bubble with one corner tucked toward its author. */
    val bubbleYou = RoundedCornerShape(18.dp, 18.dp, 6.dp, 18.dp)
    val bubbleAgent = RoundedCornerShape(18.dp, 18.dp, 18.dp, 6.dp)

    /** A bottom sheet anchored to the screen edge. */
    val sheet = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)
}

/**
 * The single source of truth for elevation. True-black surfaces read best flat,
 * so cards lift only a hair while genuinely floating affordances sit above them.
 */
object LumenElevation {
    val none: Dp = 0.dp

    /** Pinned cards lift just off the transcript without reading as a slab. */
    val card: Dp = 1.dp

    /** Floating affordances (cue, suggestions, peek sheet) sit above the page. */
    val floating: Dp = 6.dp
}
