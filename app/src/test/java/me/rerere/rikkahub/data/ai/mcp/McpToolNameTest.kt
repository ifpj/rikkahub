package me.rerere.rikkahub.data.ai.mcp

import org.junit.Assert.assertEquals
import org.junit.Test

class McpToolNameTest {
    @Test
    fun `prefix is enabled by default`() {
        val server = server(name = "demo", disablePrefix = false, toolName = "search")

        assertEquals("mcp__demo__search", server.toolNameForModel("search"))
    }

    @Test
    fun `disabled prefix exposes original tool name`() {
        val server = server(name = "demo", disablePrefix = true, toolName = "search")

        assertEquals("search", server.toolNameForModel("search"))
    }

    @Test
    fun `duplicate names are reported after both prefixes are disabled`() {
        val first = server(name = "first", disablePrefix = true, toolName = "search")
        val second = server(name = "second", disablePrefix = false, toolName = "search")
        val updatedSecond = second.clone(
            commonOptions = second.commonOptions.copy(disableToolNamePrefix = true)
        )

        assertEquals(listOf("search"), findDuplicateMcpToolNames(listOf(first, second), updatedSecond))
    }

    @Test
    fun `disabled tools do not create conflicts`() {
        val first = server(name = "first", disablePrefix = true, toolName = "search")
        val second = server(name = "second", disablePrefix = true, toolName = "search", toolEnabled = false)

        assertEquals(emptyList<String>(), findDuplicateMcpToolNames(listOf(first, second), second))
    }

    private fun server(
        name: String,
        disablePrefix: Boolean,
        toolName: String,
        toolEnabled: Boolean = true,
    ): McpServerConfig = McpServerConfig.StreamableHTTPServer(
        commonOptions = McpCommonOptions(
            name = name,
            disableToolNamePrefix = disablePrefix,
            tools = listOf(McpTool(enable = toolEnabled, name = toolName)),
        )
    )
}
