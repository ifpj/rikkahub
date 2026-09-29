package me.rerere.rikkahub.data.ai.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class McpConnectionKeyTest {
    private val base = McpServerConfig.StreamableHTTPServer(
        commonOptions = McpCommonOptions(name = "demo"),
        url = "https://example.com/mcp",
    )

    @Test
    fun `tool metadata does not affect connection key`() {
        val withTools = base.copy(
            commonOptions = base.commonOptions.copy(
                tools = listOf(McpTool(name = "search", enable = false))
            )
        )
        val withoutPrefix = base.copy(
            commonOptions = base.commonOptions.copy(disableToolNamePrefix = true)
        )
        val deferredInitialization = base.copy(
            commonOptions = base.commonOptions.copy(skipStartupInitialization = true)
        )

        assertEquals(base.connectionKey(), withTools.connectionKey())
        assertEquals(base.connectionKey(), withoutPrefix.connectionKey())
        assertEquals(base.connectionKey(), deferredInitialization.connectionKey())
    }

    @Test
    fun `url transport and headers affect connection key`() {
        assertNotEquals(base.connectionKey(), base.copy(url = "https://example.com/other").connectionKey())
        assertNotEquals(
            base.connectionKey(),
            McpServerConfig.SseTransportServer(
                id = base.id,
                commonOptions = base.commonOptions,
                url = base.url,
            ).connectionKey()
        )
        assertNotEquals(
            base.connectionKey(),
            base.copy(
                commonOptions = base.commonOptions.copy(headers = listOf("X-API-Key" to "secret"))
            ).connectionKey()
        )
    }

    @Test
    fun `terminal output switch owns the raw output header and reconnects`() {
        assertEquals(false, base.commonOptions.terminalOutputEnabled)
        assertEquals(false, base.resolvedHeaders().any { it.first == MCP_RAW_STDOUT_HEADER })

        val enabled = base.copy(
            commonOptions = base.commonOptions.copy(terminalOutputEnabled = true)
        )
        assertEquals(listOf(MCP_RAW_STDOUT_HEADER to "1"), enabled.resolvedHeaders())
        assertNotEquals(base.connectionKey(), enabled.connectionKey())

        val spoofed = base.copy(
            commonOptions = base.commonOptions.copy(
                headers = listOf("x-shell-mcp-raw-stdout" to "1")
            )
        )
        assertEquals(
            false,
            spoofed.resolvedHeaders().any { it.first.equals(MCP_RAW_STDOUT_HEADER, ignoreCase = true) }
        )
    }

    @Test
    fun `oauth token affects connection key unless manual authorization header wins`() {
        val oauth = McpOAuthState(enabled = true, accessToken = "oauth-token")
        val withOAuth = base.copy(commonOptions = base.commonOptions.copy(oauth = oauth))
        assertNotEquals(base.connectionKey(), withOAuth.connectionKey())

        val manualAuth = base.copy(
            commonOptions = base.commonOptions.copy(
                headers = listOf("Authorization" to "Bearer manual"),
                oauth = oauth,
            )
        )
        val manualAuthWithoutOAuth = manualAuth.copy(
            commonOptions = manualAuth.commonOptions.copy(oauth = null)
        )
        assertEquals(manualAuthWithoutOAuth.connectionKey(), manualAuth.connectionKey())
    }

    @Test
    fun `startup initialization can only be skipped with cached tools`() {
        val deferredWithoutCache = base.copy(
            commonOptions = base.commonOptions.copy(skipStartupInitialization = true)
        )
        val deferredWithCache = deferredWithoutCache.copy(
            commonOptions = deferredWithoutCache.commonOptions.copy(
                tools = listOf(McpTool(name = "search"))
            )
        )

        assertEquals(true, deferredWithoutCache.shouldInitializeOnStartup())
        assertEquals(false, deferredWithCache.shouldInitializeOnStartup())
        assertEquals(false, deferredWithCache.shouldConnectDuringReconcile(false, false))
        assertEquals(true, deferredWithCache.shouldConnectDuringReconcile(true, true))
    }
}
