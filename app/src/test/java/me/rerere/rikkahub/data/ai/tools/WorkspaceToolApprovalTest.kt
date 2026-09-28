package me.rerere.rikkahub.data.ai.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceToolApprovalTest {
    @Test
    fun `command requires approval and stdin continuation does not`() {
        assertTrue(resolveWorkspaceToolApproval("workspace_exec_command", emptyMap()))
        assertFalse(resolveWorkspaceToolApproval("workspace_write_stdin", emptyMap()))
    }

    @Test
    fun `command approval honors workspace overrides`() {
        val overrides = mapOf(
            "workspace_exec_command" to false,
        )

        assertFalse(resolveWorkspaceToolApproval("workspace_exec_command", overrides))
        assertFalse(resolveWorkspaceToolApproval("workspace_write_stdin", overrides))
    }
}
