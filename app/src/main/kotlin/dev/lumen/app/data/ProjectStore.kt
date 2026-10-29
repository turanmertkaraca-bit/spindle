package dev.lumen.app.data

import android.content.Context
import android.util.Log
import java.io.File
import java.nio.file.Files

/** One isolated project folder. [id] is the folder name; [name] is what the UI shows. */
data class Project(
    val id: String,
    val name: String,
    val dir: File,
)

/**
 * Owns the project folders the agent is allowed to work in. Every project is a
 * direct child of `filesDir/projects`, and the active project's folder becomes
 * the agent's working directory, so the tools inside one project cannot reach a
 * sibling project (the tool layer already refuses paths outside the cwd).
 *
 * The first run migrates the old single `filesDir/workspace` tree into
 * `projects/playground`, so an existing install keeps its files.
 *
 * The active project id is persisted in its own preferences file; the display
 * name lives in a hidden `.project` marker inside the folder so a rename never
 * has to move bytes.
 */
class ProjectStore(context: Context) {

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val legacyRoot = File(app.filesDir, LEGACY_DIR)

    /** The parent of every project folder. */
    val root: File = File(app.filesDir, ROOT_DIR)

    /**
     * Create the root, migrate a legacy workspace once, and guarantee that at
     * least one project exists and is active. Cheap after the first call.
     */
    fun ensure(): Project {
        root.mkdirs()
        if (!prefs.getBoolean(KEY_MIGRATED, false)) {
            migrateLegacy()
            prefs.edit().putBoolean(KEY_MIGRATED, true).apply()
        }
        val current = activeOrNull()
        if (current != null) return current
        val fallback = list().firstOrNull() ?: create(DEFAULT_NAME)
        checkNotNull(fallback) { "cannot create the default project folder" }
        prefs.edit().putString(KEY_ACTIVE, fallback.id).apply()
        return fallback
    }

    /** Every project, name-ascending. Never throws. */
    fun list(): List<Project> =
        root.listFiles()
            ?.asSequence()
            ?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.map { Project(id = it.name, name = nameFor(it.name), dir = it) }
            ?.sortedWith(compareBy({ it.name.lowercase() }, { it.id }))
            ?.toList()
            .orEmpty()

    /** The persisted active project when its folder still exists, else null. */
    fun activeOrNull(): Project? {
        val id = prefs.getString(KEY_ACTIVE, null) ?: return null
        // The pref is restored/tampered data on this read path, so it gets the
        // same gate as setActive/delete: a value like ".." would otherwise
        // resolve to filesDir and be adopted as the agent's working directory.
        if (!isSafeId(id)) {
            Log.w(TAG, "ignoring unsafe active project id")
            return null
        }
        val dir = File(root, id)
        if (!dir.isDirectory) return null
        return Project(id = id, name = nameFor(id), dir = dir)
    }

    /** Make [id] active. Returns null for an unknown id. */
    fun setActive(id: String): Project? {
        if (!isSafeId(id)) return null
        val dir = File(root, id)
        if (!dir.isDirectory) return null
        prefs.edit().putString(KEY_ACTIVE, id).apply()
        return Project(id = id, name = nameFor(id), dir = dir)
    }

    /**
     * Create a project folder for [rawName]. The folder id is a unique slug;
     * the typed name is kept as the display name. Returns null when the name is
     * blank or the folder cannot be created.
     */
    fun create(rawName: String): Project? {
        val name = rawName.trim().replace(Regex("\\s+"), " ").take(MAX_NAME)
        if (name.isBlank()) return null
        // Only ids were de-duplicated, so two "Work" rows were indistinguishable
        // in the picker. Reject a duplicate display name outright rather than
        // silently creating a twin the user cannot tell apart.
        if (nameTaken(name, exceptId = null)) {
            Log.w(TAG, "refusing to create a duplicate project name")
            return null
        }
        root.mkdirs()
        val id = uniqueId(slug(name))
        val dir = File(root, id)
        if (!dir.mkdirs()) return null
        // The display name lives in prefs, never as a dot-file in the project:
        // the Files view and the agent must only ever see the user's own files.
        prefs.edit().putString(nameKey(id), name).apply()
        return Project(id = id, name = name, dir = dir)
    }

    /**
     * Delete one project folder and everything in it. Refuses the last
     * remaining project (the app always needs somewhere to work) and any
     * `id` that is not a direct child of [root].
     */
    fun delete(id: String): Boolean {
        if (!isSafeId(id)) return false
        val all = list()
        if (all.size <= 1) return false
        if (all.none { it.id == id }) return false
        val dir = File(root, id)
        if (!dir.isDirectory) return false
        val canonicalRoot = runCatching { root.canonicalFile }.getOrElse { root.absoluteFile }
        val canonicalDir = runCatching { dir.canonicalFile }.getOrElse { dir.absoluteFile }
        if (canonicalDir.parentFile != canonicalRoot) return false

        // Delete the files first: the prefs are the record of what exists, so
        // rewriting them before a failed delete would make the project look
        // gone while its folder (and the user's files) stayed behind. Nothing
        // is mutated until the tree is actually gone.
        if (!deleteTree(dir)) return false
        if (prefs.getString(KEY_ACTIVE, null) == id) {
            val next = all.first { it.id != id }
            prefs.edit().putString(KEY_ACTIVE, next.id).apply()
        }
        prefs.edit().remove(nameKey(id)).apply()
        return true
    }

    /**
     * Rename the project [id] to [rawName]. The folder id never changes, so a
     * rename is a single preference write and no files move. Returns the
     * updated project, or null for an unknown id or a rejected name.
     */
    fun rename(id: String, rawName: String): Project? {
        if (!isSafeId(id)) return null
        val dir = File(root, id)
        if (!dir.isDirectory) return null
        val name = rawName.trim().replace(Regex("\\s+"), " ").take(MAX_NAME)
        if (name.isBlank()) return null
        if (nameTaken(name, exceptId = id)) {
            Log.w(TAG, "refusing to rename to a duplicate project name")
            return null
        }
        prefs.edit().putString(nameKey(id), name).apply()
        return Project(id = id, name = name, dir = dir)
    }

    // ------------------------------------------------------------ internals

    /**
     * True when another project already uses [name], compared case-insensitively
     * so "Work" and "work" cannot both exist. [exceptId] is the project being
     * renamed, whose own current name must not count against it.
     */
    private fun nameTaken(name: String, exceptId: String?): Boolean {
        val target = name.lowercase()
        return list().any { it.id != exceptId && it.name.lowercase() == target }
    }

    /** Move the pre-project-folder workspace into the default project. */
    private fun migrateLegacy() {
        val children = legacyRoot.listFiles()?.takeIf { it.isNotEmpty() } ?: return
        val target = File(root, DEFAULT_ID)
        if (target.isDirectory && target.listFiles()?.isNotEmpty() == true) return
        target.mkdirs()
        for (child in children) {
            val dest = File(target, child.name)
            if (dest.exists()) continue
            val moved = runCatching { child.renameTo(dest) }.getOrDefault(false)
            if (moved) continue
            // A cross-volume or busy rename falls back to a copy; only remove the
            // original once every byte landed, so a failed migration never loses
            // the user's project.
            val copied = runCatching { child.copyRecursively(dest, overwrite = false) }.getOrDefault(false)
            if (copied) runCatching { child.deleteRecursively() }
        }
    }

    private fun nameFor(id: String): String =
        prefs.getString(nameKey(id), null)?.takeIf { it.isNotBlank() } ?: id

    private fun nameKey(id: String): String = "name:$id"

    /** A folder-safe lowercase slug; never blank, never `.`/`..`. */
    private fun slug(name: String): String {
        val base = name.lowercase()
            .replace(Regex("[^a-z0-9._-]+"), "-")
            .trim('-', '.', '_')
            .take(MAX_ID)
        return base.ifBlank { "project" }
    }

    private fun uniqueId(base: String): String {
        var candidate = base
        var n = 2
        while (File(root, candidate).exists()) {
            candidate = "$base-$n"
            n++
        }
        return candidate
    }

    private fun isSafeId(id: String): Boolean =
        id.isNotBlank() && !id.contains('/') && !id.contains('\\') && id != "." && id != ".."

    /** Recursive delete that never follows symlinks; true when the root is gone. */
    private fun deleteTree(file: File): Boolean = try {
        if (Files.isSymbolicLink(file.toPath())) {
            file.delete()
        } else if (file.isDirectory) {
            file.listFiles()?.forEach { deleteTree(it) }
            file.delete() && !file.exists()
        } else {
            file.delete() && !file.exists()
        }
    } catch (t: Throwable) {
        false
    }

    companion object {
        private const val TAG = "ProjectStore"
        private const val PREFS = "lumen.projects"
        private const val KEY_ACTIVE = "activeProject"
        private const val KEY_MIGRATED = "workspaceMigrated"
        private const val ROOT_DIR = "projects"
        private const val LEGACY_DIR = "workspace"
        const val DEFAULT_NAME = "playground"
        private const val DEFAULT_ID = "playground"
        private const val MAX_NAME = 48
        private const val MAX_ID = 48
    }
}
