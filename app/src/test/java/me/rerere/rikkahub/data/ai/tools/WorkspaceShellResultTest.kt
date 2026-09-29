package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.jsonPrimitive
import me.rerere.workspace.WorkspaceShellSessionResult
import me.rerere.workspace.WorkspaceShellSessionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class WorkspaceShellResultTest {
    @Test
    fun runningResultUsesSessionIdAcceptedByWriteStdin() {
        val result = WorkspaceShellSessionResult(
            status = WorkspaceShellSessionStatus.RUNNING,
            sessionId = "session-123",
            stdout = "ready",
            stderr = "",
        ).toJson()

        assertEquals("session-123", result["session_id"]?.jsonPrimitive?.content)
        assertFalse("sessionId" in result)
    }

    @Test
    fun completedResultUsesSnakeCaseStatusFields() {
        val result = WorkspaceShellSessionResult(
            status = WorkspaceShellSessionStatus.COMPLETED,
            sessionId = "session-123",
            stdout = "",
            stderr = "",
            exitCode = -1,
            timedOut = true,
        ).toJson()

        assertEquals("-1", result["exit_code"].toString())
        assertEquals("true", result["timed_out"].toString())
        assertFalse("exitCode" in result)
        assertFalse("timedOut" in result)
    }
}
