package me.rerere.rikkahub.data.db.migrations

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkahub.data.db.DatabaseMigrationTracker
import me.rerere.rikkahub.data.model.MemoryFile

private const val TAG = "Migration_27_28"

val Migration_27_28 = object : Migration(27, 28) {
    override fun migrate(db: SupportSQLiteDatabase) {
        Log.i(TAG, "migrate: start migrate from 27 to 28 (memory records -> memory files)")
        DatabaseMigrationTracker.onMigrationStart(27, 28)
        db.beginTransaction()
        try {
            repairConversationSchema(db)
            createMediaCreationTables(db)
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `memory_file` (`assistant_id` TEXT NOT NULL, `path` TEXT NOT NULL, " +
                    "`content` TEXT NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`assistant_id`, `path`))"
            )

            val memories = linkedMapOf<String, MutableList<String>>()
            db.query("SELECT assistant_id, content FROM MemoryEntity ORDER BY id").use { cursor ->
                while (cursor.moveToNext()) {
                    memories.getOrPut(cursor.getString(0)) { mutableListOf() }.add(cursor.getString(1))
                }
            }

            // 旧记忆每次都会完整注入，迁到同样会完整注入的 profile 里，升级后模型看到的内容不变
            val now = System.currentTimeMillis()
            var migratedCount = 0
            memories.forEach { (assistantId, contents) ->
                val profile = legacyMemoriesToProfile(contents) ?: return@forEach
                db.execSQL(
                    "INSERT INTO memory_file (assistant_id, path, content, updated_at) VALUES (?, ?, ?, ?)",
                    arrayOf<Any?>(assistantId, MemoryFile.PROFILE_PATH, profile, now)
                )
                migratedCount++
            }

            db.execSQL("DROP TABLE IF EXISTS MemoryEntity")
            db.setTransactionSuccessful()
            Log.i(TAG, "migrate: migrate from 27 to 28 success ($migratedCount memory stores migrated)")
        } finally {
            db.endTransaction()
            DatabaseMigrationTracker.onMigrationEnd()
        }
    }
}

private fun repairConversationSchema(db: SupportSQLiteDatabase) {
    val hasChatModelId = hasColumn(db, "ConversationEntity", "chat_model_id")
    val hasConfig = hasColumn(db, "ConversationEntity", "config")
    if (hasChatModelId) {
        try {
            db.execSQL("ALTER TABLE `ConversationEntity` DROP COLUMN `chat_model_id`")
        } catch (_: Exception) {
            rebuildConversationTable(db, hasConfig)
        }
    }
    if (!hasColumn(db, "ConversationEntity", "config")) {
        db.execSQL("ALTER TABLE `ConversationEntity` ADD COLUMN `config` TEXT NOT NULL DEFAULT ''")
    }
}

private fun createMediaCreationTables(db: SupportSQLiteDatabase) {
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `media_creation_session` (
            `id` TEXT NOT NULL,
            `title` TEXT NOT NULL,
            `draft` TEXT NOT NULL,
            `create_at` INTEGER NOT NULL,
            `update_at` INTEGER NOT NULL,
            PRIMARY KEY(`id`)
        )
        """.trimIndent()
    )
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `media_creation_node` (
            `id` TEXT NOT NULL,
            `session_id` TEXT NOT NULL,
            `selected_record_id` TEXT NOT NULL,
            `create_at` INTEGER NOT NULL,
            PRIMARY KEY(`id`),
            FOREIGN KEY(`session_id`) REFERENCES `media_creation_session`(`id`)
                ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent()
    )
    db.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_media_creation_node_session_id` " +
            "ON `media_creation_node` (`session_id`)"
    )
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `media_creation_record` (
            `id` TEXT NOT NULL,
            `session_id` TEXT NOT NULL,
            `node_id` TEXT NOT NULL,
            `provider_id` TEXT NOT NULL,
            `provider_name` TEXT NOT NULL,
            `model_id` TEXT NOT NULL,
            `kind` TEXT NOT NULL,
            `prompt` TEXT NOT NULL,
            `params` TEXT NOT NULL,
            `inputs` TEXT NOT NULL,
            `status` TEXT NOT NULL,
            `task_id` TEXT,
            `error` TEXT,
            `outputs` TEXT NOT NULL,
            `create_at` INTEGER NOT NULL,
            `update_at` INTEGER NOT NULL,
            PRIMARY KEY(`id`),
            FOREIGN KEY(`session_id`) REFERENCES `media_creation_session`(`id`)
                ON UPDATE NO ACTION ON DELETE CASCADE,
            FOREIGN KEY(`node_id`) REFERENCES `media_creation_node`(`id`)
                ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent()
    )
    db.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_media_creation_record_session_id` " +
            "ON `media_creation_record` (`session_id`)"
    )
    db.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_media_creation_record_node_id` " +
            "ON `media_creation_record` (`node_id`)"
    )
}

private fun rebuildConversationTable(db: SupportSQLiteDatabase, hasConfig: Boolean) {
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
            `config` TEXT NOT NULL DEFAULT '',
            PRIMARY KEY(`id`)
        )
        """.trimIndent()
    )
    db.execSQL(
        """
        INSERT INTO `ConversationEntity_new` (
            `id`, `assistant_id`, `title`, `nodes`, `create_at`, `update_at`,
            `suggestions`, `is_pinned`, `custom_system_prompt`, `mode_injection_ids`,
            `lorebook_ids`, `workspace_cwd`, `folder_id`, `config`
        )
        SELECT `id`, `assistant_id`, `title`, `nodes`, `create_at`, `update_at`,
            `suggestions`, `is_pinned`, `custom_system_prompt`, `mode_injection_ids`,
            `lorebook_ids`, `workspace_cwd`, `folder_id`, ${if (hasConfig) "`config`" else "''"}
        FROM `ConversationEntity`
        """.trimIndent()
    )
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
    db.execSQL("CREATE INDEX `index_message_node_conversation_id` ON `message_node` (`conversation_id`)")
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

/** 把旧的逐条记忆拼成一份 profile 文件，没有有效内容时返回 null */
internal fun legacyMemoriesToProfile(contents: List<String>): String? {
    val lines = contents
        .map { it.lines().joinToString(" ") { line -> line.trim() }.trim() }
        .filter { it.isNotEmpty() }
    if (lines.isEmpty()) return null
    return buildString {
        appendLine("---")
        appendLine("name: profile")
        appendLine("description: Who the user is")
        appendLine("---")
        appendLine()
        lines.forEach { appendLine("- [stated] $it") }
    }
}
