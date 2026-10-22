package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Backfills usage only when creating the ledger for the first time. */
val Migration_30_31 = object : Migration(30, 31) {
    override fun migrate(db: SupportSQLiteDatabase) {
        Migration_26_27.migrate(db)
        Migration_29_30.migrate(db)
        ensureUsageRecords(db)
    }
}

internal fun ensureUsageRecords(db: SupportSQLiteDatabase) {
    db.query("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'usage_record'").use {
        if (it.moveToFirst()) return
    }
    db.execSQL(
        "CREATE TABLE IF NOT EXISTS `usage_record` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`created_at` INTEGER NOT NULL, `source` TEXT NOT NULL, `provider_id` TEXT, `model_id` TEXT, " +
            "`model_name` TEXT, `assistant_id` TEXT, `conversation_id` TEXT, " +
            "`prompt_tokens` INTEGER NOT NULL, `completion_tokens` INTEGER NOT NULL, " +
            "`cached_tokens` INTEGER NOT NULL)"
    )
    db.execSQL("CREATE INDEX IF NOT EXISTS `index_usage_record_created_at` ON `usage_record` (`created_at`)")

    db.execSQL(
        """
        INSERT INTO usage_record (
            created_at, source, provider_id, model_id, model_name, assistant_id, conversation_id,
            prompt_tokens, completion_tokens, cached_tokens
        )
        SELECT
            COALESCE(
                CAST(strftime('%s', substr(json_extract(j.value, '$.createdAt'), 1, 19), 'utc') AS INTEGER) * 1000,
                c.create_at
            ),
            'CHAT',
            NULL,
            json_extract(j.value, '$.modelId'),
            json_extract(j.value, '$.modelSnapshot.modelId'),
            c.assistant_id,
            mn.conversation_id,
            COALESCE(json_extract(j.value, '$.usage.promptTokens'), 0),
            COALESCE(json_extract(j.value, '$.usage.completionTokens'), 0),
            COALESCE(json_extract(j.value, '$.usage.cachedTokens'), 0)
        FROM message_node mn
        JOIN ConversationEntity c ON c.id = mn.conversation_id,
            json_each(CASE WHEN json_valid(mn.messages) THEN mn.messages ELSE '[]' END) j
        WHERE json_extract(j.value, '$.role') = 'assistant'
            AND json_type(j.value, '$.usage') = 'object'
            AND (
                COALESCE(json_extract(j.value, '$.usage.promptTokens'), 0) > 0
                OR COALESCE(json_extract(j.value, '$.usage.completionTokens'), 0) > 0
            )
        """.trimIndent()
    )
}
