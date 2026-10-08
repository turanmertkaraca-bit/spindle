package dev.lumen.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.lumen.app.DiagLine
import dev.lumen.app.EditorState
import dev.lumen.app.FileEntry
import dev.lumen.app.FilesState
import dev.lumen.app.LinuxEnvironmentState
import dev.lumen.app.StorageCategory
import dev.lumen.app.StorageReport
import dev.lumen.app.data.KeyStore
import dev.lumen.app.ui.model.StepKind
import dev.lumen.app.ui.model.UiImage
import dev.lumen.app.ui.model.UiStep
import dev.spindle.core.model.FileEdit
import dev.spindle.core.model.RunChanges
import dev.spindle.core.model.SessionId
import dev.spindle.core.model.TodoItem
import dev.spindle.core.model.TodoStatus
import dev.spindle.core.model.Usage
import dev.spindle.core.provider.ModelInfo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the real screens to PNGs so the UI can be inspected without a device.
 * Runs on a plain JVM via Robolectric's native graphics; it draws the decor view
 * directly because Compose's own capture path never sees a draw callback here.
 * The harness is strict: a blank or failed render is written to a `.error.txt`
 * for inspection and then rethrown, failing the test rather than passing quietly.
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

    /** An assistant turn exercising every markdown block the renderer supports. */
    private fun markdownSample(): List<UiStep> = listOf(
        UiStep(
            "u1", StepKind.YOU, "YOU", "a1b2c3",
            "how do I fix the redirect",
            "how do I fix the login redirect loop?",
        ),
        UiStep(
            "a1", StepKind.ASSISTANT, "ASSISTANT", "d1e2f3",
            "set the cookie first",
            buildString {
                append("## The fix\n\n")
                append("Write the **session cookie** *before* calling `res.redirect()`.\n\n")
                append("- set the `Set-Cookie` header first\n")
                append("- then redirect the browser\n")
                append("- keep the original status code\n\n")
                append("```kotlin\nres.setHeader(\"Set-Cookie\", cookie)\nres.redirect(\"/\")\n```")
            },
        ),
    )

    /** Two files, +12/−3, with real diff bodies so the card has something to open. */
    private fun sampleChanges(): RunChanges {
        val sid = SessionId("ses1")
        return RunChanges(
            edits = listOf(
                FileEdit(
                    id = "e1", sessionId = sid,
                    path = "app/src/main/kotlin/dev/lumen/app/ui/RopeLayout.kt",
                    startLine = 12, endLine = 19, added = 8, removed = 3,
                    unifiedDiff = "@@ -12,7 +12,12 @@ class RopeLayout {\n" +
                        "-    val open = false\n" +
                        "+    val open = remember { mutableStateOf(true) }\n" +
                        "+    DisposableEffect(open) { onDispose { /* noop */ } }",
                ),
                FileEdit(
                    id = "e2", sessionId = sid,
                    path = "app/src/main/kotlin/dev/lumen/app/ui/StepMapper.kt",
                    added = 4, removed = 0, created = true,
                    unifiedDiff = "@@ -30,3 +30,7 @@ object StepMapper {\n" +
                        "+    // fold consecutive tool rows into one pocket\n" +
                        "+    val folded = rows.reduceOrNull { a, b -> a }",
                ),
            ),
        )
    }

    /** A session mid-plan: one done, one active, one pending, one dropped. */
    private fun sampleTodos(): List<TodoItem> = listOf(
        TodoItem("t1", "read the layout rule", TodoStatus.DONE),
        TodoItem("t2", "patch the redirect guard", TodoStatus.IN_PROGRESS),
        TodoItem("t3", "run the unit tests", TodoStatus.PENDING),
        TodoItem("t4", "update the changelog", TodoStatus.CANCELLED),
    )

    /** A subagent whose child transcript nests two levels deep. */
    private fun subagentTreeSample(): List<UiStep> = listOf(
        UiStep("u1", StepKind.YOU, "YOU", "a1b2c3", "fix the build", "fix the failing build"),
        UiStep(
            "s1", StepKind.SUBAGENT, "TASK", "b9c0d1", "fix the build", "spawned a build fixer",
            childId = "child1",
            childSteps = listOf(
                UiStep(
                    "c1", StepKind.TOOL, "READ", "c1", "read gradle config", "read gradle config",
                    childSteps = listOf(
                        UiStep("g1", StepKind.TOOL, "GREP", "g1", "grep assembleDebug", "grep assembleDebug"),
                    ),
                ),
                UiStep("c2", StepKind.THINKING, "THINKING", "c2", "the toolchain is stale", "the toolchain is stale"),
                UiStep("c3", StepKind.ASSISTANT, "ASSISTANT", "c3", "bumped the plugin", "bumped the kotlin plugin"),
            ),
        ),
    )

    /** A failed tool with raw output, for the collapsed/expanded card fixtures. */
    private fun toolFixture(): List<UiStep> = listOf(
        UiStep("u1", StepKind.YOU, "YOU", "a1b2c3", "whats your env", "whats your env"),
        UiStep(
            "t1", StepKind.TOOL, "BASH", "e5f607",
            "uname -a",
            "failed: [exit 159] uname -a\n=== System Info ===\nLinux localhost 5.10.0 aarch64\n" +
                "=== Env ===\nSHELL=/bin/sh\nPATH=/usr/bin:/bin",
            failed = true,
        ),
    )

    /** A rendered PNG as base64, so the image fixture decodes to a real thumbnail. */
    private fun pngBase64(): String {
        val bmp = Bitmap.createBitmap(160, 100, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(android.graphics.Color.rgb(70, 110, 200))
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        return android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
    }

    private fun shoot(name: String, content: @Composable () -> Unit) {
        val dir = File("build/screenshots").apply { mkdirs() }
        try {
            compose.setContent(content)
            compose.waitForIdle()
            ScreenshotSupport.shoot(compose.activity, dir, name)
        } catch (t: Throwable) {
            // Keep the evidence next to the screenshots, but never swallow the
            // failure: a blank or failed render must fail the test.
            File(dir, "$name.error.txt").writeText(t.stackTraceToString())
            throw t
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

    @Test fun light_assistant() = chat(LumenColors.Light, "light_assistant.png")

    @Test fun dark_assistant() = chat(LumenColors.Dark, "dark_assistant.png")

    @Test fun ember_light() = chat(LumenColors.EmberLight, "ember_light.png")

    @Test fun ember_dark() = chat(LumenColors.EmberDark, "ember_dark.png")

    @Test fun phosphor_light() = chat(LumenColors.PhosphorLight, "phosphor_light.png")

    @Test fun phosphor_dark() = chat(LumenColors.PhosphorDark, "phosphor_dark.png")

    @Test fun abyss_light() = chat(LumenColors.AbyssLight, "abyss_light.png")

    @Test fun abyss_dark() = chat(LumenColors.AbyssDark, "abyss_dark.png")

    /** Tools at rest: compact collapsed cards inside the transcript. */
    @Test fun light_merged_tools() = chat(LumenColors.Light, "light_merged_tools.png")

    @Test fun dark_subagent() = chat(LumenColors.Dark, "dark_subagent.png")

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
            ambient = false,
            onToggleTheme = {},
        )
    }

    /** A tool card at rest: compact header, no raw output. */
    @Test fun light_tool_collapsed() = shoot("light_tool_collapsed.png") {
        LumenChatScreen(
            steps = toolFixture(), input = "", busy = false, error = null,
            colors = LumenColors.Light, ambient = false, onToggleTheme = {},
        )
    }

    /** The same card tapped open: the bounded raw output is revealed. */
    @Test fun dark_tool_expanded() = shoot("dark_tool_expanded.png") {
        LumenChatScreen(
            steps = toolFixture(), input = "", busy = false, error = null,
            colors = LumenColors.Dark, forceOpenIndex = 1, ambient = false, onToggleTheme = {},
        )
    }

    /** A sent image is shown inside the YOU bubble, not only in the composer chips. */
    @Test fun light_user_image() = shoot("light_user_image.png") {
        LumenChatScreen(
            steps = listOf(
                UiStep(
                    "u1", StepKind.YOU, "YOU", "a1b2c3", "whats your env", "whats your env",
                    images = listOf(UiImage("screenshot.png", "image/png", pngBase64())),
                ),
            ),
            input = "", busy = false, error = null,
            colors = LumenColors.Light, ambient = false, onToggleTheme = {},
        )
    }

    @Test fun key_screen_light() = shoot("key_light.png") {
        KeyScreen(colors = LumenColors.Light, onSubmit = { _, _ -> }, onToggleTheme = {})
    }

    @Test fun key_screen_dark() = shoot("key_dark.png") {
        KeyScreen(colors = LumenColors.Dark, onSubmit = { _, _ -> }, onToggleTheme = {})
    }

    /** GitHub connected (a saved login + repo seeded into the store), both themes. */
    @Test fun github_light() = githubShot("github_light.png", LumenColors.Light)

    @Test fun github_dark() = githubShot("github_dark.png", LumenColors.Dark)

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
                (1..40).joinToString("\n") { "line $it — a long assistant answer rendered fully inline without an inner scroll." },
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
                (1..40).joinToString("\n") { "line $it — a long assistant answer rendered fully inline without an inner scroll." },
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

    /** The markdown renderer: heading, bold/italic, bullets and a code block. */
    @Test fun light_markdown() = shoot("light_markdown.png") {
        LumenChatScreen(
            steps = markdownSample(),
            input = "",
            busy = false,
            error = null,
            colors = LumenColors.Light,
            ambient = false,
            onToggleTheme = {},
            onEditKey = {},
        )
    }

    @Test fun dark_markdown() = shoot("dark_markdown.png") {
        LumenChatScreen(
            steps = markdownSample(),
            input = "",
            busy = false,
            error = null,
            colors = LumenColors.Dark,
            ambient = false,
            onToggleTheme = {},
            onEditKey = {},
        )
    }

    /** A linked file reference inside assistant prose (the peek is not opened). */
    @Test fun light_file_reference() = shoot("light_file_reference.png") {
        LumenChatScreen(
            steps = listOf(
                UiStep("u1", StepKind.YOU, "YOU", "a1b2c3", "where is the fix", "where is the fix?"),
                UiStep(
                    "a1", StepKind.ASSISTANT, "ASSISTANT", "d1e2f3", "the fix is in App.kt",
                    "The fix lands in **src/App.kt:42** — set the cookie before the redirect, " +
                        "then see `src/App.kt:12` for the surrounding context.",
                ),
            ),
            input = "",
            busy = false,
            error = null,
            colors = LumenColors.Light,
            ambient = false,
            onToggleTheme = {},
            onEditKey = {},
            cwd = "/workspace",
            exists = { true },
            onOpenFile = { _, _ -> },
        )
    }

    /** A very long regular message renders fully inline, with no nested scroll. */
    @Test fun light_long_inline_no_scroll() = shoot("light_long_inline.png") {
        LumenChatScreen(
            steps = listOf(
                UiStep("u1", StepKind.YOU, "YOU", "a1b2c3", "summarise", "summarise the whole file"),
                UiStep(
                    "a1", StepKind.ASSISTANT, "ASSISTANT", "d1e2f3", "long answer",
                    (1..60).joinToString("\n") {
                        "line $it — a long regular assistant message rendered inline without an inner scroll region."
                    },
                ),
            ),
            input = "",
            busy = false,
            error = null,
            colors = LumenColors.Light,
            ambient = false,
            onToggleTheme = {},
        )
    }

    @Test fun light_changes_and_usage() = shoot("light_changes.png") {
        LumenChatScreen(
            steps = sample(),
            input = "",
            busy = false,
            error = null,
            colors = LumenColors.Light,
            title = "make the timeline a spine",
            onHome = {},
            ambient = false,
            onToggleTheme = {},
            onEditKey = {},
            usage = Usage(inputTokens = 7_400, outputTokens = 4_900, costUsd = 0.0450),
            changes = sampleChanges(),
        )
    }

    @Test fun dark_changes_and_usage() = shoot("dark_changes.png") {
        LumenChatScreen(
            steps = sample(),
            input = "",
            busy = false,
            error = null,
            colors = LumenColors.Dark,
            title = "make the timeline a spine",
            onHome = {},
            ambient = false,
            onToggleTheme = {},
            onEditKey = {},
            usage = Usage(inputTokens = 7_400, outputTokens = 4_900, costUsd = 0.0450),
            changes = sampleChanges(),
        )
    }

    /** The live todo board pinned above the composer. */
    @Test fun light_todo_board() = shoot("light_todos.png") {
        LumenChatScreen(
            steps = sample(),
            input = "",
            busy = false,
            error = null,
            colors = LumenColors.Light,
            title = "make the timeline a spine",
            onHome = {},
            ambient = false,
            onToggleTheme = {},
            onEditKey = {},
            todos = sampleTodos(),
        )
    }

    @Test fun dark_todo_board() = shoot("dark_todos.png") {
        LumenChatScreen(
            steps = sample(),
            input = "",
            busy = false,
            error = null,
            colors = LumenColors.Dark,
            title = "make the timeline a spine",
            onHome = {},
            ambient = false,
            onToggleTheme = {},
            onEditKey = {},
            todos = sampleTodos(),
        )
    }

    /** A subagent card tapped open, revealing its nested call tree. */
    @Test fun light_subagent_tree() = shoot("light_subagent_tree.png") {
        LumenChatScreen(
            steps = subagentTreeSample(),
            input = "",
            busy = false,
            error = null,
            colors = LumenColors.Light,
            title = "fix the build",
            onHome = {},
            forceOpenIndex = 1,
            ambient = false,
            onToggleTheme = {},
            onEditKey = {},
        )
    }

    @Test fun dark_subagent_tree() = shoot("dark_subagent_tree.png") {
        LumenChatScreen(
            steps = subagentTreeSample(),
            input = "",
            busy = false,
            error = null,
            colors = LumenColors.Dark,
            title = "fix the build",
            onHome = {},
            forceOpenIndex = 1,
            ambient = false,
            onToggleTheme = {},
            onEditKey = {},
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

    private fun modelPickerShot(name: String, colors: LumenColors) = shoot(name) {
        ModelPickerScreen(
            colors = colors,
            provider = "deepseek",
            models = listOf(
                ModelInfo(
                    "deepseek", "deepseek-flash", label = "DeepSeek Flash",
                    contextWindow = 1_000_000, supportsReasoning = true,
                    inputCostPerM = 0.28, outputCostPerM = 0.42,
                ),
                ModelInfo(
                    "deepseek", "deepseek-v4-pro", label = "DeepSeek V4 Pro",
                    contextWindow = 1_000_000, supportsReasoning = true,
                    inputCostPerM = 0.55, outputCostPerM = 2.19,
                ),
                ModelInfo("deepseek", "deepseek-future", label = "DeepSeek Future", contextWindow = 500_000),
            ),
            selected = "deepseek/deepseek-flash",
            refreshing = false,
            error = null,
            onProvider = {}, onSelect = {}, onRefresh = {}, onBack = {},
        )
    }

    @Test fun models_light() = modelPickerShot("models_light.png", LumenColors.Light)

    @Test fun models_dark() = modelPickerShot("models_dark.png", LumenColors.Dark)

    /**
     * The in-chat quick settings sheet over a populated transcript: provider,
     * model, theme and budget chips plus the ask-before-tools switch and the
     * key/full-settings/close footer, in both themes. Rendering the whole chat
     * with the sheet over it is best-effort under Robolectric; the strict `shoot`
     * turns any blank or failed render into a test failure (after writing the
     * `.error.txt` evidence).
     */
    private fun quickSettingsShot(name: String, colors: LumenColors) = shoot(name) {
        LumenChatScreen(
            steps = sample(),
            input = "",
            busy = false,
            error = null,
            colors = colors,
            title = "make the timeline a spine",
            onHome = {},
            ambient = false,
            onToggleTheme = {},
            onEditKey = {},
            quickSettings = true,
            onCloseQuickSettings = {},
            provider = "deepseek",
            model = "deepseek/deepseek-flash",
            theme = "dark",
            budgetUsd = 2.0,
            askBeforeTools = true,
            onProvider = {},
            onModelSelect = {},
            onTheme = {},
            onMaxCost = {},
            onAskBeforeTools = {},
            onOpenFullSettings = {},
        )
    }

    @Test fun light_quick_settings() = quickSettingsShot("light_quick_settings.png", LumenColors.Light)

    @Test fun dark_quick_settings() = quickSettingsShot("dark_quick_settings.png", LumenColors.Dark)

    /** A populated folder listing, plus an html file so the canvas affordance shows. */
    private fun filesSample(): FilesState = FilesState(
        dir = "",
        entries = listOf(
            FileEntry(name = "src", path = "src", isDir = true, size = 0, modified = 0),
            FileEntry(name = "README.md", path = "README.md", isDir = false, size = 12_400, modified = 0),
            FileEntry(name = "index.html", path = "index.html", isDir = false, size = 3_200, modified = 0),
        ),
    )

    private fun storageSample(): StorageReport = StorageReport(
        total = 812L * 1024L * 1024L,
        categories = listOf(
            StorageCategory("workspace", 640L * 1024L * 1024L, clearable = false),
            StorageCategory("cache", 120L * 1024L * 1024L, clearable = true),
            StorageCategory("logs", 52L * 1024L * 1024L, clearable = true),
        ),
        scanning = false,
    )

    private fun filesShot(name: String, colors: LumenColors, files: FilesState?) = shoot(name) {
        FilesScreen(
            colors = colors, files = files, editor = null,
            onOpenDir = {}, onUp = {}, onEnter = {}, onSaveFile = { _, _ -> },
            onCloseEditor = {}, onCreateFile = { _, _ -> }, onCreateDir = { _, _ -> },
            onRename = { _, _ -> }, onDelete = { _, _ -> }, onBack = {},
        )
    }

    private fun filesEditorShot(name: String, colors: LumenColors) = shoot(name) {
        FilesScreen(
            colors = colors, files = filesSample(),
            editor = EditorState(path = "README.md", lines = listOf("# Lumen", "", "a native agent app")),
            onOpenDir = {}, onUp = {}, onEnter = {}, onSaveFile = { _, _ -> },
            onCloseEditor = {}, onCreateFile = { _, _ -> }, onCreateDir = { _, _ -> },
            onRename = { _, _ -> }, onDelete = { _, _ -> }, onBack = {},
        )
    }

    private fun terminalShot(name: String, colors: LumenColors, lines: List<String>, running: Boolean) = shoot(name) {
        TerminalScreen(
            colors = colors, lines = lines, running = running, error = null,
            onSend = {}, onInterrupt = {}, onClear = {}, onBack = {},
        )
    }

    private fun canvasShot(name: String, colors: LumenColors, html: String) = shoot(name) {
        CanvasScreen(html = html, colors = colors, sourceName = "demo.html", onBack = {})
    }

    private fun diagnosticsShot(name: String, colors: LumenColors, diag: List<DiagLine>) = shoot(name) {
        DiagnosticsScreen(
            colors = colors, diag = diag,
            linux = LinuxEnvironmentState(alpineReady = true, debianReady = true, debianActive = true),
            onInstallDebian = {}, onRefreshLinux = {}, onClear = {}, onBack = {},
        )
    }

    private fun storageShot(name: String, colors: LumenColors, storage: StorageReport?) = shoot(name) {
        StorageScreen(
            colors = colors, storage = storage,
            onRescan = {}, onClear = {}, onClearAll = {}, onBack = {},
        )
    }

    /**
     * The GitHub screen owns its [KeyStore], so seed a (non-secret) login + repo
     * before rendering to show the connected status card. The screen reads only
     * `colors`/`onBack`, per its real signature. Seeding runs inside `shoot`, so
     * any failure is written to `.error.txt` and rethrown to fail the test.
     */
    private fun githubShot(name: String, colors: LumenColors) = shoot(name) {
        val store = KeyStore(compose.activity)
        store.githubLogin = "octocat"
        store.githubRepo = "octocat/Hello-World"
        GitHubScreen(colors = colors, onBack = {})
    }

    private val canvasHtml = """
        <html><body style="margin:0;background:#101018;color:#eaeaff;font-family:monospace;padding:24px">
        <h1>lumen canvas</h1><p>a page the agent wrote, rendered in the sandbox.</p>
        </body></html>
    """.trimIndent()

    private val diagLines = listOf(
        DiagLine(1_700_000_000_000, "run start: make the timeline a spine"),
        DiagLine(1_700_000_001_200, "tool: read TimelineLayout.kt"),
        DiagLine(1_700_000_004_400, "run end: ok"),
    )

    /** Files: populated, an open editor, empty and loading, in both themes. */
    @Test fun files_light() = filesShot("files_light.png", LumenColors.Light, filesSample())
    @Test fun files_dark() = filesShot("files_dark.png", LumenColors.Dark, filesSample())
    @Test fun files_editor_light() = filesEditorShot("files_editor_light.png", LumenColors.Light)
    @Test fun files_editor_dark() = filesEditorShot("files_editor_dark.png", LumenColors.Dark)
    @Test fun files_empty_light() = filesShot("files_empty_light.png", LumenColors.Light, FilesState("", emptyList()))
    @Test fun files_empty_dark() = filesShot("files_empty_dark.png", LumenColors.Dark, FilesState("", emptyList()))
    @Test fun files_loading_light() = filesShot("files_loading_light.png", LumenColors.Light, null)
    @Test fun files_loading_dark() = filesShot("files_loading_dark.png", LumenColors.Dark, null)

    /** Terminal: live output plus the empty/starting states, both themes. */
    @Test fun terminal_light() = terminalShot(
        "terminal_light.png", LumenColors.Light,
        listOf("lumen ready\n", "$ echo hi\n", "hi\n"), running = true,
    )
    @Test fun terminal_dark() = terminalShot(
        "terminal_dark.png", LumenColors.Dark,
        listOf("lumen ready\n", "$ echo hi\n", "hi\n"), running = true,
    )
    @Test fun terminal_empty_light() = terminalShot("terminal_empty_light.png", LumenColors.Light, emptyList(), running = true)
    @Test fun terminal_empty_dark() = terminalShot("terminal_empty_dark.png", LumenColors.Dark, emptyList(), running = false)

    /** Canvas: a rendered page and the empty state, both themes. */
    @Test fun canvas_light() = canvasShot("canvas_light.png", LumenColors.Light, canvasHtml)
    @Test fun canvas_dark() = canvasShot("canvas_dark.png", LumenColors.Dark, canvasHtml)
    @Test fun canvas_empty_light() = canvasShot("canvas_empty_light.png", LumenColors.Light, "")
    @Test fun canvas_empty_dark() = canvasShot("canvas_empty_dark.png", LumenColors.Dark, "")

    /** Diagnostics: a populated log and the empty log, both themes. */
    @Test fun diagnostics_light() = diagnosticsShot("diagnostics_light.png", LumenColors.Light, diagLines)
    @Test fun diagnostics_dark() = diagnosticsShot("diagnostics_dark.png", LumenColors.Dark, diagLines)
    @Test fun diagnostics_empty_light() = diagnosticsShot("diagnostics_empty_light.png", LumenColors.Light, emptyList())
    @Test fun diagnostics_empty_dark() = diagnosticsShot("diagnostics_empty_dark.png", LumenColors.Dark, emptyList())

    /** Storage: a measured breakdown and the empty scan, both themes. */
    @Test fun storage_light() = storageShot("storage_light.png", LumenColors.Light, storageSample())
    @Test fun storage_dark() = storageShot("storage_dark.png", LumenColors.Dark, storageSample())
    @Test fun storage_empty_light() = storageShot("storage_empty_light.png", LumenColors.Light, StorageReport(0L, emptyList(), scanning = false))
    @Test fun storage_empty_dark() = storageShot("storage_empty_dark.png", LumenColors.Dark, null)

    /** First-run onboarding wizard at step one, both themes. */
    @Test fun onboarding_light() = onboardingShot("onboarding_light.png", LumenColors.Light)

    @Test fun onboarding_dark() = onboardingShot("onboarding_dark.png", LumenColors.Dark)

    /** The launch maintenance splash, both themes. */
    @Test fun boot_light() = bootShot("boot_light.png", LumenColors.Light)

    @Test fun boot_dark() = bootShot("boot_dark.png", LumenColors.Dark)

    private fun onboardingShot(name: String, colors: LumenColors) = shoot(name) {
        OnboardingScreen(
            colors = colors,
            linux = LinuxEnvironmentState(),
            onInstallDebian = {},
            onRequestBattery = {},
            onRequestDownloads = {},
            onAddKey = {},
            onFinish = {},
        )
    }

    private fun bootShot(name: String, colors: LumenColors) = shoot(name) {
        BootScreen(colors = colors, message = "pruning the sandbox\u2026")
    }
}
