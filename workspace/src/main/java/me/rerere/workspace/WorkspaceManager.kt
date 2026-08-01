package me.rerere.workspace

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.UUID

class WorkspaceManager(
    private val baseDir: File,
    private val config: WorkspaceConfig = WorkspaceConfig(),
    private val shellRunner: WorkspaceShellRunner = HostShellRunner(),
    private val bindMounts: List<WorkspaceBindMount> = emptyList(),
) {
    private val fileSystem = WorkspaceFileSystem(config)
    private val shellSessions = mutableMapOf<String, ManagedShellSession>()
    private val shellSessionsLock = Any()

    // 按 target 长度降序, 保证 /a/b 优先于 /a 匹配
    private val sortedBindMounts = bindMounts.sortedByDescending { it.target.trimEnd('/').length }

    init {
        baseDir.mkdirs()
    }

    fun ensureWorkspace(root: String): File {
        val dir = workspaceDir(root)
        filesDir(root).mkdirs()
        linuxDir(root).mkdirs()
        tempDir(root).mkdirs()
        return dir
    }

    fun workspaceDir(root: String): File {
        requireValidRoot(root)
        return File(baseDir, root)
    }

    fun filesDir(root: String): File = File(workspaceDir(root), FILES_DIR)

    fun linuxDir(root: String): File = File(workspaceDir(root), LINUX_DIR)

    fun tempDir(root: String): File = File(workspaceDir(root), TEMP_DIR)

    fun hasRootfs(root: String): Boolean = File(linuxDir(root), "bin/sh").isFile

    fun deleteWorkspace(root: String): Boolean {
        terminateShellSessions(root)
        return workspaceDir(root).deleteRecursively()
    }

    fun listFiles(
        root: String,
        path: String = "",
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    ): List<WorkspaceFileEntry> =
        fileSystem.list(areaDir(root, area), path)

    fun readText(
        root: String,
        path: String,
        charset: Charset = StandardCharsets.UTF_8,
    ): String = fileSystem.readText(filesDir(root), path, charset)

    fun writeText(
        root: String,
        path: String,
        text: String,
        overwrite: Boolean = true,
        charset: Charset = StandardCharsets.UTF_8,
    ): WorkspaceFileEntry = fileSystem.writeText(filesDir(root), path, text, overwrite, charset)

    fun importFile(
        root: String,
        destinationPath: String,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
        fileName: String,
        inputStream: InputStream,
    ): WorkspaceFileEntry {
        val areaRoot = areaDir(root, area)
        val targetPath = if (destinationPath.isBlank()) fileName else "$destinationPath/$fileName"
        return fileSystem.importBytes(areaRoot, targetPath, inputStream)
    }

    fun fileSize(
        root: String,
        path: String,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    ): Long {
        val file = fileSystem.resolve(areaDir(root, area), path)
        require(file.exists()) { "File does not exist: $path" }
        require(file.isFile) { "Path is not a file: $path" }
        return file.length()
    }

    fun exportFile(
        root: String,
        path: String,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
        outputStream: OutputStream,
    ) {
        val file = fileSystem.resolve(areaDir(root, area), path)
        require(file.exists()) { "File does not exist: $path" }
        require(file.isFile) { "Path is not a file: $path" }
        outputStream.use { out -> file.inputStream().use { it.copyTo(out) } }
    }

    /**
     * 把 Rootfs 内的绝对路径映射到宿主机上的真实文件。
     *
     * bind mount 的 source 本身就是 Android 侧的普通目录, 因此 /skills 这类挂载路径
     * 可以直接用文件 IO 访问, 无需经过 PRoot; 只是 Rootfs 目录里对应位置是个空挂载点,
     * 按 [WorkspaceStorageArea.LINUX] 解析必然落空。
     */
    fun resolveRootfsPath(root: String, path: String): RootfsLocation {
        val trimmed = path.trim().trimEnd('/').ifBlank { "/" }
        require(trimmed.startsWith("/")) { "Rootfs path must be absolute: $path" }

        sortedBindMounts.forEach { mount ->
            val target = mount.target.trimEnd('/')
            if (trimmed == target) return RootfsLocation(mount.source, "")
            if (trimmed.startsWith("$target/")) {
                return RootfsLocation(mount.source, trimmed.removePrefix("$target/"))
            }
        }

        if (trimmed == ROOTFS_WORKSPACE_DIR || trimmed.startsWith("$ROOTFS_WORKSPACE_DIR/")) {
            return RootfsLocation(
                rootDir = filesDir(root),
                relativePath = trimmed.removePrefix(ROOTFS_WORKSPACE_DIR).trimStart('/'),
            )
        }

        // 内核伪文件系统: 显式拒绝, 而不是回落到一个必然读不到的物理路径
        KERNEL_FS_MOUNTS.firstOrNull { trimmed == it || trimmed.startsWith("$it/") }?.let {
            error("$it is a kernel filesystem and cannot be read as a file, use workspace_shell instead")
        }

        return RootfsLocation(linuxDir(root), trimmed.trimStart('/'))
    }

    fun rootfsFileSize(root: String, path: String): Long =
        resolveRootfsFile(root, path).also { it.requireReadableFile(path) }.length()

    fun exportRootfsFile(root: String, path: String, outputStream: OutputStream) {
        val file = resolveRootfsFile(root, path)
        file.requireReadableFile(path)
        outputStream.use { out -> file.inputStream().use { it.copyTo(out) } }
    }

    fun resolveRootfsFile(root: String, path: String): File {
        val location = resolveRootfsPath(root, path)
        return fileSystem.resolve(location.rootDir, location.relativePath)
    }

    private fun File.requireReadableFile(path: String) {
        require(exists()) { "File does not exist: $path" }
        require(isFile) { "Path is not a file: $path" }
    }

    fun deleteFile(
        root: String,
        path: String,
        recursive: Boolean = false,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    ): Boolean =
        fileSystem.delete(areaDir(root, area), path, recursive)

    fun moveFile(root: String, source: String, target: String, overwrite: Boolean = false): WorkspaceFileEntry =
        fileSystem.move(filesDir(root), source, target, overwrite)

    fun glob(root: String, pattern: String, path: String = ""): List<WorkspaceFileEntry> =
        fileSystem.glob(filesDir(root), pattern, path)

    fun grep(
        root: String,
        query: String,
        path: String = "",
        regex: Boolean = false,
        ignoreCase: Boolean = true,
        includeGlob: String? = null,
    ): List<WorkspaceSearchMatch> =
        fileSystem.grep(filesDir(root), query, path, regex, ignoreCase, includeGlob)

    fun executeCommand(
        root: String,
        command: String,
        cwd: String = "",
        timeoutMillis: Long = DEFAULT_COMMAND_TIMEOUT_MS,
        stdin: ByteArray? = null,
    ): WorkspaceCommandResult {
        return shellRunner.execute(createShellContext(root, command, cwd, timeoutMillis, stdin))
    }

    /** Starts a command and retains it as a session when it is still running after [yieldMillis]. */
    fun startCommandSession(
        root: String,
        command: String,
        cwd: String = "",
        timeoutMillis: Long = DEFAULT_COMMAND_TIMEOUT_MS,
        yieldMillis: Long = DEFAULT_SESSION_YIELD_MS,
        terminalRows: Int = DEFAULT_TERMINAL_ROWS,
        terminalColumns: Int = DEFAULT_TERMINAL_COLUMNS,
    ): WorkspaceShellSessionResult {
        val context = createShellContext(
            root = root,
            command = command,
            cwd = cwd,
            timeoutMillis = timeoutMillis,
            stdin = null,
            usePty = true,
            terminalRows = terminalRows,
            terminalColumns = terminalColumns,
        )
        val sessionId = UUID.randomUUID().toString()
        val session = synchronized(shellSessionsLock) {
            cleanupShellSessionsLocked()
            val activeCount = shellSessions.values.count { it.root == root && !it.process.isCompleted }
            require(activeCount < MAX_ACTIVE_SHELL_SESSIONS_PER_WORKSPACE) {
                "Too many active shell sessions for this workspace (max $MAX_ACTIVE_SHELL_SESSIONS_PER_WORKSPACE)"
            }
            ManagedShellSession(
                id = sessionId,
                root = root,
                process = shellRunner.start(context),
                lastAccessAt = System.currentTimeMillis(),
            ).also { shellSessions[sessionId] = it }
        }

        session.process.await(yieldMillis.coerceIn(0, MAX_SESSION_YIELD_MS))
        val result = takeShellSessionSnapshot(session)
        if (result.status == WorkspaceShellSessionStatus.COMPLETED) {
            synchronized(shellSessionsLock) { shellSessions.remove(sessionId) }
            return result.copy(sessionId = null)
        }
        return result
    }

    /** Waits briefly and returns only output produced since the previous snapshot. */
    fun waitCommandSession(
        root: String,
        sessionId: String,
        yieldMillis: Long = DEFAULT_SESSION_WAIT_MS,
    ): WorkspaceShellSessionResult {
        val session = getShellSession(root, sessionId)
        session.process.await(yieldMillis.coerceIn(0, MAX_SESSION_YIELD_MS))
        return takeShellSessionSnapshot(session)
    }

    /** Writes to, closes stdin for, or terminates an existing session. */
    fun updateCommandSession(
        root: String,
        sessionId: String,
        stdin: ByteArray? = null,
        closeStdin: Boolean = false,
        terminate: Boolean = false,
        interrupt: Boolean = false,
        terminalRows: Int? = null,
        terminalColumns: Int? = null,
    ): WorkspaceShellSessionResult {
        val session = getShellSession(root, sessionId)
        if (stdin != null) session.process.writeStdin(stdin)
        if (closeStdin) session.process.closeStdin()
        if (interrupt) session.process.interrupt()
        if (terminalRows != null && terminalColumns != null) {
            session.process.resizeTerminal(terminalRows, terminalColumns)
        }
        if (terminate) {
            session.process.terminate()
            session.process.await(TERMINATION_WAIT_MS)
        }
        return takeShellSessionSnapshot(session)
    }

    private fun createShellContext(
        root: String,
        command: String,
        cwd: String,
        timeoutMillis: Long,
        stdin: ByteArray?,
        usePty: Boolean = false,
        terminalRows: Int = DEFAULT_TERMINAL_ROWS,
        terminalColumns: Int = DEFAULT_TERMINAL_COLUMNS,
    ): WorkspaceShellContext {
        require(command.isNotBlank()) { "Command is required" }
        val workingDir = fileSystem.resolve(filesDir(root), cwd)
        require(workingDir.exists()) { "Working directory does not exist: $cwd" }
        require(workingDir.isDirectory) { "Working path is not a directory: $cwd" }
        return WorkspaceShellContext(
            root = root,
            command = command,
            cwd = cwd,
            filesDir = filesDir(root),
            linuxDir = linuxDir(root),
            tempDir = tempDir(root),
            workingDir = workingDir,
            timeoutMillis = timeoutMillis,
            stdin = stdin,
            bindMounts = bindMounts,
            usePty = usePty,
            terminalRows = terminalRows.coerceAtLeast(1),
            terminalColumns = terminalColumns.coerceAtLeast(1),
        )
    }

    private fun getShellSession(root: String, sessionId: String): ManagedShellSession =
        synchronized(shellSessionsLock) {
            cleanupShellSessionsLocked()
            shellSessions[sessionId]
                ?.takeIf { it.root == root }
                ?.also { it.lastAccessAt = System.currentTimeMillis() }
                ?: error("Shell session not found: $sessionId")
        }

    private fun takeShellSessionSnapshot(session: ManagedShellSession): WorkspaceShellSessionResult =
        synchronized(session) {
            // result() joins both collectors after process exit, so the final output is included in this snapshot.
            val commandResult = session.process.result()
            val stdout = session.process.stdoutText()
            val stderr = session.process.stderrText()
            val stdoutDelta = stdout.substring(session.stdoutCursor.coerceAtMost(stdout.length))
            val stderrDelta = stderr.substring(session.stderrCursor.coerceAtMost(stderr.length))
            session.stdoutCursor = stdout.length
            session.stderrCursor = stderr.length
            session.lastAccessAt = System.currentTimeMillis()
            WorkspaceShellSessionResult(
                status = if (commandResult == null) {
                    WorkspaceShellSessionStatus.RUNNING
                } else {
                    WorkspaceShellSessionStatus.COMPLETED
                },
                sessionId = session.id,
                stdout = stdoutDelta,
                stderr = stderrDelta,
                exitCode = commandResult?.exitCode,
                timedOut = commandResult?.timedOut == true,
                truncated = session.process.isOutputTruncated,
                pty = session.process.usesPty,
            )
        }

    private fun terminateShellSessions(root: String) {
        val sessions = synchronized(shellSessionsLock) {
            shellSessions.values.filter { it.root == root }.also { matching ->
                matching.forEach { shellSessions.remove(it.id) }
            }
        }
        sessions.forEach {
            it.process.terminate()
            it.process.awaitAfterTermination()
        }
    }

    private fun cleanupShellSessionsLocked() {
        val cutoff = System.currentTimeMillis() - COMPLETED_SESSION_RETENTION_MS
        shellSessions.entries.removeAll { (_, session) ->
            session.process.isCompleted && session.lastAccessAt < cutoff
        }
    }

    private fun requireValidRoot(root: String) {
        require(root.matches(ROOT_NAME_REGEX)) {
            "Invalid workspace root name: $root"
        }
    }

    private fun areaDir(root: String, area: WorkspaceStorageArea): File = when (area) {
        WorkspaceStorageArea.FILES -> filesDir(root)
        WorkspaceStorageArea.LINUX -> linuxDir(root)
    }

    fun cleanupAllTempDirs() {
        val roots = baseDir.listFiles()?.filter { it.isDirectory } ?: return
        for (dir in roots) {
            val root = dir.name
            if (!root.matches(ROOT_NAME_REGEX)) continue
            // PRoot temp files
            tempDir(root).let { if (it.exists()) it.deleteRecursively() }
            // Rootfs /tmp and /var/tmp
            File(linuxDir(root), "tmp").let { if (it.exists()) it.deleteRecursively() }
            File(linuxDir(root), "var/tmp").let { if (it.exists()) it.deleteRecursively() }
        }
    }

    companion object {
        private const val FILES_DIR = "files"
        private const val LINUX_DIR = "linux"
        private const val TEMP_DIR = "tmp"
        const val DEFAULT_COMMAND_TIMEOUT_MS = 30_000L
        const val DEFAULT_SESSION_YIELD_MS = 10_000L
        const val DEFAULT_SESSION_WAIT_MS = 10_000L

        private const val MAX_SESSION_YIELD_MS = 30_000L
        private const val TERMINATION_WAIT_MS = 1_000L
        private const val COMPLETED_SESSION_RETENTION_MS = 5 * 60_000L
        private const val MAX_ACTIVE_SHELL_SESSIONS_PER_WORKSPACE = 4

        /** Rootfs 内工作区文件区的挂载点 */
        const val ROOTFS_WORKSPACE_DIR = "/workspace"

        /** 由宿主机透传的内核伪文件系统, 只能通过 shell 访问 */
        val KERNEL_FS_MOUNTS = listOf("/dev", "/proc", "/sys")

        private val ROOT_NAME_REGEX = Regex("[A-Za-z0-9._-]+")
    }
}

/** Rootfs 内绝对路径在宿主机上的落点 */
private data class ManagedShellSession(
    val id: String,
    val root: String,
    val process: WorkspaceShellProcess,
    var stdoutCursor: Int = 0,
    var stderrCursor: Int = 0,
    var lastAccessAt: Long,
)

data class RootfsLocation(
    val rootDir: File,
    val relativePath: String,
)
