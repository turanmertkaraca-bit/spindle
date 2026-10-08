package dev.lumen.app.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

/**
 * Shared helpers for the Robolectric screenshot / animation harnesses. Extracted
 * from the pattern in `ScreenshotTest` so the animation tests can reuse it
 * without touching the existing test.
 *
 * Compose's own `captureToImage()` never sees a draw callback under Robolectric,
 * so everything here draws the decor view straight onto a `Canvas` instead.
 */
object ScreenshotSupport {

    /** Capture the whole decor view at 1:1. */
    fun captureDecor(activity: Activity): Bitmap = captureDecor(activity, 1f)

    /**
     * Capture the decor view scaled by [scale] (e.g. 0.5f for half-size frames).
     * Scaling the canvas is cheap and keeps a long animation sequence small.
     */
    fun captureDecor(activity: Activity, scale: Float): Bitmap {
        val view = activity.window.decorView
        val w = view.width
        val h = view.height
        check(w > 0 && h > 0) { "decor view not laid out: ${w}x$h" }
        val s = if (scale > 0f) scale else 1f
        val bw = (w * s).toInt().coerceAtLeast(1)
        val bh = (h * s).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.scale(bw.toFloat() / w.toFloat(), bh.toFloat() / h.toFloat())
        view.draw(canvas)
        return bmp
    }

    /** Write [bmp] to [file] as a PNG, creating parent directories as needed. */
    fun writePng(bmp: Bitmap, file: File) {
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /**
     * Draw [activity]'s decor view and write it to `[dir]/[name]`. Unlike the old
     * best-effort version, this fails loudly: a missing, zero-size or wholly blank
     * frame throws an [AssertionError] naming the scene instead of silently
     * leaving an `.error.txt` marker. Callers may still catch and record the
     * failure, but the failure can no longer be swallowed into a passing test.
     */
    fun shoot(activity: Activity, dir: File, name: String, scale: Float = 1f) {
        val bmp = try {
            captureDecor(activity, scale)
        } catch (t: Throwable) {
            throw AssertionError("screenshot '$name' failed to render", t)
        }
        assertNotBlank(bmp, name)
        try {
            writePng(bmp, File(dir, name))
        } catch (t: Throwable) {
            throw AssertionError("screenshot '$name' failed to write", t)
        }
    }

    /**
     * The largest per-channel spread across the frame that still counts as a
     * single flat colour. ARGB_8888 is lossless, so a genuinely flat frame has a
     * spread of exactly 0; the small tolerance absorbs any single stray pixel and
     * guarantees that a real empty state (which always draws text or a glyph, and
     * therefore anti-aliased edges) is never mistaken for blank.
     */
    private const val BLANK_TOLERANCE = 2

    /**
     * Fail with the scene name if [bmp] is null, has no pixels, or is effectively
     * blank. "Blank" is scoped narrowly — every sampled pixel must lie within
     * [BLANK_TOLERANCE] per channel of every other — so a flat-coloured empty
     * state still passes as soon as it draws any text, border or glyph.
     */
    fun assertNotBlank(bmp: Bitmap?, scene: String) {
        if (bmp == null) throw AssertionError("screenshot '$scene' captured no bitmap")
        if (bmp.width <= 0 || bmp.height <= 0) {
            throw AssertionError("screenshot '$scene' captured a zero-size bitmap (${bmp.width}x${bmp.height})")
        }
        var minR = 255
        var minG = 255
        var minB = 255
        var maxR = 0
        var maxG = 0
        var maxB = 0
        val stride = 2
        var y = 0
        while (y < bmp.height) {
            var x = 0
            while (x < bmp.width) {
                val c = bmp.getPixel(x, y)
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                if (r < minR) minR = r
                if (r > maxR) maxR = r
                if (g < minG) minG = g
                if (g > maxG) maxG = g
                if (b < minB) minB = b
                if (b > maxB) maxB = b
                x += stride
            }
            y += stride
        }
        val spread = maxOf(maxR - minR, maxG - minG, maxB - minB)
        if (spread <= BLANK_TOLERANCE) {
            throw AssertionError(
                "screenshot '$scene' is blank: every sampled pixel is within $BLANK_TOLERANCE " +
                    "of a single flat colour (spread=$spread)",
            )
        }
    }

    /**
     * Mean absolute per-channel difference between [a] and [b], sampling every
     * [stride]-th pixel in both axes. Returns 0.0 for identical images; larger is
     * more different. A stride of 4 keeps the cost low on full-size frames.
     */
    fun meanAbsDiff(a: Bitmap, b: Bitmap, stride: Int = 4): Double {
        val w = minOf(a.width, b.width)
        val h = minOf(a.height, b.height)
        if (w <= 0 || h <= 0) return 0.0
        val step = stride.coerceAtLeast(1)
        val pa = IntArray(w * h)
        val pb = IntArray(w * h)
        a.getPixels(pa, 0, w, 0, 0, w, h)
        b.getPixels(pb, 0, w, 0, 0, w, h)
        var sum = 0.0
        var count = 0L
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val i = y * w + x
                val ca = pa[i]
                val cb = pb[i]
                val dr = abs(((ca shr 16) and 0xFF) - ((cb shr 16) and 0xFF))
                val dg = abs(((ca shr 8) and 0xFF) - ((cb shr 8) and 0xFF))
                val db = abs((ca and 0xFF) - (cb and 0xFF))
                sum += (dr + dg + db).toDouble()
                count++
                x += step
            }
            y += step
        }
        return if (count == 0L) 0.0 else sum / (3.0 * count.toDouble())
    }
}
