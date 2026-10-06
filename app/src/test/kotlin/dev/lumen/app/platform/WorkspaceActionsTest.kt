package dev.lumen.app.platform

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import kotlin.test.assertNotNull

/**
 * The workspace action bridge must fail soft: a headless Robolectric run has no
 * resolving installer/viewer, so a missing or non-APK target has to come back as
 * a short message rather than an exception.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorkspaceActionsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val workspace: File = Files.createTempDirectory("lumen-actions").toFile()
    private val actions = WorkspaceActions(context, workspace)

    @Test
    fun `installApk returns a message for a missing path`() {
        assertNotNull(actions.installApk(File(workspace, "missing.apk").absolutePath))
    }

    @Test
    fun `installApk returns a message for a non-apk file`() {
        val file = File(workspace, "notes.txt").apply { writeText("hi") }
        assertNotNull(actions.installApk(file.absolutePath))
    }

    @Test
    fun `openExternally on a missing file does not throw`() {
        actions.openExternally(File(workspace, "missing.png").absolutePath, "image/png")
    }
}
