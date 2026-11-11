package dev.lumen.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.lumen.app.data.KeyStore
import dev.lumen.app.data.ProjectStore
import dev.spindle.core.model.SessionId
import dev.spindle.core.store.InMemorySessionStore
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
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
 * The project boundary: switching projects retargets every workspace-relative
 * operation, a path that resolves into a SIBLING project is refused, and the
 * last project can never be deleted.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProjectWorkspaceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var projects: ProjectStore
    private lateinit var vm: ChatViewModel
    private lateinit var store: InMemorySessionStore
    private lateinit var alpha: dev.lumen.app.data.Project
    private lateinit var beta: dev.lumen.app.data.Project

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val root = File(context.filesDir, "projects")
        root.deleteRecursively()
        context.getSharedPreferences("lumen.projects", Context.MODE_PRIVATE).edit().clear().commit()
        projects = ProjectStore(context)
        alpha = projects.ensure()
        beta = assertNotNull(projects.create("beta"))
        projects.setActive(alpha.id)
        File(alpha.dir, "alpha.txt").writeText("alpha")
        File(beta.dir, "beta.txt").writeText("beta")
        store = InMemorySessionStore()
        vm = ChatViewModel(
            alpha.dir.toPath(),
            KeyStore(context),
            store,
            projectStore = projects,
            io = Dispatchers.Unconfined,
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `the active project is the initial workspace`() {
        assertEquals(alpha.dir.toPath().toString(), vm.workspacePath)
        assertEquals(alpha.name, vm.state.value.projectName)

        vm.openFiles()
        assertEquals(listOf("alpha.txt"), vm.state.value.files?.entries?.map { it.name })
        assertTrue(vm.fileExists("alpha.txt"))
        // A sibling project is outside the active root even with a relative climb.
        assertFalse(vm.fileExists("../${beta.id}/beta.txt"))
    }

    @Test
    fun `switching projects retargets the files view and the agent cwd`() {
        vm.switchProject(beta.id)

        assertEquals(beta.dir.toPath().toString(), vm.workspacePath)
        assertEquals(beta.name, vm.state.value.projectName)
        assertTrue(vm.state.value.projects.first { it.active }.id == beta.id)

        vm.openFiles()
        assertEquals(listOf("beta.txt"), vm.state.value.files?.entries?.map { it.name })
        assertTrue(vm.fileExists("beta.txt"))
        assertFalse(vm.fileExists("../${alpha.id}/alpha.txt"))
    }

    @Test
    fun `creating a project makes it the active isolated folder`() {
        vm.createProject("gamma")

        assertEquals("gamma", vm.state.value.projectName)
        assertTrue(vm.workspacePath.endsWith("/gamma"))
        assertTrue(File(vm.workspacePath).isDirectory)
        assertTrue(vm.state.value.projects.any { it.name == "gamma" && it.active })
    }

    @Test
    fun `deleting a project removes its folder and never the last one`() {
        vm.deleteProject(beta.id)

        assertFalse(beta.dir.exists())
        assertTrue(alpha.dir.isDirectory)

        vm.deleteProject(alpha.id)
        assertTrue(alpha.dir.isDirectory)
        assertEquals("keep at least one project", vm.state.value.error)
    }

    @Test
    fun `deleting the active project falls back to another project`() {
        vm.switchProject(beta.id)
        vm.deleteProject(beta.id)

        assertFalse(beta.dir.exists())
        assertEquals(alpha.id, vm.state.value.projectId)
        assertEquals(alpha.dir.toPath().toString(), vm.workspacePath)
    }
    /**
     * A new chat must be filed under the project it was started in.
     *
     * `newChat` set the cwd but not `projectId`, so every session was created
     * UNFILED: it never appeared under its project, the dashboard's "N sessions"
     * and resume row stayed empty, and filtering by project showed nothing even
     * though the run's tools were operating inside that project.
     */
    @Test
    fun `a new chat is filed under the active project`() = runTest {
        vm.newChat()

        val sid = vm.state.value.currentSessionId
        assertNotNull(sid, "newChat must open a session")
        val persisted = store.session(SessionId(sid!!))
        assertNotNull(persisted, "the session must exist in the store")
        assertEquals(
            alpha.id,
            persisted.projectId,
            "a new chat belongs to the active project, not the unfiled inbox",
        )
    }

}