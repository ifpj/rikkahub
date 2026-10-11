package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Adds the per-workspace shared-storage flag.
 *
 * Some forked version-29 databases already contain this column because an
 * earlier build applied the change before the database version was bumped.
 * Keep this migration idempotent so those databases can still reach version
 * 30 without losing any workspace data.
 */
val Migration_29_30 = object : Migration(29, 30) {
    override fun migrate(db: SupportSQLiteDatabase) {
        if (!hasColumn(db, "workspaces", "mount_shared_storage")) {
            db.execSQL(
                "ALTER TABLE `workspaces` ADD COLUMN `mount_shared_storage` INTEGER NOT NULL DEFAULT 0"
            )
        }
    }
}

private fun hasColumn(db: SupportSQLiteDatabase, table: String, column: String): Boolean {
    db.query("PRAGMA table_info(`$table`)").use { cursor ->
        val nameIndex = cursor.getColumnIndex("name")
        while (cursor.moveToNext()) {
            if (nameIndex >= 0 && cursor.getString(nameIndex) == column) {
                return true
            }
        }
    }
    return false
}
