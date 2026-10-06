package dev.lumen.app.platform

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/**
 * Android-only bridge from a chat workspace mention to a system action that
 * leaves the app: installing an APK through the package installer or handing a
 * file to a viewer. Every launch is wrapped in [runCatching] so a headless or
 * misconfigured host degrades to a short message instead of a crash.
 */
class WorkspaceActions(private val context: Context, private val workspaceRoot: File) {

    /**
     * Launch the system package installer for the APK at [absolutePath]. Returns
     * null on success, or a short human message when it could not be launched
     * (missing file, wrong extension, or a refused/lost intent).
     */
    fun installApk(absolutePath: String): String? {
        val file = File(absolutePath)
        if (!file.exists() || !file.name.lowercase().endsWith(".apk")) {
            return "not an apk: $absolutePath"
        }
        return runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            if (!context.packageManager.canRequestPackageInstalls()) {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                "Allow installs for Lumen, then tap again"
            } else {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uri, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                null
            }
        }.getOrElse { t -> t.message ?: "cannot install $absolutePath" }
    }

    /**
     * Open the workspace file at [absolutePath] in an external app as [mime].
     * Returns null on success, or a short message when the intent could not be
     * launched.
     */
    fun openExternally(absolutePath: String, mime: String): String? =
        runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(absolutePath))
            context.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.exceptionOrNull()?.let { it.message ?: "cannot open $absolutePath" }
}
