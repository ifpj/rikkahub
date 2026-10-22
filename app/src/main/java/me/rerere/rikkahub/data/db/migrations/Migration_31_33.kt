package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Version 31 was shipped with different schemas by upstream and this fork. */
val Migration_31_33 = object : Migration(31, 33) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // These repairs are idempotent; preserve any existing records/settings.
        Migration_30_31.migrate(db)
        val columns = mutableSetOf<String>()
        db.query("PRAGMA table_info(`workspaces`)").use { cursor ->
            val name = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) columns += cursor.getString(name)
        }
        if ("type" !in columns) {
            db.execSQL("ALTER TABLE `workspaces` ADD COLUMN `type` TEXT NOT NULL DEFAULT 'LOCAL'")
        }
        if ("ssh_config" !in columns) {
            db.execSQL("ALTER TABLE `workspaces` ADD COLUMN `ssh_config` TEXT")
        }
        if ("mount_shared_storage" !in columns) {
            db.execSQL("ALTER TABLE `workspaces` ADD COLUMN `mount_shared_storage` INTEGER NOT NULL DEFAULT 0")
        }
    }
}
