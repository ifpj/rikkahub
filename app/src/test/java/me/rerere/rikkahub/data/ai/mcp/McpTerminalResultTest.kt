package me.rerere.rikkahub.data.ai.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.ai.tools.SHELL_TERMINAL_OUTPUT_METADATA_KEY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class McpTerminalResultTest {
    @Test
    fun `raw output stays in UI metadata only`() {
        val result = mcpTextPart(
            """{"status":"completed","stdout":"red","raw_stdout":"\u001b[31mred\u001b[0m"}""",
            terminalOutputEnabled = true,
        )
        assertFalse(result.text.contains("raw_stdout"))
        assertEquals("red", Json.parseToJsonElement(result.text).jsonObject["stdout"]?.jsonPrimitive?.content)
        assertEquals("true", result.metadata?.get(MCP_TERMINAL_CARD_METADATA_KEY).toString())
        assertEquals("\u001b[31mred\u001b[0m", result.metadata?.get(SHELL_TERMINAL_OUTPUT_METADATA_KEY)?.jsonPrimitive?.content)
    }

    @Test
    fun `disabled service preserves ordinary MCP result`() {
        val text = """{"stdout":"red","raw_stdout":"raw"}"""
        val result = mcpTextPart(text, terminalOutputEnabled = false)
        assertEquals(text, result.text)
        assertNull(result.metadata)
    }

    @Test
    fun `enabled service never sends raw stdout even for non-terminal JSON`() {
        val result = mcpTextPart("""{"message":"ok","raw_stdout":"secret"}""", true)
        assertEquals("""{"message":"ok"}""", result.text)
        assertNull(result.metadata)
    }
}
