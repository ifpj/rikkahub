package me.rerere.rikkahub.data.ai.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceToolApprovalTest {
    @Test
    fun `shell continuation tools do not request approval by default`() {
        assertFalse(resolveWorkspaceToolApproval("workspace_shell_wait", emptyMap()))
        assertFalse(resolveWorkspaceToolApproval("workspace_shell_write", emptyMap()))
    }

    @Test
    fun `shell continuation tools honor workspace approval overrides`() {
        val overrides = mapOf(
            "workspace_shell_wait" to true,
            "workspace_shell_write" to true,
        )

        assertTrue(resolveWorkspaceToolApproval("workspace_shell_wait", overrides))
        assertTrue(resolveWorkspaceToolApproval("workspace_shell_write", overrides))
    }
}
