package me.rerere.workspace

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

interface WorkspaceShellRunner {
    fun start(context: WorkspaceShellContext): WorkspaceShellProcess

    fun execute(context: WorkspaceShellContext): WorkspaceCommandResult {
        val shellProcess = start(context)
        return try {
            shellProcess.awaitResult()
        } catch (e: InterruptedException) {
            // Synchronous callers expect cancellation to stop the command as well.
            shellProcess.terminate()
            shellProcess.awaitAfterTermination()
            throw e
        }
    }
}

data class WorkspaceShellContext(
    val root: String,
    val command: String,
    val cwd: String,
    val filesDir: File,
    val linuxDir: File,
    val tempDir: File,
    val workingDir: File,
    val timeoutMillis: Long,
    val stdin: ByteArray? = null,
    val bindMounts: List<WorkspaceBindMount> = emptyList(),
    val usePty: Boolean = false,
    val terminalRows: Int = DEFAULT_TERMINAL_ROWS,
    val terminalColumns: Int = DEFAULT_TERMINAL_COLUMNS,
)

class HostShellRunner : WorkspaceShellRunner {
    override fun start(context: WorkspaceShellContext): WorkspaceShellProcess {
        val process = ProcessBuilder(defaultShell(), "-c", context.command)
            .directory(context.workingDir)
            .redirectErrorStream(false)
            .start()
        return WorkspaceShellProcess.start(process, context.timeoutMillis, context.stdin)
    }

    private fun defaultShell(): String =
        if (File("/system/bin/sh").exists()) "/system/bin/sh" else "/bin/sh"
}

const val DEFAULT_TERMINAL_ROWS = 24
const val DEFAULT_TERMINAL_COLUMNS = 120

// Maximum characters retained per output stream. Collectors keep draining after this limit to avoid pipe deadlocks.
const val MAX_OUTPUT_CHARS = 128 * 1024

/** A running process whose lifetime is independent from an individual tool coroutine. */
class WorkspaceShellProcess private constructor(
    private val backend: WorkspaceProcessBackend?,
    private val immediateResult: WorkspaceCommandResult?,
    timeoutMillis: Long,
    stdin: ByteArray?,
) {
    private val stdoutCollector = backend?.let { StreamCollector(it.stdoutStream) }
    private val stderrCollector = backend?.stderrStream?.let(::StreamCollector)
    private val stdinWriter = if (backend != null && stdin != null) {
        StreamWriter(backend.stdinStream, stdin)
    } else {
        null
    }

    @Volatile
    private var timedOut = false

    init {
        if (backend != null) {
            Thread {
                try {
                    if (!backend.waitFor(timeoutMillis.coerceAtLeast(1))) {
                        timedOut = true
                        backend.terminate()
                    }
                } catch (_: InterruptedException) {
                    // The watchdog is daemon-only and is never intentionally interrupted.
                }
            }.apply {
                name = "workspace-shell-timeout"
                isDaemon = true
                start()
            }
        }
    }

    val isCompleted: Boolean
        get() = immediateResult != null || backend?.isAlive == false

    val usesPty: Boolean
        get() = backend?.usesPty == true

    @Throws(InterruptedException::class)
    fun await(timeoutMillis: Long): Boolean {
        if (immediateResult != null) return true
        return backend?.waitFor(timeoutMillis.coerceAtLeast(0)) ?: true
    }

    @Throws(InterruptedException::class)
    fun awaitResult(): WorkspaceCommandResult {
        if (immediateResult != null) return immediateResult
        backend?.waitFor()
        return result() ?: error("Shell process did not complete")
    }

    fun result(): WorkspaceCommandResult? {
        immediateResult?.let { return it }
        val currentBackend = backend ?: return null
        if (currentBackend.isAlive) return null
        joinCollectors()
        return WorkspaceCommandResult(
            exitCode = if (timedOut) -1 else currentBackend.exitCode(),
            stdout = stdoutText(),
            stderr = stderrText(),
            timedOut = timedOut,
            truncated = isOutputTruncated,
        )
    }

    fun stdoutText(): String = immediateResult?.stdout ?: stdoutCollector?.text().orEmpty()

    fun stderrText(): String = immediateResult?.stderr ?: stderrCollector?.text().orEmpty()

    val isOutputTruncated: Boolean
        get() = immediateResult?.truncated == true ||
            stdoutCollector?.truncated == true || stderrCollector?.truncated == true

    @Throws(IOException::class)
    fun writeStdin(bytes: ByteArray) {
        val currentBackend = backend ?: error("Shell process has already completed")
        require(currentBackend.isAlive) { "Shell process has already completed" }
        synchronized(currentBackend.stdinStream) {
            currentBackend.stdinStream.write(bytes)
            currentBackend.stdinStream.flush()
        }
    }

    fun closeStdin() {
        runCatching { backend?.closeStdin() }
    }

    fun interrupt() {
        val currentBackend = backend ?: error("Shell process has already completed")
        require(currentBackend.isAlive) { "Shell process has already completed" }
        currentBackend.interrupt()
    }

    fun resizeTerminal(rows: Int, columns: Int) {
        backend?.resizeTerminal(rows, columns)
    }

    fun terminate() {
        backend?.terminate()
    }

    internal fun awaitAfterTermination() {
        runCatching { backend?.waitFor(1_000) }
        joinCollectors()
    }

    private fun joinCollectors() {
        stdinWriter?.join(1_000)
        stdoutCollector?.join(1_000)
        stderrCollector?.join(1_000)
    }

    companion object {
        fun start(
            process: Process,
            timeoutMillis: Long,
            stdin: ByteArray? = null,
        ): WorkspaceShellProcess = start(JavaProcessBackend(process), timeoutMillis, stdin)

        internal fun start(
            backend: WorkspaceProcessBackend,
            timeoutMillis: Long,
            stdin: ByteArray? = null,
        ): WorkspaceShellProcess = WorkspaceShellProcess(
            backend = backend,
            immediateResult = null,
            timeoutMillis = timeoutMillis,
            stdin = stdin,
        )

        fun completed(result: WorkspaceCommandResult): WorkspaceShellProcess = WorkspaceShellProcess(
            backend = null,
            immediateResult = result,
            timeoutMillis = 0,
            stdin = null,
        )
    }
}

internal interface WorkspaceProcessBackend {
    val stdoutStream: InputStream
    val stderrStream: InputStream?
    val stdinStream: OutputStream
    val isAlive: Boolean
    val usesPty: Boolean

    @Throws(InterruptedException::class)
    fun waitFor(timeoutMillis: Long): Boolean

    @Throws(InterruptedException::class)
    fun waitFor()

    fun exitCode(): Int

    fun closeStdin()

    fun interrupt()

    fun resizeTerminal(rows: Int, columns: Int)

    fun terminate()
}

private class JavaProcessBackend(
    private val process: Process,
) : WorkspaceProcessBackend {
    override val stdoutStream: InputStream = process.inputStream
    override val stderrStream: InputStream = process.errorStream
    override val stdinStream: OutputStream = process.outputStream
    override val isAlive: Boolean get() = process.isAlive
    override val usesPty: Boolean = false

    override fun waitFor(timeoutMillis: Long): Boolean =
        process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)

    override fun waitFor() {
        process.waitFor()
    }

    override fun exitCode(): Int = process.exitValue()

    override fun closeStdin() {
        process.outputStream.close()
    }

    override fun interrupt() {
        process.outputStream.write(3)
        process.outputStream.flush()
    }

    override fun resizeTerminal(rows: Int, columns: Int) = Unit

    override fun terminate() {
        runCatching { process.outputStream.close() }
        if (process.isAlive) process.destroyForcibly()
    }
}

private class StreamWriter(
    private val stream: OutputStream,
    private val bytes: ByteArray,
) {
    private val thread = Thread {
        try {
            stream.use { output ->
                output.write(bytes)
                output.flush()
            }
        } catch (_: IOException) {
            // The child may exit or be terminated before it consumes all stdin.
        }
    }.apply {
        isDaemon = true
        start()
    }

    fun join(millis: Long) = thread.join(millis)
}

private class StreamCollector(
    stream: InputStream,
    private val maxChars: Int = MAX_OUTPUT_CHARS,
) {
    private val builder = StringBuilder()

    @Volatile
    var truncated = false
        private set

    private val thread = Thread {
        try {
            stream.bufferedReader().use { reader ->
                val buffer = CharArray(4096)
                while (true) {
                    val read = reader.read(buffer)
                    if (read < 0) break
                    synchronized(builder) {
                        val remaining = maxChars - builder.length
                        if (remaining > 0) {
                            builder.append(buffer, 0, minOf(read, remaining))
                        }
                        if (read > remaining) {
                            truncated = true
                        }
                    }
                }
            }
        } catch (_: IOException) {
            // Preserve output already read when process streams are closed by timeout or termination.
        }
    }.apply {
        isDaemon = true
        start()
    }

    fun join(millis: Long) = thread.join(millis)

    fun text(): String = synchronized(builder) { builder.toString() }
}
