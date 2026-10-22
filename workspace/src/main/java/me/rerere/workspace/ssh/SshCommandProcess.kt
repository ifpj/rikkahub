package me.rerere.workspace.ssh

import me.rerere.workspace.WorkspaceProcessBackend
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/** One exec channel corresponds to one command, never a reusable interactive shell. */
internal class SshCommandProcess(private val shell: SshShell) : WorkspaceProcessBackend {
    override val stdoutStream: InputStream = shell.inputStream
    override val stderrStream: InputStream? = null
    override val stdinStream: OutputStream = shell.outputStream
    override val isAlive: Boolean get() = shell.isAlive
    override val usesPty: Boolean = true

    override fun waitFor(timeoutMillis: Long): Boolean {
        val start = System.nanoTime()
        val timeout = TimeUnit.MILLISECONDS.toNanos(timeoutMillis.coerceAtLeast(0))
        while (isAlive) {
            val remaining = timeout - (System.nanoTime() - start)
            if (remaining <= 0) return false
            Thread.sleep(TimeUnit.NANOSECONDS.toMillis(remaining).coerceIn(1, 50))
        }
        shell.close()
        return true
    }

    override fun waitFor() {
        shell.waitFor()
        shell.close()
    }

    override fun exitCode(): Int = shell.exitCode

    override fun closeStdin() = writeControl(byteArrayOf(4, 4))

    override fun interrupt() = writeControl(byteArrayOf(3))

    private fun writeControl(bytes: ByteArray) {
        synchronized(stdinStream) {
            stdinStream.write(bytes)
            stdinStream.flush()
        }
    }

    override fun resizeTerminal(rows: Int, columns: Int) = shell.resize(columns, rows)

    override fun terminate() = shell.terminate()
}
