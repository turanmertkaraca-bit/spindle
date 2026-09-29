package dev.lumen.app.ui

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * A self-contained animated-GIF (GIF89a) encoder.
 *
 * Why hand-written: Android unit tests compile against `android.jar`, which has
 * no `java.awt` / `javax.imageio`, so the JDK GIF writer is unavailable. This is
 * a small, dependency-free encoder that turns a list of [Bitmap] frames into a
 * looping GIF. It quantises to a fixed 6x7x6 RGB cube and runs the standard GIF
 * LZW coder. Test-support only; every failure is swallowed so a GIF problem can
 * never fail the build.
 */
object AnimatedGifWriter {

    private const val R_LEVELS = 6
    private const val G_LEVELS = 7
    private const val B_LEVELS = 6
    private const val PALETTE_SIZE = R_LEVELS * G_LEVELS * B_LEVELS

    /** Encode [frames] as an infinite-looping GIF at [out], one frame per [delayMs]. */
    fun write(frames: List<Bitmap>, out: File, delayMs: Int = 100) {
        if (frames.isEmpty()) return
        runCatching {
            out.parentFile?.mkdirs()
            val w = frames.first().width
            val h = frames.first().height
            FileOutputStream(out).use { stream ->
                stream.write(encode(frames, w, h, delayMs))
            }
        }.onFailure {
            println("AnimatedGifWriter: could not write $out (${it.javaClass.simpleName}: ${it.message})")
        }
    }

    private fun encode(frames: List<Bitmap>, w: Int, h: Int, delayMs: Int): ByteArray {
        val buffer = ByteArrayOutputStream()
        val delayCs = ((delayMs + 5) / 10).coerceAtLeast(2)

        // Header + logical screen descriptor.
        buffer.write("GIF89a".toByteArray(Charsets.US_ASCII))
        writeShort(buffer, w)
        writeShort(buffer, h)
        buffer.write(0xF7) // global colour table: 256 entries, 8-bit
        buffer.write(0)    // background colour index
        buffer.write(0)    // pixel aspect ratio

        writeColourTable(buffer)

        // Netscape looping extension: loop forever.
        buffer.write(0x21); buffer.write(0xFF); buffer.write(0x0B)
        buffer.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
        buffer.write(0x03); buffer.write(0x01)
        writeShort(buffer, 0)
        buffer.write(0x00)

        val indices = IntArray(w * h)
        val pixels = IntArray(w * h)
        for (frame in frames) {
            frame.getPixels(pixels, 0, w, 0, 0, w, h)
            for (i in pixels.indices) indices[i] = quantise(pixels[i])

            // Graphic control extension (delay + no transparency).
            buffer.write(0x21); buffer.write(0xF9); buffer.write(0x04)
            buffer.write(0x00)
            writeShort(buffer, delayCs)
            buffer.write(0x00)
            buffer.write(0x00)

            // Image descriptor.
            buffer.write(0x2C)
            writeShort(buffer, 0)
            writeShort(buffer, 0)
            writeShort(buffer, w)
            writeShort(buffer, h)
            buffer.write(0x00) // no local colour table; not interlaced

            lzwEncode(buffer, indices, 8)
        }

        buffer.write(0x3B) // trailer
        return buffer.toByteArray()
    }

    /** A fixed 6x7x6 RGB cube, written as 256 3-byte entries. */
    private fun writeColourTable(buffer: ByteArrayOutputStream) {
        for (i in 0 until 256) {
            if (i < PALETTE_SIZE) {
                val r = i / (G_LEVELS * B_LEVELS)
                val g = (i / B_LEVELS) % G_LEVELS
                val b = i % B_LEVELS
                buffer.write(r * 255 / (R_LEVELS - 1))
                buffer.write(g * 255 / (G_LEVELS - 1))
                buffer.write(b * 255 / (B_LEVELS - 1))
            } else {
                buffer.write(0); buffer.write(0); buffer.write(0)
            }
        }
    }

    /** Map an ARGB pixel to its nearest index in the colour cube. */
    private fun quantise(argb: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        val ri = (r * (R_LEVELS - 1) + 127) / 255
        val gi = (g * (G_LEVELS - 1) + 127) / 255
        val bi = (b * (B_LEVELS - 1) + 127) / 255
        return (ri * G_LEVELS + gi) * B_LEVELS + bi
    }

    /** Standard GIF LZW compression with sub-block output. */
    private fun lzwEncode(buffer: ByteArrayOutputStream, indices: IntArray, minCodeSize: Int) {
        val out = ByteArrayOutputStream()
        val clearCode = 1 shl minCodeSize
        val endCode = clearCode + 1
        var codeSize = minCodeSize + 1
        var nextCode = endCode + 1

        val dictionary = HashMap<Int, Int>(4096)
        fun resetDict() {
            dictionary.clear()
            codeSize = minCodeSize + 1
            nextCode = endCode + 1
        }

        var bitBuffer = 0
        var bitCount = 0
        fun emit(code: Int) {
            bitBuffer = bitBuffer or (code shl bitCount)
            bitCount += codeSize
            while (bitCount >= 8) {
                out.write(bitBuffer and 0xFF)
                bitBuffer = bitBuffer ushr 8
                bitCount -= 8
            }
        }

        var current = indices[0]
        emit(clearCode)
        resetDict()
        for (i in 1 until indices.size) {
            val next = indices[i]
            val key = (current shl 8) or next
            val found = dictionary[key]
            if (found != null) {
                current = found
            } else {
                emit(current)
                if (nextCode < 4096) {
                    dictionary[key] = nextCode
                    nextCode++
                    if (nextCode > (1 shl codeSize) && codeSize < 12) codeSize++
                } else {
                    emit(clearCode)
                    resetDict()
                }
                current = next
            }
        }
        emit(current)
        emit(endCode)
        if (bitCount > 0) out.write(bitBuffer and 0xFF)

        // Flush as sub-blocks preceded by their length.
        val data = out.toByteArray()
        buffer.write(minCodeSize)
        var offset = 0
        while (offset < data.size) {
            val len = minOf(255, data.size - offset)
            buffer.write(len)
            buffer.write(data, offset, len)
            offset += len
        }
        buffer.write(0x00) // block terminator
    }

    private fun writeShort(buffer: ByteArrayOutputStream, value: Int) {
        buffer.write(value and 0xFF)
        buffer.write((value shr 8) and 0xFF)
    }
}
