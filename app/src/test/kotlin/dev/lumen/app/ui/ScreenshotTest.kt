package dev.lumen.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
 * Renders the real screens to PNGs so the UI can be inspected without a device.
 * Runs on a plain JVM via Robolectric's native graphics; it draws the decor view
 * directly because Compose's own capture path never sees a draw callback here.
 * Any failure is recorded to a .error.txt instead of failing the build.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun sample(): List<UiStep> = listOf(
        UiStep(
            "u1", StepKind.YOU, "YOU", "a1b2c3",
            "fix the login redirect bug",
            "the /login redirect is broken — after signing in it bounces straight back to /login",
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
            "found the redirect loop",
            "Found it. The session cookie is set after res.redirect(), so the browser leaves before Set-Cookie is written. Writing the cookie first fixes the loop.",
        ),
    )

    private fun shoot(name: String, content: @Composable () -> Unit) {
        compose.setContent(content)
        val dir = File("build/screenshots").apply { mkdirs() }
        runCatching {
            compose.waitForIdle()
            val view = compose.activity.window.decorView
            val w = view.width
            val h = view.height
            check(w > 0 && h > 0) { "decor view not laid out: ${w}x$h" }
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bmp))
            FileOutputStream(File(dir, name)).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }.onFailure {
            File(dir, "$name.error.txt").writeText(it.stackTraceToString())
        }
    }

    private fun chat(colors: LumenColors, name: String, force: Int? = null) = shoot(name) {
        LumenChatScreen(
            steps = sample(),
            input = "",
            busy = false,
            error = null,
            colors = colors,
            forceOpenIndex = force,
            title = "make the timeline a spine",
            onHome = {},
            ambient = false,
            onToggleTheme = {},
            onEditKey = {},
        )
    }

    @Test fun light_assistant() = chat(LumenColors.Light, "light_assistant.png", 4)

    @Test fun dark_assistant() = chat(LumenColors.Dark, "dark_assistant.png", 4)

    @Test fun light_merged_tools() = chat(LumenColors.Light, "light_merged_tools.png", 2)

    @Test fun dark_subagent() = chat(LumenColors.Dark, "dark_subagent.png", 3)

    @Test fun light_early_rope() = chat(LumenColors.Light, "light_early.png", 1)

    @Test fun dark_thinking() = shoot("dark_thinking.png") {
        LumenChatScreen(
            steps = listOf(
                UiStep("u1", StepKind.YOU, "YOU", "a1b2c3", "why is the sky blue", "why is the sky blue"),
                UiStep(
                    "t1", StepKind.THINKING, "THINKING", "c3d4e5", "considering",
                    "Rayleigh scattering makes shorter wavelengths bounce around the atmosphere far more than longer ones, so blue light reaches the eye from every direction.",
                ),
            ),
            input = "", busy = true, error = null,
            colors = LumenColors.Dark, forceOpenIndex = 1,
            onHome = {}, ambient = false, onToggleTheme = {}, onEditKey = {},
        )
    }

    @Test fun light_answered() = shoot("light_answered.png") {
        LumenChatScreen(
            steps = listOf(
                UiStep("u1", StepKind.YOU, "YOU", "a1b2c3", "why is the sky blue", "why is the sky blue"),
                UiStep(
                    "t1", StepKind.THINKING, "THINKING", "c3d4e5", "considering",
                    "Rayleigh scattering makes shorter wavelengths bounce around the atmosphere far more than longer ones, so blue light reaches the eye from every direction.",
                ),
                UiStep(
                    "a1", StepKind.ASSISTANT, "ASSISTANT", "d1e2f3", "it scatters",
                    "sunlight scatters off air molecules, and short blue wavelengths scatter far more than red, so the sky looks blue.",
                ),
            ),
            input = "", busy = false, error = null,
            colors = LumenColors.Light, forceOpenIndex = 1,
            onHome = {}, ambient = false, onToggleTheme = {}, onEditKey = {},
        )
    }

    @Test fun empty_session() = shoot("empty_dark.png") {
        LumenChatScreen(
            steps = emptyList(),
            input = "",
            busy = false,
            error = null,
            colors = LumenColors.Dark,
            ambient = false,
            onToggleTheme = {},
            onEditKey = {},
        )
    }

    @Test fun empty_session_light() = shoot("empty_light.png") {
        LumenChatScreen(
            steps = emptyList(),
            input = "",
            busy = false,
            error = null,
            colors = LumenColors.Light,
            ambient = false,
            onToggleTheme = {},
            onEditKey = {},
        )
    }

    @Test fun auth_error_dark() = shoot("error_dark.png") {
        LumenChatScreen(
            steps = emptyList(),
            input = "",
            busy = false,
            error = "OpenAI HTTP 401: {\"error\":{\"message\":\"Missing Authentication header\",\"code\":401}}",
            colors = LumenColors.Dark,
            ambient = false,
            onToggleTheme = {},
            onEditKey = {},
        )
    }

    @Test fun light_running_tool() = shoot("light_running.png") {
        LumenChatScreen(
            steps = listOf(
                UiStep("u", StepKind.YOU, "YOU", "aa11bb", "run the tests", "run the tests"),
                UiStep(
                    "r", StepKind.TOOL, "BASH", "cc22dd", "running…", "npm test",
                    rows = listOf("run" to "npm test"), running = true,
                ),
            ),
            input = "",
            busy = true,
            error = null,
            colors = LumenColors.Light,
            forceOpenIndex = 1,
            ambient = false,
            onToggleTheme = {},
        )
    }

    @Test fun key_screen_light() = shoot("key_light.png") {
        KeyScreen(colors = LumenColors.Light, onSubmit = { _, _ -> }, onToggleTheme = {})
    }

    @Test fun key_screen_dark() = shoot("key_dark.png") {
        KeyScreen(colors = LumenColors.Dark, onSubmit = { _, _ -> }, onToggleTheme = {})
    }

    @Test fun light_focus_top_tugs_down() = chat(LumenColors.Light, "light_focus_top.png", 0)

    @Test fun dark_focus_top_tugs_down() = chat(LumenColors.Dark, "dark_focus_top.png", 0)

    @Test fun single_droplet() = shoot("single_droplet.png") {
        LumenChatScreen(
            steps = listOf(UiStep("only", StepKind.YOU, "YOU", "11aa22", "hello", "hello")),
            input = "",
            busy = false,
            error = null,
            colors = LumenColors.Light,
            forceOpenIndex = 0,
            ambient = false,
            onToggleTheme = {},
        )
    }

    @Test fun dark_long_text() = shoot("dark_long_text.png") {
        LumenChatScreen(
            steps = sample() + UiStep(
                "long", StepKind.ASSISTANT, "ASSISTANT", "f00d12", "a long answer",
                (1..40).joinToString("\n") { "line $it — a long assistant answer that has to scroll inside the panel." },
            ),
            input = "",
            busy = false,
            error = null,
            colors = LumenColors.Dark,
            forceOpenIndex = 5,
            ambient = false,
            onToggleTheme = {},
        )
    }

    @Test fun light_long_text() = shoot("light_long_text.png") {
        LumenChatScreen(
            steps = sample() + UiStep(
                "long", StepKind.ASSISTANT, "ASSISTANT", "f00d12", "a long answer",
                (1..40).joinToString("\n") { "line $it — a long assistant answer that has to scroll inside the panel." },
            ),
            input = "",
            busy = false,
            error = null,
            colors = LumenColors.Light,
            forceOpenIndex = 5,
            ambient = false,
            onToggleTheme = {},
        )
    }

    private fun sessionRows(): List<dev.lumen.app.SessionRow> {
        val now = System.currentTimeMillis()
        return listOf(
            dev.lumen.app.SessionRow("s1", "make the timeline a spine", now - 120_000, "the new timeline is a centre spine of dots"),
            dev.lumen.app.SessionRow("s2", "fix the build", now - 3_600_000, "gradle assembleDebug is green now"),
            dev.lumen.app.SessionRow("s3", "new chat", now - 86_400_000, ""),
        )
    }

    @Test fun home_light() = shoot("home_light.png") {
        HomeScreen(
            colors = LumenColors.Light,
            sessions = sessionRows(),
            onNewChat = {}, onOpen = {}, onDelete = {}, onSettings = {},
            onToggleTheme = {},
        )
    }

    @Test fun home_dark() = shoot("home_dark.png") {
        HomeScreen(
            colors = LumenColors.Dark,
            sessions = sessionRows(),
            onNewChat = {}, onOpen = {}, onDelete = {}, onSettings = {},
            onToggleTheme = {},
        )
    }

    @Test fun settings_light() = shoot("settings_light.png") {
        SettingsScreen(
            colors = LumenColors.Light,
            provider = "openrouter",
            model = "openrouter/openrouter/free",
            theme = "system",
            onProvider = {}, onModel = {}, onTheme = {}, onEditKey = {}, onBack = {},
        )
    }

    @Test fun settings_dark() = shoot("settings_dark.png") {
        SettingsScreen(
            colors = LumenColors.Dark,
            provider = "deepseek",
            model = "deepseek/deepseek-flash",
            theme = "dark",
            onProvider = {}, onModel = {}, onTheme = {}, onEditKey = {}, onBack = {},
        )
    }
}
