package dev.lumen.app.ui

import android.graphics.Bitmap
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageTypeSpecifier
import javax.imageio.metadata.IIOMetadata
import javax.imageio.metadata.IIOMetadataNode
import javax.imageio.stream.FileImageOutputStream

/**
 * A zero-dependency animated-GIF encoder built on the JDK's `javax.imageio`
 * GIF plugin. Test-support only: it runs on the host JVM under Robolectric,
 * where `java.desktop` is available. Every failure is swallowed (and logged)
 * so a GIF problem can never fail the build — see [write].
 */
object AnimatedGifWriter {

    /** Encode [frames] as an infinite-looping GIF at [out], one frame per [delayMs]. */
    fun write(frames: List<Bitmap>, out: File, delayMs: Int = 100) {
        if (frames.isEmpty()) return
        runCatching {
            out.parentFile?.mkdirs()
            val writer = ImageIO.getImageWritersByFormatName("gif").next()
            try {
                FileImageOutputStream(out).use { stream ->
                    writer.output = stream
                    writer.prepareWriteSequence(null)
                    val param = writer.defaultWriteParam
                    val type = ImageTypeSpecifier.createFromBufferedImageType(BufferedImage.TYPE_INT_RGB)
                    // GIF delay is in centiseconds; most viewers clamp 0–1 to ~100ms, so floor at 2.
                    val delayCs = ((delayMs + 5) / 10).coerceAtLeast(2)
                    for (frame in frames) {
                        val meta = writer.getDefaultImageMetadata(type, param)
                        configure(meta, delayCs)
                        writer.writeToSequence(IIOImage(toBufferedImage(frame), null, meta), param)
                    }
                    writer.endWriteSequence()
                }
            } finally {
                writer.dispose()
            }
        }.onFailure {
            println("AnimatedGifWriter: could not write $out (${it.javaClass.simpleName}: ${it.message})")
        }
    }

    private fun configure(meta: IIOMetadata, delayCs: Int) {
        val format = meta.nativeMetadataFormatName ?: return
        val root = meta.getAsTree(format) as? IIOMetadataNode ?: return

        val gce = child(root, "GraphicControlExtension")
            ?: IIOMetadataNode("GraphicControlExtension").also { root.appendChild(it) }
        gce.setAttribute("disposalMethod", "none")
        gce.setAttribute("userInputFlag", "FALSE")
        gce.setAttribute("transparentColorFlag", "FALSE")
        gce.setAttribute("delayTime", delayCs.toString())
        gce.setAttribute("transparentColorIndex", "0")

        // Netscape 2.0 application extension: 3-byte user object {1, 0, 0} = loop forever.
        val appExt = IIOMetadataNode("ApplicationExtension")
        appExt.setAttribute("applicationID", "NETSCAPE")
        appExt.setAttribute("authenticationCode", "2.0")
        appExt.setUserObject(byteArrayOf(0x1, 0x0, 0x0))
        val appExts = child(root, "ApplicationExtensions")
            ?: IIOMetadataNode("ApplicationExtensions").also { root.appendChild(it) }
        appExts.appendChild(appExt)

        meta.setFromTree(format, root)
    }

    private fun child(parent: IIOMetadataNode, name: String): IIOMetadataNode? {
        var node = parent.firstChild
        while (node != null) {
            if (node.nodeName.equals(name, ignoreCase = true)) return node as IIOMetadataNode
            node = node.nextSibling
        }
        return null
    }

    private fun toBufferedImage(bmp: Bitmap): BufferedImage {
        val w = bmp.width
        val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        val image = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        image.setRGB(0, 0, w, h, pixels, 0, w)
        return image
    }
}
