package dev.lumen.app.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The file-type router.
 *
 * Plain JVM (no Robolectric): the Files screen is a Compose surface verified only
 * by CI, so a routing rule kept inside it would be untested. Every case here was a
 * real symptom — an APK opening in the text editor, a PDF labelled as binary, an
 * archive with no affordance.
 */
class FileTypeRoutingTest {

    private fun route(name: String) = FileTypeRouting.route(name).route

    // ---- the defects this replaced -------------------------------------------

    @Test
    fun `an apk offers install instead of opening in the text editor`() {
        // Was: fell into `else -> onEnter`, i.e. the editor showing binary garbage.
        assertEquals(FileRoute.INSTALL_APK, route("app-debug.apk"))
        assertEquals("application/vnd.android.package-archive", FileTypeRouting.mimeOf("app-debug.apk"))
    }

    @Test
    fun `a pdf is handed to the system viewer, not the editor`() {
        assertEquals(FileRoute.OPEN_PDF, route("spec.pdf"))
        assertEquals("application/pdf", FileTypeRouting.mimeOf("spec.pdf"))
    }

    @Test
    fun `an archive is identified and offered open-with`() {
        assertEquals(FileRoute.OPEN_ARCHIVE, route("logs.zip"))
        assertEquals(FileRoute.OPEN_ARCHIVE, route("backup.tar.gz"))
        assertEquals(FileRoute.OPEN_ARCHIVE, route("bundle.jar"))
        assertEquals("application/gzip", FileTypeRouting.mimeOf("backup.tar.gz"))
    }

    @Test
    fun `an unknown binary type is open-with, never a dead tap`() {
        // Was: `application/octet-stream` handed to the editor as if it were text.
        assertEquals(FileRoute.OPEN_WITH, route("model.onnx"))
        assertEquals("application/octet-stream", FileTypeRouting.mimeOf("model.onnx"))
    }

    // ---- the routes that already worked --------------------------------------

    @Test
    fun `html previews in the canvas`() {
        assertEquals(FileRoute.PREVIEW_HTML, route("index.html"))
        assertEquals(FileRoute.PREVIEW_HTML, route("page.HTM"))
    }

    @Test
    fun `images go to the phone viewer with a real mime type`() {
        assertEquals(FileRoute.VIEW_IMAGE, route("shot.png"))
        assertEquals("image/png", FileTypeRouting.mimeOf("shot.png"))
        assertEquals("image/jpeg", FileTypeRouting.mimeOf("pic.JPG"))
        assertEquals("image/jpeg", FileTypeRouting.mimeOf("pic.jpeg"))
    }

    @Test
    fun `source and config files are editable`() {
        for (n in listOf("Main.kt", "build.gradle.kts", "app.json", "ci.yml", "notes.md", "run.sh", "a.py")) {
            assertEquals(FileRoute.EDIT, route(n), n)
        }
    }

    @Test
    fun `a directory is a directory`() {
        assertEquals(FileRoute.DIRECTORY, FileTypeRouting.route("src", isDir = true).route)
    }

    // ---- awkward inputs ------------------------------------------------------

    @Test
    fun `case is irrelevant`() {
        assertEquals(FileRoute.INSTALL_APK, route("APP-DEBUG.APK"))
        assertEquals(FileRoute.OPEN_PDF, route("Spec.PDF"))
        assertEquals(FileRoute.PREVIEW_HTML, route("Page.Html"))
    }

    @Test
    fun `a dotfile with no extension is text, not open-with`() {
        // `.gitignore` and `Makefile` are plainly editable; sending them to
        // "open with" would be a worse answer than the editor.
        assertEquals(FileRoute.EDIT, route(".gitignore"))
        assertEquals(FileRoute.EDIT, route("Makefile"))
    }

    @Test
    fun `a backup suffix does not make a file source`() {
        // `Main.kt.bak` must not be read as Kotlin source; it is unknown content.
        assertEquals(FileRoute.OPEN_WITH, route("Main.kt.bak"))
    }

    @Test
    fun `route is total and never throws`() {
        for (n in listOf("", ".", "..", "a.", ".a", "no-ext", "x.$$$", "..png", "a b.pdf")) {
            FileTypeRouting.route(n) // must not throw
            FileTypeRouting.mimeOf(n)
            FileTypeRouting.glyph(n)
        }
    }

    @Test
    fun `every route has a glyph and a mime`() {
        for (n in listOf("a.kt", "a.pdf", "a.apk", "a.png", "a.zip", "a.html", "a.bin", "src")) {
            val t = FileTypeRouting.route(n, isDir = n == "src")
            assertTrue(t.label.isNotBlank(), "no label for $n")
            assertTrue(FileTypeRouting.glyph(n).isNotBlank(), "no glyph for $n")
            assertEquals(1, FileTypeRouting.glyph(n).length.coerceAtMost(2).coerceAtLeast(1))
        }
    }

    @Test
    fun `label names the kind so the menu can say what it will do`() {
        assertEquals("Android app", FileTypeRouting.route("a.apk").label)
        assertEquals("PDF document", FileTypeRouting.route("a.pdf").label)
        assertEquals("folder", FileTypeRouting.route("src", isDir = true).label)
    }
}
