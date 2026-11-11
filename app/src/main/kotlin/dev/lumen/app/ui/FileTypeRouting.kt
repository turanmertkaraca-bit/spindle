package dev.lumen.app.ui

/**
 * What tapping a file should do, decided in ONE place.
 *
 * This exists because the same question — "what is this file and what should
 * happen when it is tapped?" — was answered by four independent extension tables:
 * the Files row tap, the Files overflow menu, `ChatViewModel.openFileExternally`'s
 * mime table, and the chat-mention router in `MainActivity`. They disagreed.
 *
 * The visible symptoms, all reproduced:
 *
 *  - a build artefact (`app-debug.apk`) opened in the **text editor** from Files,
 *    showing binary mojibake, while the same file tapped in a chat message offered
 *    to install it — `installApk` existed and worked, it was simply unreachable
 *    from the file browser;
 *  - a PDF, a zip or any other non-image fell into the editor too;
 *  - the mime table knew only images, so every other type was handed to the system
 *    as `application/octet-stream`, which no viewer accepts.
 *
 * Pure Kotlin on purpose: `:app` Compose surfaces are only verified by CI, so a
 * routing rule kept inside them is effectively untested. `FileTypeRoutingTest`
 * runs on a plain JVM.
 */
enum class FileRoute {
    /** A directory: descend into it. */
    DIRECTORY,

    /** Text the in-app editor can usefully show and save. */
    EDIT,

    /** An HTML page: render it in the sandboxed canvas. */
    PREVIEW_HTML,

    /** An image: hand it to the phone's viewer. */
    VIEW_IMAGE,

    /** An APK: offer to install it. Never auto-install — confirm first. */
    INSTALL_APK,

    /** A PDF: hand it to the system viewer. Deliberately no built-in viewer. */
    OPEN_PDF,

    /** An archive: identify it, and offer "open with". No built-in extractor. */
    OPEN_ARCHIVE,

    /** Anything else: offer "open with" rather than a dead tap or a broken editor. */
    OPEN_WITH,
}

/**
 * A resolved file: what it is, how to label it, and the mime type to hand the
 * system when the action leaves the app.
 */
data class FileType(
    val route: FileRoute,
    /** The mime type to pass to an external viewer. Empty when not applicable. */
    val mime: String,
    /** A short human label for the overflow menu, e.g. "PDF document". */
    val label: String,
)

object FileTypeRouting {

    /** Extensions the in-app editor handles well. Anything else is not edited by default. */
    private val TEXT = setOf(
        "kt", "kts", "java", "xml", "json", "yml", "yaml", "toml", "properties",
        "gradle", "md", "markdown", "txt", "log", "csv", "tsv",
        "js", "mjs", "cjs", "ts", "tsx", "jsx", "html", "htm", "css", "scss",
        "py", "rb", "go", "rs", "c", "h", "cc", "cpp", "hpp", "cs", "php",
        "sh", "bash", "zsh", "fish", "ps1", "bat",
        "sql", "ini", "cfg", "conf", "env", "gitignore", "dockerfile",
        "makefile", "cmake", "proto", "graphql", "svelte", "vue",
    )

    private val IMAGE = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "heic", "heif", "avif")

    private val ARCHIVE = setOf("zip", "tar", "gz", "tgz", "bz2", "xz", "7z", "rar", "jar")

    private val MIME = mapOf(
        "png" to "image/png",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "webp" to "image/webp",
        "gif" to "image/gif",
        "bmp" to "image/bmp",
        "heic" to "image/heic",
        "heif" to "image/heif",
        "avif" to "image/avif",
        "pdf" to "application/pdf",
        "zip" to "application/zip",
        "tar" to "application/x-tar",
        "gz" to "application/gzip",
        "tgz" to "application/gzip",
        "bz2" to "application/x-bzip2",
        "xz" to "application/x-xz",
        "7z" to "application/x-7z-compressed",
        "rar" to "application/vnd.rar",
        "jar" to "application/java-archive",
        "apk" to "application/vnd.android.package-archive",
    )

    /** The final extension, lowercased, or "" when there is none. */
    fun extensionOf(name: String): String =
        name.substringAfterLast('.', "").lowercase()

    /**
     * A compound extension where the last one alone is not the whole story.
     *
     * `notes.tar.gz` must read as an archive, and it does either way — but
     * `file.kt.bak` should NOT be treated as Kotlin source, and a bare
     * `.gitignore`-style dotfile has no extension at all. Returning the tail two
     * segments for the tarball family is what lets [label] say "tar.gz".
     */
    private fun compoundExtension(name: String): String {
        val lower = name.lowercase()
        for (pair in listOf("tar.gz", "tar.bz2", "tar.xz")) {
            if (lower.endsWith(".$pair")) return pair
        }
        return extensionOf(name)
    }

    /**
     * Resolve [name] to a route. Total: never throws, and an unknown or
     * extension-less file is [FileRoute.OPEN_WITH] so the UI offers a real action
     * instead of a dead tap.
     */
    fun route(name: String, isDir: Boolean = false): FileType {
        if (isDir) return FileType(FileRoute.DIRECTORY, "", "folder")
        val ext = extensionOf(name)
        return when {
            ext == "apk" -> FileType(FileRoute.INSTALL_APK, MIME.getValue("apk"), "Android app")
            ext == "pdf" -> FileType(FileRoute.OPEN_PDF, MIME.getValue("pdf"), "PDF document")
            ext == "html" || ext == "htm" ->
                FileType(FileRoute.PREVIEW_HTML, "text/html", "Web page")
            ext in IMAGE -> FileType(FileRoute.VIEW_IMAGE, MIME[ext] ?: "image/*", "Image")
            // The compound check must come before the single one, or `.tar.gz`
            // resolves on "gz" and the label loses the "tar" half.
            compoundExtension(name) in ARCHIVE || ext in ARCHIVE ->
                FileType(FileRoute.OPEN_ARCHIVE, MIME[ext] ?: "application/octet-stream", "Archive")
            ext in TEXT -> FileType(FileRoute.EDIT, "text/plain", "Text")
            // A dotfile such as `.gitignore` or `Makefile` has no extension but is
            // plainly text. Treat an extension-less name as editable rather than
            // sending the user to "open with" for something the editor can show.
            ext.isEmpty() -> FileType(FileRoute.EDIT, "text/plain", "Text")
            else -> FileType(FileRoute.OPEN_WITH, "application/octet-stream", "File")
        }
    }

    /**
     * The mime type to hand an external viewer for [name].
     *
     * Always a real type: `application/octet-stream` is only used when nothing is
     * known, and even then it is better than the previous behaviour of labelling a
     * PDF as binary.
     */
    fun mimeOf(name: String): String = route(name).mime.ifEmpty { "application/octet-stream" }

    /** The one-glyph hint for a file row, so a listing scans without reading names. */
    fun glyph(name: String): String = when (route(name).route) {
        FileRoute.PREVIEW_HTML -> "\u25c9"
        FileRoute.VIEW_IMAGE -> "\u25a3"
        FileRoute.INSTALL_APK -> "\u2b07"
        FileRoute.OPEN_PDF -> "\u2b1a"
        FileRoute.OPEN_ARCHIVE -> "\u2b1f"
        FileRoute.EDIT -> when (extensionOf(name)) {
            "md", "markdown", "txt", "log" -> "\u2261"
            "kt", "kts", "java", "py", "js", "ts", "sh", "go", "rs", "c", "h" -> "\u2039\u203a"
            "json", "yml", "yaml", "toml", "ini", "cfg" -> "{}"
            else -> "\u2261"
        }
        FileRoute.OPEN_WITH -> "\u00b7"
        FileRoute.DIRECTORY -> "\u25b8"
    }
}
