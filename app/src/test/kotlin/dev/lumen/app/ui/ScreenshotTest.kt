package dev.lumen.app.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import dev.lumen.app.ui.model.StepKind
import dev.lumen.app.ui.model.UiStep
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * Renders the real screen to PNGs so the UI can be inspected without a device.
 * Runs on a plain JVM via Robolectric's native graphics. If the capture backend
 * is unavailable it records the error instead of failing the build.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private fun sample(): List<UiStep> = listOf(
        UiStep(
            "u1", StepKind.YOU, "YOU", "a1b2c3",
            "make the timeline feel like a rope",
            "make the timeline feel like a rope with droplets on it, and make it smooth",
        ),
        UiStep(
            "t1", StepKind.THINKING, "THINKING", "c3d4e5",
            "I should look at the existing layout rule first",
            "I should look at the existing layout rule first and see where the boolean open/close is coming from.",
        ),
        UiStep(
            "k1", StepKind.TOOL, "READ", "e5f607",
            "TimelineLayout.kt",
            "read core/src/main/kotlin/dev/spindle/core/ui/TimelineLayout.kt",
            rows = listOf("open" to "TimelineLayout.kt", "scan" to "LumenChatScreen.kt"),
        ),
        UiStep(
            "k2", StepKind.TOOL, "EDIT", "07a8b9",
            "3 calls - 0 failed",
            "edit LumenChatScreen.kt\nedit RopeLayout.kt\nedit StepMapper.kt",
            rows = listOf("write" to "LumenChatScreen.kt", "write" to "RopeLayout.kt", "write" to "StepMapper.kt"),
            merged = 3,
        ),
        UiStep(
            "s1", StepKind.SUBAGENT, "TASK", "b9c0d1",
            "fix the build",
            "spawn a subagent to fix the build",
            rows = listOf("compile" to "assembleDebug", "test" to "testDebugUnitTest"),
        ),
        UiStep(
            "a1", StepKind.ASSISTANT, "ASSISTANT", "d1e2f3",
            "the rope now blooms at the focus",
            "the rope now blooms at the focus line, leans like a droplet, and collapses back to a dot as you scroll. the prism splits it into a spectrum when it is a tool.",
        ),
    )

    private fun render(colors: LumenColors, name: String, force: Int) {
        compose.setContent {
            LumenChatScreen(
                steps = sample(),
                input = "",
                busy = false,
                error = null,
                colors = colors,
                forceOpenIndex = force,
                onToggleTheme = {},
            )
        }
        compose.waitForIdle()
        val dir = File("build/screenshots").apply { mkdirs() }
        runCatching {
            val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
            FileOutputStream(File(dir, name)).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }.onFailure {
            File(dir, "$name.error.txt").writeText(it.stackTraceToString())
        }
    }

    @Test
    fun light_assistant() = render(LumenColors.Light, "light_assistant.png", 5)

    @Test
    fun dark_assistant() = render(LumenColors.Dark, "dark_assistant.png", 5)

    @Test
    fun light_merged_tools() = render(LumenColors.Light, "light_merged_tools.png", 3)

    @Test
    fun dark_subagent() = render(LumenColors.Dark, "dark_subagent.png", 4)

    @Test
    fun light_early_rope() = render(LumenColors.Light, "light_early.png", 1)
}
