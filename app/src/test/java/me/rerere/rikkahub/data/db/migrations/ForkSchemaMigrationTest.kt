package me.rerere.rikkahub.data.db.migrations

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager

/** Execute the actual migrations against both shipped schema lineages, without touching user data. */
class ForkSchemaMigrationTest {
    @Test
    fun `fork v31 preserves chat and storage settings and matches canonical schema`() {
        migrateAndVerify(legacySchema(), fork = true)
    }

    @Test
    fun `upstream v31 preserves existing usage and does not duplicate it`() {
        migrateAndVerify(schema(31), fork = false)
    }

    @Test
    fun `legacy table rebuild preserves config and message rows`() {
        migrateAndVerify(legacySchema(), fork = true, forceRebuild = true)
    }

    @Test
    fun `upstream v32 only needs shared storage column`() {
        database(schema(32)).use { connection ->
            Migration_32_33.migrate(adapter(connection))
            assertCanonical(connection)
        }
    }

    private fun migrateAndVerify(source: JsonObject, fork: Boolean, forceRebuild: Boolean = false) {
        database(source).use { connection ->
            val db = adapter(connection, forceRebuild)
            db.execSQL("INSERT INTO ConversationEntity (id, title, nodes, create_at, update_at, config) " +
                "VALUES ('chat', 'keep', '[]', 1000, 1000, '{\"keep\":true}')")
            db.execSQL("INSERT INTO workspaces (id, name, root, shell_status, created_at, updated_at) " +
                "VALUES ('ws', 'keep', 'root', 'READY', 1000, 1000)")
            db.execSQL("INSERT INTO message_node VALUES ('msg', 'chat', 0, " +
                "'[{\"role\":\"assistant\",\"usage\":{\"promptTokens\":10,\"completionTokens\":5}}]', 0)")
            if (fork) {
                db.execSQL("UPDATE workspaces SET mount_shared_storage = 1")
                // A pre-reconciliation build could leave this temporary column behind.
                db.execSQL("ALTER TABLE ConversationEntity ADD COLUMN chat_model_id TEXT")
            } else {
                db.execSQL("INSERT INTO usage_record (created_at, source, prompt_tokens, completion_tokens, cached_tokens) " +
                    "VALUES (1000, 'CHAT', 10, 5, 0)")
            }
            Migration_31_33.migrate(db)
            Migration_31_33.migrate(db)
            assertCanonical(connection)
            assertEquals("{\"keep\":true}", scalar(connection, "SELECT config FROM ConversationEntity"))
            assertEquals(if (fork) "1" else "0", scalar(connection, "SELECT mount_shared_storage FROM workspaces"))
            assertEquals("1", scalar(connection, "SELECT count(*) FROM message_node"))
            assertEquals("1", scalar(connection, "SELECT count(*) FROM usage_record"))
            assertEquals("10", scalar(connection, "SELECT prompt_tokens FROM usage_record"))
        }
    }

    private fun assertCanonical(actual: Connection) {
        database(schema(33)).use { expected ->
            schema(33).getValue("entities").jsonArray.forEach { entity ->
                val name = entity.jsonObject.getValue("tableName").jsonPrimitive.content
                listOf("table_info", "foreign_key_list", "index_list").forEach { pragma ->
                    fun shape(connection: Connection) = rows(connection, "PRAGMA $pragma(`$name`)")
                        .map { it.drop(1) }.sortedBy { it.joinToString() }
                    assertEquals("$name $pragma", shape(expected), shape(actual))
                }
            }
        }
    }

    private fun rows(connection: Connection, sql: String): List<List<String?>> =
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { result ->
                buildList {
                    while (result.next()) add((1..result.metaData.columnCount).map { result.getString(it) })
                }.sortedBy { it.joinToString() }
            }
        }

    private fun scalar(connection: Connection, sql: String) = rows(connection, sql).single().single()

    private fun schema(version: Int): JsonObject = Json.parseToJsonElement(
        File("schemas/me.rerere.rikkahub.data.db.AppDatabase/$version.json").readText()
    ).jsonObject.getValue("database").jsonObject

    private fun legacySchema(): JsonObject = Json.parseToJsonElement(
        checkNotNull(javaClass.getResource("/legacy-fork-31.json")).readText()
    ).jsonObject.getValue("database").jsonObject

    private fun database(schema: JsonObject): Connection {
        val connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        connection.createStatement().use { statement ->
            schema.getValue("entities").jsonArray.forEach { entry ->
                val entity = entry.jsonObject
                val table = entity.getValue("tableName").jsonPrimitive.content
                statement.execute(entity.getValue("createSql").jsonPrimitive.content.replace("\u0024{TABLE_NAME}", table))
                entity["indices"]?.jsonArray.orEmpty().forEach { index ->
                    statement.execute(index.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\u0024{TABLE_NAME}", table))
                }
            }
        }
        return connection
    }

    private fun adapter(connection: Connection, forceRebuild: Boolean = false): SupportSQLiteDatabase = Proxy.newProxyInstance(
        javaClass.classLoader, arrayOf(SupportSQLiteDatabase::class.java),
    ) { _, method, args ->
        when (method.name) {
            "execSQL" -> connection.createStatement().use {
                val sql = args!![0] as String
                if (forceRebuild && "DROP COLUMN" in sql) error("Simulate older SQLite")
                it.execute(sql)
                null
            }
            "query" -> {
                val statement = connection.createStatement()
                val result = statement.executeQuery(args!![0] as String)
                Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Cursor::class.java)) { _, m, a ->
                    when (m.name) {
                        "moveToNext", "moveToFirst" -> result.next()
                        "getColumnIndex", "getColumnIndexOrThrow" -> result.findColumn(a!![0] as String) - 1
                        "getString" -> result.getString((a!![0] as Int) + 1)
                        "close" -> { result.close(); statement.close(); null }
                        else -> error("Unexpected cursor method: ${m.name}")
                    }
                }
            }
            else -> error("Unexpected database method: ${method.name}")
        }
    } as SupportSQLiteDatabase
}
