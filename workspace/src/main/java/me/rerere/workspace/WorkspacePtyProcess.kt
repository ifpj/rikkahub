package me.rerere.workspace

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

internal object WorkspacePtyProcess {
    fun start(
        command: String,
        cwd: String,
        args: Array<String>,
        environment: Array<String>,
        rows: Int,
        columns: Int,
    ): WorkspaceProcessBackend {
        val processId = IntArray(1)
        val fd = WorkspacePtyNative.createSubprocess(
            command = command,
            cwd = cwd,
            args = args,
            environment = environment,
            processId = processId,
            rows = rows,
            columns = columns,
        )
        if (fd < 0) throw IOException("Unable to create workspace PTY")
        return NativePtyBackend(fd, processId.single())
    }
}

private class NativePtyBackend(
    private val fd: Int,
    private val processId: Int,
) : WorkspaceProcessBackend {
    private val completionLock = Object()
    private val descriptorClosed = AtomicBoolean(false)

    @Volatile
    private var completedExitCode: Int? = null

    override val stdoutStream: InputStream = NativePtyInputStream()
    override val stderrStream: InputStream? = null
    override val stdinStream: OutputStream = NativePtyOutputStream()
    override val isAlive: Boolean get() = completedExitCode == null
    override val usesPty: Boolean = true

    init {
        Thread {
            val exitCode = WorkspacePtyNative.waitFor(processId)
            synchronized(completionLock) {
                completedExitCode = exitCode
                completionLock.notifyAll()
            }
        }.apply {
            name = "workspace-pty-wait"
            isDaemon = true
            start()
        }
    }

    override fun waitFor(timeoutMillis: Long): Boolean {
        if (!isAlive || timeoutMillis <= 0) return !isAlive
        val deadline = System.currentTimeMillis() + timeoutMillis
        synchronized(completionLock) {
            while (completedExitCode == null) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) break
                completionLock.wait(remaining)
            }
            return completedExitCode != null
        }
    }

    override fun waitFor() {
        synchronized(completionLock) {
            while (completedExitCode == null) completionLock.wait()
        }
    }

    override fun exitCode(): Int = completedExitCode ?: error("PTY process is still running")

    override fun closeStdin() {
        // A PTY is a single duplex descriptor and cannot be half-closed. EOT gives canonical-mode programs EOF.
        // Two EOTs also cover a pending partial line: the first flushes it, the second ends the following read.
        writeAll(byteArrayOf(4, 4))
    }

    override fun interrupt() {
        // ETX is interpreted by the terminal line discipline and delivers SIGINT to the foreground process group.
        writeAll(byteArrayOf(3))
    }

    override fun resizeTerminal(rows: Int, columns: Int) {
        if (!descriptorClosed.get()) {
            WorkspacePtyNative.setWindowSize(fd, rows.coerceAtLeast(1), columns.coerceAtLeast(1))
        }
    }

    override fun terminate() {
        if (isAlive) WorkspacePtyNative.killProcessGroup(processId)
    }

    private fun writeAll(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size) {
        if (descriptorClosed.get()) throw IOException("PTY is closed")
        var written = 0
        while (written < length) {
            val count = WorkspacePtyNative.write(fd, bytes, offset + written, length - written)
            if (count <= 0) throw IOException("Unable to write to workspace PTY")
            written += count
        }
    }

    private fun closeDescriptor() {
        if (descriptorClosed.compareAndSet(false, true)) WorkspacePtyNative.close(fd)
    }

    private inner class NativePtyInputStream : InputStream() {
        private val singleByte = ByteArray(1)

        override fun read(): Int {
            val count = read(singleByte, 0, 1)
            return if (count < 0) -1 else singleByte[0].toInt() and 0xff
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (descriptorClosed.get()) return -1
            val count = WorkspacePtyNative.read(fd, buffer, offset, length)
            return if (count <= 0) -1 else count
        }

        override fun close() = closeDescriptor()
    }

    private inner class NativePtyOutputStream : OutputStream() {
        override fun write(value: Int) = writeAll(byteArrayOf(value.toByte()))

        override fun write(buffer: ByteArray, offset: Int, length: Int) = writeAll(buffer, offset, length)

        override fun close() = closeStdin()
    }
}

private object WorkspacePtyNative {
    init {
        System.loadLibrary("workspace_pty")
    }

    @JvmStatic
    external fun createSubprocess(
        command: String,
        cwd: String,
        args: Array<String>,
        environment: Array<String>,
        processId: IntArray,
        rows: Int,
        columns: Int,
    ): Int

    @JvmStatic
    external fun setWindowSize(fd: Int, rows: Int, columns: Int)

    @JvmStatic
    external fun waitFor(processId: Int): Int

    @JvmStatic
    external fun read(fd: Int, buffer: ByteArray, offset: Int, length: Int): Int

    @JvmStatic
    external fun write(fd: Int, buffer: ByteArray, offset: Int, length: Int): Int

    @JvmStatic
    external fun killProcessGroup(processId: Int)

    @JvmStatic
    external fun close(fd: Int)
}
