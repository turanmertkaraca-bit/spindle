package dev.lumen.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Mono = FontFamily.Monospace

/**
 * A calm, on-brand placeholder for a screen's empty, loading or error state so
 * no surface ever renders as a blank void. A single spectral droplet with one
 * faint line (plus an optional detail line); [tint] warms it for an error.
 */
@Composable
fun StateHint(
    colors: LumenColors,
    text: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    tint: Color = colors.water,
    tag: String? = null,
) {
    Column(
        modifier.padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(9.dp)
                .clip(WaterShapes.droplet(tail = 0.55f))
                .background(tint.copy(alpha = 0.55f)),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text,
            color = colors.dim, fontFamily = Mono, fontSize = 12.5.sp,
            textAlign = TextAlign.Center,
            modifier = if (tag != null) Modifier.testTag(tag) else Modifier,
        )
        if (detail != null) {
            Spacer(Modifier.height(5.dp))
            Text(
                detail,
                color = colors.faint, fontFamily = Mono, fontSize = 11.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}
