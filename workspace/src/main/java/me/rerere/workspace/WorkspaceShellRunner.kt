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

    /** 在后台启动命令并立即返回, [id] 用来命名日志文件; [WorkspaceShellContext.timeoutMillis] 不起作用 */
    fun startBackground(context: WorkspaceShellContext, id: String): WorkspaceBackgroundCommand =
        throw UnsupportedOperationException("Background commands are not supported by this shell")

    /** 交互式终端的启动参数, 不支持交互式终端时返回 null */
    fun terminalLaunch(context: WorkspaceTerminalContext): WorkspaceTerminalLaunch? = null
}

data class WorkspaceTerminalContext(
    val filesDir: File,
    val linuxDir: File,
    val tempDir: File,
    val bindMounts: List<WorkspaceBindMount> = emptyList(),
    val shellCompatibilityMode: Boolean = false,
)

/** 交给终端模拟器拉起的本地进程 */
data class WorkspaceTerminalLaunch(
    val executable: String,
    val cwd: String,
    val args: List<String>,
    val env: List<String>,
)

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
    val shellCompatibilityMode: Boolean = false,
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

// Maximum characters retained per output stream. Collectors keep draining after this limit to avoid pipe deadlocks.
const val MAX_OUTPUT_CHARS = 128 * 1024

/** A running process whose lifetime is independent from an individual tool coroutine. */
class WorkspaceShellProcess private constructor(
    private val process: Process?,
    private val immediateResult: WorkspaceCommandResult?,
    timeoutMillis: Long,
    stdin: ByteArray?,
) {
    private val stdoutCollector = process?.let { StreamCollector(it.inputStream) }
    private val stderrCollector = process?.let { StreamCollector(it.errorStream) }
    private val stdinWriter = if (process != null && stdin != null) {
        StreamWriter(process.outputStream, stdin)
    } else {
        null
    }

    @Volatile
    private var timedOut = false

    init {
        if (process != null) {
            Thread {
                try {
                    if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                        timedOut = true
                        process.destroyForcibly()
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
        get() = immediateResult != null || process?.isAlive == false

    @Throws(InterruptedException::class)
    fun await(timeoutMillis: Long): Boolean {
        if (immediateResult != null) return true
        return process?.waitFor(timeoutMillis.coerceAtLeast(0), TimeUnit.MILLISECONDS) ?: true
    }

    @Throws(InterruptedException::class)
    fun awaitResult(): WorkspaceCommandResult {
        if (immediateResult != null) return immediateResult
        process?.waitFor()
        return result() ?: error("Shell process did not complete")
    }

    fun result(): WorkspaceCommandResult? {
        immediateResult?.let { return it }
        val currentProcess = process ?: return null
        if (currentProcess.isAlive) return null
        joinCollectors()
        return WorkspaceCommandResult(
            exitCode = if (timedOut) -1 else currentProcess.exitValue(),
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
        val currentProcess = process ?: error("Shell process has already completed")
        require(currentProcess.isAlive) { "Shell process has already completed" }
        synchronized(currentProcess.outputStream) {
            currentProcess.outputStream.write(bytes)
            currentProcess.outputStream.flush()
        }
    }

    fun closeStdin() {
        runCatching { process?.outputStream?.close() }
    }

    fun terminate() {
        closeStdin()
        process?.takeIf { it.isAlive }?.destroyForcibly()
    }

    internal fun awaitAfterTermination() {
        runCatching { process?.waitFor(1, TimeUnit.SECONDS) }
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
        ): WorkspaceShellProcess = WorkspaceShellProcess(
            process = process,
            immediateResult = null,
            timeoutMillis = timeoutMillis,
            stdin = stdin,
        )

        fun completed(result: WorkspaceCommandResult): WorkspaceShellProcess = WorkspaceShellProcess(
            process = null,
            immediateResult = result,
            timeoutMillis = 0,
            stdin = null,
        )
    }
}

internal class StreamWriter(
    private val stream: java.io.OutputStream,
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

internal class StreamCollector(
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

/** POSIX shell 单引号转义 */
internal fun String.shellQuote(): String =
    "'" + replace("'", "'\"'\"'") + "'"
