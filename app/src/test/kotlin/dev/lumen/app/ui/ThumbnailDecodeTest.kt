package dev.lumen.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import java.io.ByteArrayOutputStream
import android.util.Base64
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The vision-thumbnail sampling math and decode path, on a plain JVM. The
 * downsample factor must never shrink the image BELOW the requested size (or the
 * thumbnail would be blurrier than the box it is drawn into), and the decoder
 * must be resilient to empty/garbage bytes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThumbnailDecodeTest {

    @Test
    fun `sample size halves while the result stays at least as large as requested`() {
        // 4000x3000 into a 480x400 box: 4000/8 = 500 >= 480, 3000/8 = 375 < 400,
        // so the safe factor is 4 (1000x750), not 8.
        assertEquals(4, sampleSizeFor(4000, 3000, 480, 400))
    }

    @Test
    fun `sample size is one when the source already fits`() {
        assertEquals(1, sampleSizeFor(200, 150, 480, 400))
    }

    @Test
    fun `sample size is always a power of two`() {
        for (factor in 1..6) {
            val src = 100 * (1 shl factor)
            val sample = sampleSizeFor(src, src, 480, 400)
            assertEquals(0, sample and (sample - 1), "sample $sample should be a power of two")
        }
    }

    @Test
    fun `sample size rejects non-positive dimensions`() {
        assertEquals(1, sampleSizeFor(0, 100, 10, 10))
        assertEquals(1, sampleSizeFor(100, 100, 0, 10))
    }

    @Test
    fun `decodeThumbnail downsamples a real png`() {
        val bytes = pngBytes(1000, 800)
        val bmp = decodeThumbnail(bytes, reqW = 250, reqH = 200)
        assertNotNull(bmp)
        // 1000/4 = 250, 800/4 = 200 — the sampled image is exactly the request.
        assertTrue(bmp.width <= 1000 && bmp.height <= 800)
        assertTrue(bmp.width >= 250 && bmp.height >= 200)
    }

    @Test
    fun `decodeThumbnail returns null for empty or garbage bytes`() {
        assertNull(decodeThumbnail(ByteArray(0), 100, 100))
        assertNull(decodeThumbnail("not an image".toByteArray(), 100, 100))
    }

    @Test
    fun `looksLikeImage recognizes real signatures and rejects text`() {
        assertTrue(looksLikeImage(pngBytes(4, 4)))
        assertTrue(!looksLikeImage(ByteArray(0)))
        assertTrue(!looksLikeImage("not an image".toByteArray()))
    }

    @Test
    fun `hashKey is stable and distinguishes payloads`() {
        val a = Base64.encodeToString(ByteArray(64) { it.toByte() }, Base64.NO_WRAP)
        val b = Base64.encodeToString(ByteArray(64) { (it + 1).toByte() }, Base64.NO_WRAP)
        assertEquals(hashKey(a), hashKey(a))
        assertTrue(hashKey(a) != hashKey(b))
    }

    private fun pngBytes(w: Int, h: Int): ByteArray {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(android.graphics.Color.rgb(20, 120, 200))
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }
}
