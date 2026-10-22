package dev.lumen.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Project folders own the agent's workspace. These tests pin the pieces that
 * matter: one folder per project, a stable active choice, migration of the old
 * single workspace, and deletion that never removes the last project or a path
 * outside the projects root.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProjectStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun freshStore(): ProjectStore {
        val root = File(context.filesDir, "projects")
        root.deleteRecursively()
        File(context.filesDir, "workspace").deleteRecursively()
        context.getSharedPreferences("lumen.projects", Context.MODE_PRIVATE)
            .edit().clear().commit()
        return ProjectStore(context)
    }

    @Test
    fun `ensure creates and persists the default project`() {
        val store = freshStore()

        val first = store.ensure()
        assertEquals(ProjectStore.DEFAULT_NAME, first.name)
        assertTrue(first.dir.isDirectory)
        assertEquals(first.id, store.activeOrNull()?.id)

        // A second call is stable: it does not mint a new folder.
        val again = ProjectStore(context).ensure()
        assertEquals(first.id, again.id)
        assertEquals(1, store.list().size)
    }

    @Test
    fun `create makes a unique folder and keeps the typed display name`() {
        val store = freshStore()
        store.ensure()

        val a = assertNotNull(store.create("My Project"))
        val b = assertNotNull(store.create("My Project"))

        assertEquals("My Project", a.name)
        assertEquals("my-project", a.id)
        assertEquals("my-project-2", b.id)
        assertTrue(a.dir.isDirectory)
        assertTrue(b.dir.isDirectory)
        assertEquals("My Project", store.list().first { it.id == b.id }.name)
    }

    @Test
    fun `blank names are refused`() {
        val store = freshStore()
        store.ensure()
        assertNull(store.create("   "))
    }

    @Test
    fun `setActive only accepts an existing direct child`() {
        val store = freshStore()
        store.ensure()
        val project = assertNotNull(store.create("target"))

        assertEquals(project.id, store.setActive(project.id)?.id)
        assertEquals(project.id, store.activeOrNull()?.id)
        assertNull(store.setActive("nope"))
        assertNull(store.setActive("../escape"))
    }

    @Test
    fun `delete removes a project folder but never the last one`() {
        val store = freshStore()
        val first = store.ensure()
        val second = assertNotNull(store.create("second"))
        File(second.dir, "nested/file.txt").apply { parentFile?.mkdirs() }.writeText("x")

        assertTrue(store.delete(second.id))
        assertFalse(second.dir.exists())
        assertEquals(listOf(first.id), store.list().map { it.id })

        // The last project is the agent's home; deleting it is refused.
        assertFalse(store.delete(first.id))
        assertTrue(first.dir.isDirectory)
    }

    @Test
    fun `deleting the active project moves the active choice first`() {
        val store = freshStore()
        val first = store.ensure()
        val second = assertNotNull(store.create("second"))
        store.setActive(second.id)

        assertTrue(store.delete(second.id))
        // delete() repoints the pref before removing the folder, so the app is
        // never left with an active project that does not exist.
        assertEquals(first.id, store.activeOrNull()?.id)
    }

    @Test
    fun `a legacy workspace is migrated once into the default project`() {
        val store = freshStore()
        val legacy = File(context.filesDir, "workspace").apply { mkdirs() }
        File(legacy, "src/main.kt").apply { parentFile?.mkdirs() }.writeText("fun main() {}\n")

        val project = store.ensure()

        assertTrue(File(project.dir, "src/main.kt").isFile)
        assertEquals("fun main() {}\n", File(project.dir, "src/main.kt").readText())
        // The legacy file is moved, not copied, so no duplicate tree is left.
        assertFalse(File(legacy, "src/main.kt").exists())
    }
}
