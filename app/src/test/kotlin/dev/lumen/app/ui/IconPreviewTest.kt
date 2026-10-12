package dev.lumen.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.spindle.core.ui.DropletShape
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

/**
 * TEMPORARY icon preview sheet. Five launcher-icon concepts in every palette,
 * each in three contrast treatments: white-on-gradient, ink-on-gradient, and
 * themed/mono. Delete once a concept + treatment is chosen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IconPreviewTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val mono = FontFamily.Monospace

    private enum class Concept(val label: String) {
        TERMINAL("terminal window"),
        DROPLET("droplet + prompt"),
        MONOGRAM("monogram L cursor"),
        WATERLINE("waterline"),
        BLOOM("prism lens"),
    }

    @Test fun icon_prism() = sheet("icon_prism.png", "prism")
    @Test fun icon_ember() = sheet("icon_ember.png", "ember")
    @Test fun icon_phosphor() = sheet("icon_phosphor.png", "phosphor")
    @Test fun icon_abyss() = sheet("icon_abyss.png", "abyss")

    private fun sheet(name: String, id: String) {
        val colors = LumenColors.forId(id, dark = true)
        val dir = File("build/screenshots").apply { mkdirs() }
        val gradient = Brush.linearGradient(listOf(colors.spectrum[0], colors.spectrum[2]))
        val ink = colors.bg
        try {
            compose.setContent {
                Column(
                    Modifier
                        .fillMaxSize()
                        .background(Color(0xFF15161A))
                        .padding(start = 16.dp, end = 16.dp, top = 18.dp),
                ) {
                    Text(
                        "icon concepts \u00b7 $id",
                        color = Color(0xFFEDEDF2),
                        fontFamily = mono,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Caption("white")
                        Caption("ink")
                        Caption("themed")
                    }
                    Spacer(Modifier.height(10.dp))
                    for (c in Concept.values()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Tile(gradient, Color.White, c, round = false)
                            Spacer(Modifier.width(14.dp))
                            Tile(gradient, ink, c, round = false)
                            Spacer(Modifier.width(14.dp))
                            Tile(Color(0xFF33343A), Color.White, c, round = true)
                            Spacer(Modifier.width(16.dp))
                            Text(c.label, color = Color(0xFFC9C9D4), fontFamily = mono, fontSize = 12.sp)
                        }
                        Spacer(Modifier.height(14.dp))
                    }
                }
            }
            compose.waitForIdle()
            ScreenshotSupport.shoot(compose.activity, dir, name)
        } catch (t: Throwable) {
            File(dir, "$name.error.txt").writeText(t.stackTraceToString())
            throw t
        }
    }

    @Composable
    private fun Caption(text: String) {
        Text(text, color = Color(0xFF6E6E7A), fontFamily = mono, fontSize = 10.sp)
    }

    @Composable
    private fun Tile(bg: Brush, mark: Color, concept: Concept, round: Boolean, size: Dp = 84.dp) {
        val shape = if (round) CircleShape else RoundedCornerShape(size * 0.24f)
        Box(
            Modifier
                .size(size)
                .clip(shape)
                .background(bg),
        ) {
            Canvas(Modifier.fillMaxSize()) { drawConcept(concept, mark) }
        }
    }

    @Composable
    private fun Tile(bg: Color, mark: Color, concept: Concept, round: Boolean, size: Dp = 84.dp) {
        val shape = if (round) CircleShape else RoundedCornerShape(size * 0.24f)
        Box(
            Modifier
                .size(size)
                .clip(shape)
                .background(bg),
        ) {
            Canvas(Modifier.fillMaxSize()) { drawConcept(concept, mark) }
        }
    }

    private fun DrawScope.drawConcept(concept: Concept, ink: Color) {
        val s = size.minDimension
        val cx = size.width / 2f
        val cy = size.height / 2f
        val stroke = s * 0.075f
        val cap = StrokeCap.Round
        val join = StrokeJoin.Round
        val soft = ink.copy(alpha = 0.72f)

        when (concept) {
            Concept.TERMINAL -> {
                val inset = s * 0.20f
                drawRoundRect(
                    color = ink,
                    topLeft = Offset(inset, inset),
                    size = Size(s - 2 * inset, s - 2 * inset),
                    cornerRadius = CornerRadius(s * 0.11f),
                    style = Stroke(stroke, cap = cap, join = join),
                )
                drawLine(ink, Offset(inset, inset + s * 0.13f), Offset(s - inset, inset + s * 0.13f), stroke, cap)
                drawLine(soft, Offset(s * 0.38f, s * 0.50f), Offset(s * 0.50f, s * 0.60f), stroke, cap)
                drawLine(soft, Offset(s * 0.50f, s * 0.60f), Offset(s * 0.38f, s * 0.70f), stroke, cap)
                drawLine(ink, Offset(s * 0.56f, s * 0.70f), Offset(s * 0.72f, s * 0.70f), stroke, cap)
            }

            Concept.DROPLET -> {
                val path = dropletPath(cx, cy - s * 0.02f, s * 0.28f, s * 0.11f)
                drawPath(path, ink)
                drawLine(soft, Offset(cx - s * 0.07f, cy - s * 0.04f), Offset(cx + s * 0.03f, cy + s * 0.04f), stroke * 0.7f, cap)
                drawLine(soft, Offset(cx + s * 0.03f, cy + s * 0.04f), Offset(cx - s * 0.07f, cy + s * 0.12f), stroke * 0.7f, cap)
            }

            Concept.MONOGRAM -> {
                drawLine(ink, Offset(s * 0.36f, s * 0.27f), Offset(s * 0.36f, s * 0.68f), stroke * 1.3f, cap)
                drawLine(ink, Offset(s * 0.36f, s * 0.68f), Offset(s * 0.62f, s * 0.68f), stroke * 1.3f, cap)
                drawRect(soft, topLeft = Offset(s * 0.60f, s * 0.52f), size = Size(s * 0.13f, s * 0.16f))
            }

            Concept.WATERLINE -> {
                drawPath(dropletPath(cx, cy - s * 0.15f, s * 0.13f, s * 0.06f), ink)
                var i = 0
                val steps = 22
                var prev = Offset(s * 0.20f, cy + s * 0.14f)
                while (i <= steps) {
                    val t = i.toDouble() / steps
                    val x = s * 0.20f + (s * 0.60f) * t.toFloat()
                    val y = cy + s * 0.14f + sin(t * 2.0 * PI).toFloat() * s * 0.05f
                    val cur = Offset(x, y)
                    drawLine(ink, prev, cur, stroke * 0.8f, cap)
                    prev = cur
                    i++
                }
                drawRect(soft, topLeft = Offset(cx - s * 0.10f, cy + s * 0.30f), size = Size(s * 0.20f, s * 0.055f))
            }

            Concept.BLOOM -> {
                val radii = listOf(0.30f, 0.205f, 0.11f)
                for (i in radii.indices) {
                    val r = s * radii[i]
                    drawCircle(color = if (i == 2) ink else soft, radius = r, center = Offset(cx, cy), style = Stroke(stroke * 0.9f))
                }
                drawPath(dropletPath(cx, cy, s * 0.045f, s * 0.02f), ink)
            }
        }
    }

    private fun dropletPath(cx: Float, cy: Float, radius: Float, tail: Float): Path {
        val pts = DropletShape.outline(cx, cy, DropletShape.Params(radius = radius, tail = tail, segments = 56))
        val path = Path()
        path.moveTo(pts[0].x, pts[0].y)
        for (i in 1 until pts.size) path.lineTo(pts[i].x, pts[i].y)
        path.close()
        return path
    }
}
