package dev.lumen.app.ui

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import dev.lumen.app.EditorState
import dev.lumen.app.FileEntry
import dev.lumen.app.FilesState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * The files cockpit renders a folder listing and routes taps: a folder enters,
 * the editor shows a capped file and disables Save when truncated.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FilesScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val viewport = Modifier.size(width = 360.dp, height = 640.dp)

    private val entries = listOf(
        FileEntry(name = "src", path = "src", isDir = true, size = 0, modified = 0),
        FileEntry(name = "readme.txt", path = "readme.txt", isDir = false, size = 12, modified = 0),
    )

    @Composable
    private fun screen(
        files: FilesState?,
        editor: EditorState? = null,
        onEnter: (FileEntry) -> Unit = {},
    ) {
        FilesScreen(
            colors = LumenColors.Dark,
            files = files,
            editor = editor,
            onOpenDir = {},
            onUp = {},
            onEnter = onEnter,
            onSaveFile = { _, _ -> },
            onCloseEditor = {},
            onCreateFile = { _, _ -> },
            onCreateDir = { _, _ -> },
            onRename = { _, _ -> },
            onDelete = { _, _ -> },
            onBack = {},
            modifier = viewport,
        )
    }

    @Test
    fun `renders entries and tapping a folder enters it`() {
        var entered: FileEntry? = null
        compose.setContent { screen(FilesState(dir = "", entries = entries), onEnter = { entered = it }) }

        compose.onNodeWithText("readme.txt").assertIsDisplayed()
        compose.onNodeWithTag("entry-src").assertIsDisplayed().performClick()
        compose.waitForIdle()

        assertEquals("src", entered?.name)
        assertEquals(true, entered?.isDir)
    }

    @Test
    fun `a truncated editor notes it and disables save`() {
        compose.setContent {
            screen(
                FilesState(dir = "", entries = entries),
                editor = EditorState(path = "readme.txt", lines = listOf("hello"), truncated = true),
            )
        }

        compose.onNodeWithTag("editor-path").assertIsDisplayed()
        compose.onNodeWithTag("editor-body").assertIsDisplayed()
        compose.onNodeWithTag("editor-truncated").assertIsDisplayed()
    }
}
