package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Upstream v32 has SSH fields but not the fork's shared storage preference. */
val Migration_32_33 = object : Migration(32, 33) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `workspaces` ADD COLUMN `mount_shared_storage` INTEGER NOT NULL DEFAULT 0")
    }
}
