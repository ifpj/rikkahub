package me.rerere.rikkahub.data.db.migrations

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration_25_26_Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrateForkVersion25_addsWorkspaceColumnAndPreservesChatModel() {
        val name = "migration-fork-25"
        helper.createDatabase(name, 24).apply {
            execSQL("ALTER TABLE `ConversationEntity` ADD COLUMN `chat_model_id` TEXT NOT NULL DEFAULT ''")
            execSQL(
                """INSERT INTO `ConversationEntity` (id, title, nodes, create_at, update_at, chat_model_id)
                    VALUES ('conversation', 'Existing chat', '[]', 1, 1, 'saved-model')"""
            )
            version = 25
            close()
        }

        helper.runMigrationsAndValidate(name, 26, true, Migration_25_26).use { db ->
            assertTrue(hasColumn(db, "workspaces", "shell_compatibility_mode"))
            db.query("SELECT chat_model_id FROM `ConversationEntity` WHERE id = 'conversation'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("saved-model", cursor.getString(0))
            }
        }
    }

    @Test
    fun migrateUpstreamVersion25_addsChatModelColumnAndPreservesWorkspaceMode() {
        val name = "migration-upstream-25"
        helper.createDatabase(name, 25).apply {
            execSQL(
                """INSERT INTO `workspaces` (id, name, root, shell_status, created_at, updated_at,
                    shell_compatibility_mode)
                    VALUES ('workspace', 'Existing workspace', '/tmp/workspace', 'DISABLED', 1, 1, 1)"""
            )
            close()
        }

        helper.runMigrationsAndValidate(name, 26, true, Migration_25_26).use { db ->
            assertTrue(hasColumn(db, "ConversationEntity", "chat_model_id"))
            db.query("SELECT shell_compatibility_mode FROM `workspaces` WHERE id = 'workspace'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }
        }
    }

    private fun hasColumn(db: androidx.sqlite.db.SupportSQLiteDatabase, table: String, column: String): Boolean {
        db.query("PRAGMA table_info(`$table`)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == column) return true
            }
        }
        return false
    }
}
