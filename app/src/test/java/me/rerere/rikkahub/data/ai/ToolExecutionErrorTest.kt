package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.ai.tools.workspaceWriteStdinSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolExecutionErrorTest {
    private val writeStdinTool = Tool(
        name = "workspace_write_stdin",
        description = "",
        parameters = ::workspaceWriteStdinSchema,
        execute = { emptyList() },
    )

    @Test
    fun writeStdinSchemaRequiresSessionId() {
        assertEquals(listOf("session_id"), workspaceWriteStdinSchema().required)
        assertTrue(workspaceWriteStdinSchema().properties.containsKey("session_id"))
        val serialized = Json.encodeToJsonElement<InputSchema>(workspaceWriteStdinSchema()).jsonObject
        assertEquals(listOf("session_id"), serialized.getValue("required").jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun missingSessionIdIsRejectedBeforeExecution() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            validateRequiredToolArguments(writeStdinTool, buildJsonObject {})
        }
        assertEquals("missing required param: session_id", error.message)
    }

    @Test
    fun toolErrorResultContainsNoStackTrace() {
        val error = IllegalStateException("session_id is required\n\tat example.Stack.method(Stack.java:42)")
        val result = toolExecutionErrorResult(error)
        val body = Json.parseToJsonElement(result.text).jsonObject

        assertEquals("true", body.getValue("isError").jsonPrimitive.content)
        assertEquals("IllegalStateException: session_id is required", body.getValue("content").jsonPrimitive.content)
        assertFalse(result.text.contains("Stack.java"))
    }
}
