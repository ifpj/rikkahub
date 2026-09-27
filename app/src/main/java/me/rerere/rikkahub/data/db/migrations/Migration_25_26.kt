package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Version 25 was produced by two branches with different schemas. The fork
 * added a temporary [chat_model_id] column, while the upstream schema did
 * not. Version 26 is the upstream schema, so remove that column when it is
 * present and add the shell compatibility column when needed.
 */
val Migration_25_26 = object : Migration(25, 26) {
    override fun migrate(db: SupportSQLiteDatabase) {
        if (hasColumn(db, "ConversationEntity", "chat_model_id")) {
            try {
                db.execSQL("ALTER TABLE `ConversationEntity` DROP COLUMN `chat_model_id`")
            } catch (_: Exception) {
                // DROP COLUMN is unavailable on some older Android SQLite
                // versions. The fallback keeps the same data and schema.
                rebuildConversationTable(db)
            }
        }
        if (!hasColumn(db, "workspaces", "shell_compatibility_mode")) {
            db.execSQL(
                "ALTER TABLE `workspaces` ADD COLUMN `shell_compatibility_mode` INTEGER NOT NULL DEFAULT 0"
            )
        }
    }
}

private fun rebuildConversationTable(db: SupportSQLiteDatabase) {
    db.execSQL(
        """
        CREATE TABLE `ConversationEntity_new` (
            `id` TEXT NOT NULL,
            `assistant_id` TEXT NOT NULL DEFAULT '0950e2dc-9bd5-4801-afa3-aa887aa36b4e',
            `title` TEXT NOT NULL,
            `nodes` TEXT NOT NULL,
            `create_at` INTEGER NOT NULL,
            `update_at` INTEGER NOT NULL,
            `suggestions` TEXT NOT NULL DEFAULT '[]',
            `is_pinned` INTEGER NOT NULL DEFAULT 0,
            `custom_system_prompt` TEXT NOT NULL DEFAULT '',
            `mode_injection_ids` TEXT NOT NULL DEFAULT '[]',
            `lorebook_ids` TEXT NOT NULL DEFAULT '[]',
            `workspace_cwd` TEXT NOT NULL DEFAULT '',
            `folder_id` TEXT NOT NULL DEFAULT '',
            PRIMARY KEY(`id`)
        )
        """.trimIndent()
    )
    db.execSQL(
        """
        INSERT INTO `ConversationEntity_new` (
            `id`, `assistant_id`, `title`, `nodes`, `create_at`, `update_at`,
            `suggestions`, `is_pinned`, `custom_system_prompt`, `mode_injection_ids`,
            `lorebook_ids`, `workspace_cwd`, `folder_id`
        )
        SELECT `id`, `assistant_id`, `title`, `nodes`, `create_at`, `update_at`,
            `suggestions`, `is_pinned`, `custom_system_prompt`, `mode_injection_ids`,
            `lorebook_ids`, `workspace_cwd`, `folder_id`
        FROM `ConversationEntity`
        """.trimIndent()
    )

    // SQLite versions without DROP COLUMN also need the child table detached
    // before replacing the parent table. Keep its rows in a temporary table.
    db.execSQL(
        """
        CREATE TABLE `message_node_backup` AS
        SELECT `id`, `conversation_id`, `node_index`, `messages`, `select_index`
        FROM `message_node`
        """.trimIndent()
    )
    db.execSQL("DROP TABLE `message_node`")
    db.execSQL("DROP TABLE `ConversationEntity`")
    db.execSQL("ALTER TABLE `ConversationEntity_new` RENAME TO `ConversationEntity`")
    db.execSQL(
        """
        CREATE TABLE `message_node` (
            `id` TEXT NOT NULL,
            `conversation_id` TEXT NOT NULL,
            `node_index` INTEGER NOT NULL,
            `messages` TEXT NOT NULL,
            `select_index` INTEGER NOT NULL,
            PRIMARY KEY(`id`),
            FOREIGN KEY(`conversation_id`) REFERENCES `ConversationEntity`(`id`)
                ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent()
    )
    db.execSQL(
        """
        INSERT INTO `message_node` (`id`, `conversation_id`, `node_index`, `messages`, `select_index`)
        SELECT `id`, `conversation_id`, `node_index`, `messages`, `select_index`
        FROM `message_node_backup`
        """.trimIndent()
    )
    db.execSQL("DROP TABLE `message_node_backup`")
    db.execSQL(
        "CREATE INDEX `index_message_node_conversation_id` ON `message_node` (`conversation_id`)"
    )
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
