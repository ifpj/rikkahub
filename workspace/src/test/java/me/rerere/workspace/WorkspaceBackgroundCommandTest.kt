package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspaceBackgroundCommandTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun backgroundCommandsKeepWorkspaceMountsWithoutAnInteractivePty() {
        var captured: WorkspaceShellContext? = null
        val runner = object : WorkspaceShellRunner {
            override fun start(context: WorkspaceShellContext): WorkspaceShellProcess =
                error("Background commands must use their dedicated runner")

            override fun startBackground(context: WorkspaceShellContext, id: String): WorkspaceBackgroundCommand {
                captured = context
                return object : WorkspaceBackgroundCommand {
                    override val logPath = "/tmp/$id.log"
                    override fun waitFor() = 0
                    override fun outputSize() = 0L
                    override fun readOutput(offset: Long, maxBytes: Int) = byteArrayOf()
                    override fun kill() = Unit
                }
            }
        }
        val manager = WorkspaceManager(temp.newFolder("workspaces"), shellRunner = runner)
        manager.ensureWorkspace("one")
        val mount = WorkspaceBindMount(temp.newFolder("shared"), "/storage/emulated/10")
        val backend = manager.backend("one", shellCompatibilityMode = true, additionalBindMounts = listOf(mount))

        val command = backend.startBackgroundCommand("printf ready", "", "task")

        assertEquals("/tmp/task.log", command.logPath)
        val context = requireNotNull(captured)
        assertEquals("printf ready", context.command)
        assertEquals(0L, context.timeoutMillis)
        assertFalse(context.usePty)
        assertTrue(context.shellCompatibilityMode)
        assertTrue(context.bindMounts.contains(mount))
    }
}
