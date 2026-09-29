package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.mcp.MCP_TERMINAL_CARD_METADATA_KEY
import me.rerere.rikkahub.ui.components.message.tools.ShellToolUI
import me.rerere.rikkahub.ui.components.message.tools.ToolUIRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class ToolDisplayMetadataTest {
    private val toolName = "mcp__shell__exec_command"
    private val displayMetadata = buildJsonObject { put(MCP_TERMINAL_CARD_METADATA_KEY, true) }

    @Test
    fun `new call uses terminal card before output arrives`() {
        val current = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Tool("new", toolName, "{}")),
        )
        val marked = listOf(current).withToolDisplayMetadata(mapOf(toolName to displayMetadata))
        val call = marked.single().getTools().single()

        assertEquals(true, call.output.isEmpty())
        assertEquals(displayMetadata, call.metadata)
        assertSame(ShellToolUI, ToolUIRegistry.resolve(call))
        assertSame(marked, marked.withToolDisplayMetadata(mapOf(toolName to displayMetadata)))
    }

    @Test
    fun `only current matching call is marked and old result marker still works`() {
        val historical = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Tool("old", toolName, "{}")),
        )
        val current = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool("matching", toolName, "{}"),
                UIMessagePart.Tool("other", "unrelated_tool", "{}"),
            ),
        )
        val marked = listOf(historical, current)
            .withToolDisplayMetadata(mapOf(toolName to displayMetadata))

        assertEquals(null, marked.first().getTools().single().metadata)
        assertNotSame(ShellToolUI, ToolUIRegistry.resolve(marked.last().getTools().last()))

        val oldOutput = UIMessagePart.Text("{}", metadata = displayMetadata)
        val oldCall = historical.getTools().single().copy(output = listOf(oldOutput))
        assertSame(ShellToolUI, ToolUIRegistry.resolve(oldCall))
    }
}
