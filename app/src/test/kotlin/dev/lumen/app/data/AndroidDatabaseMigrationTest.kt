package dev.lumen.app.data

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import dev.spindle.core.model.Session
import dev.spindle.core.model.SessionId
import dev.spindle.core.model.SessionState
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The Android store upgrades old on-disk databases with versioned, transactional
 * migrations and quarantines a file it cannot trust, rather than crash-looping.
 * Robolectric gives a working SQLite and a real app data directory.
 *
 * A plain [Application] is used because the production [dev.lumen.app.LumenApp]
 * launches its retention janitor at startup, which would open and migrate the
 * very file these tests hand-craft before the test body runs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AndroidDatabaseMigrationTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun dbFile(): File = context.getDatabasePath("lumen.db")

    private fun openRaw(): SQLiteDatabase = context.openOrCreateDatabase("lumen.db", Context.MODE_PRIVATE, null)

    @Test
    fun `versioned migration upgrades an old schema`() = runTest {
        openRaw().use { raw ->
            raw.execSQL(
                "CREATE TABLE sessions(id TEXT PRIMARY KEY, title TEXT, cwd TEXT, created_at INTEGER, " +
                    "updated_at INTEGER, model TEXT, provider_id TEXT, agent TEXT, parent_id TEXT)",
            )
            raw.execSQL(
                "CREATE TABLE messages(id TEXT PRIMARY KEY, session_id TEXT, role TEXT, created_at INTEGER, " +
                    "model TEXT, provider_id TEXT, agent TEXT, usage TEXT, finish TEXT, error TEXT, seq INTEGER)",
            )
            raw.execSQL("INSERT INTO sessions VALUES('s1','old','/w',1,2,NULL,NULL,'build',NULL)")
            // Two rows share seq 1: the unique-index migration must renumber them
            // while preserving their order.
            raw.execSQL("INSERT INTO messages VALUES('m1','s1','USER',1,NULL,NULL,NULL,NULL,NULL,NULL,1)")
            raw.execSQL("INSERT INTO messages VALUES('m2','s1','ASSISTANT',2,NULL,NULL,NULL,NULL,NULL,NULL,1)")
        }

        AndroidSessionStore(context).use { store ->
            val session = store.session(SessionId("s1"))
            assertNotNull(session)
            assertEquals(SessionState.IDLE, session.state, "migration 1 adds the newer session columns")
            assertEquals(listOf("m1", "m2"), store.messages(SessionId("s1")).map { it.id.value })
        }

        openRaw().use { raw ->
            assertEquals(4, raw.userVersion(), "the version gate records the current schema")
            assertTrue(raw.hasColumn("snapshots", "sequence"), "migration 3 adds the snapshot sequence")
            assertTrue(raw.isUniqueIndex("messages", "idx_msg"), "migration 2 makes (session_id, seq) unique")
        }
    }

    @Test
    fun `migration 4 content-addresses duplicate snapshot bodies`() = runTest {
        openRaw().use { raw ->
            raw.execSQL(
                "CREATE TABLE snapshots(id TEXT NOT NULL UNIQUE, session_id TEXT, path TEXT, content TEXT, " +
                    "sha256 TEXT, created_at INTEGER, sequence INTEGER PRIMARY KEY AUTOINCREMENT)",
            )
            raw.execSQL("CREATE INDEX idx_snap ON snapshots(session_id, path)")
            // Two rows share body "dup" (and its hash); a third is distinct.
            raw.execSQL(
                "INSERT INTO snapshots(id, session_id, path, content, sha256, created_at) " +
                    "VALUES('s1','sess','a.kt','dup','h1',1)",
            )
            raw.execSQL(
                "INSERT INTO snapshots(id, session_id, path, content, sha256, created_at) " +
                    "VALUES('s2','sess','b.kt','dup','h1',2)",
            )
            raw.execSQL(
                "INSERT INTO snapshots(id, session_id, path, content, sha256, created_at) " +
                    "VALUES('s3','sess','c.kt','other','h2',3)",
            )
            raw.rawQuery("PRAGMA user_version = 3", null).use { it.moveToFirst() }
        }

        AndroidSnapshotStore(context).use { store ->
            val rows = store.forSession(SessionId("sess"))
            assertEquals(listOf("s1", "s2", "s3"), rows.map { it.id }, "every row survives in sequence order")
            assertEquals(listOf("dup", "dup", "other"), rows.map { it.content }, "content resolves through the join")
        }

        openRaw().use { raw ->
            assertEquals(4, raw.userVersion())
            assertTrue(raw.hasColumn("snapshots", "sequence"), "the sequence column is preserved")
            assertTrue(!raw.hasColumn("snapshots", "content"), "the content column is moved to snapshot_blobs")
            assertEquals(2, raw.countBlobs(), "duplicate bodies collapse to one blob")
            val sequences = raw.rawQuery(
                "SELECT id FROM snapshots ORDER BY sequence",
                null,
            ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
            assertEquals(listOf("s1", "s2", "s3"), sequences, "sequence values are preserved exactly")
        }
    }

    @Test
    fun `a corrupt database is quarantined and recreated`() = runTest {
        // Robolectric's SQLite tolerates header damage, so the integrity verdict
        // is stubbed to fail here. This test pins OUR recovery logic (a failed
        // check => quarantine + recreate); AndroidDatabase's real default still
        // uses `PRAGMA quick_check`.
        dbFile().parentFile?.mkdirs()

        AndroidSessionStore(context).use { it.createSession(Session(SessionId("seed"), "t", "/w", 0, 0)) }

        val database = AndroidDatabase(context) { false }
        AndroidSessionStore(database).use { store ->
            assertTrue(store.sessions().isEmpty(), "the recreated database starts empty")
            store.createSession(Session(SessionId("fresh"), "t", "/w", 0, 0))
            assertEquals(1, store.sessions().size, "the app keeps working after quarantine")
        }

        val quarantined = dbFile().parentFile?.listFiles()
            ?.filter { it.name.startsWith("lumen.db.corrupt-") }
            .orEmpty()
        assertTrue(quarantined.isNotEmpty(), "the unusable file is kept aside as .corrupt-<ts>")
        assertTrue(dbFile().exists(), "a fresh database replaces it")
    }

    private fun SQLiteDatabase.userVersion(): Int =
        rawQuery("PRAGMA user_version", null).use { c -> if (c.moveToFirst()) c.getInt(0) else -1 }

    private fun SQLiteDatabase.hasColumn(table: String, column: String): Boolean =
        rawQuery("PRAGMA table_info($table)", null).use { c ->
            val name = c.getColumnIndexOrThrow("name")
            var found = false
            while (c.moveToNext()) if (c.getString(name) == column) found = true
            found
        }

    private fun SQLiteDatabase.isUniqueIndex(table: String, index: String): Boolean =
        rawQuery("PRAGMA index_list($table)", null).use { c ->
            val name = c.getColumnIndexOrThrow("name")
            val unique = c.getColumnIndexOrThrow("unique")
            var found = false
            while (c.moveToNext()) if (c.getString(name) == index) found = c.getInt(unique) != 0
            found
        }

    private fun SQLiteDatabase.countBlobs(): Int =
        rawQuery("SELECT COUNT(*) FROM snapshot_blobs", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else -1
        }
}
