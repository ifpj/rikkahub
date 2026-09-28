package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.DiffMetadata
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.toMetadata
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.generateUnifiedDiff
import me.rerere.workspace.DEFAULT_TERMINAL_COLUMNS
import me.rerere.workspace.DEFAULT_TERMINAL_ROWS
import me.rerere.workspace.WorkspaceCommandResult
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceLayout
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.WorkspaceShellSessionResult
import me.rerere.workspace.WorkspaceShellSessionStatus
import org.koin.java.KoinJavaComponent.getKoin
import java.io.ByteArrayOutputStream
import kotlin.uuid.Uuid

private const val SHELL_TIMEOUT_MAX_SECONDS = 600L
private const val SHELL_YIELD_MAX_MILLIS = 30_000L
private const val SHELL_INITIAL_YIELD_MILLIS = 1_000L
private const val SHELL_AUTO_WAIT_MILLIS = 1_000L
private const val SHELL_YIELD_TIME_KEY = "yield_time_ms"
private const val SHELL_MODEL_OUTPUT_MAX_CHARS = 16 * 1024
private const val MAX_READ_FILE_BYTES = 8L * 1024 * 1024

internal const val SHELL_TERMINAL_OUTPUT_METADATA_KEY = "shellTerminalOutput"
private const val SHELL_AUTO_CONTINUE_METADATA_KEY = "shellAutoContinue"

val WorkspaceToolDefaultApprovals: Map<String, Boolean> = mapOf(
    "workspace_read_file" to false,
    "workspace_write_file" to false,
    "workspace_edit_file" to false,
    "workspace_shell" to true,
    "workspace_shell_wait" to false,
    "workspace_shell_write" to false,
)

/** 直接写文件的工具, 只在支持 [WorkspaceCapabilities.fileWriteTools] 的工作区提供 */
val WorkspaceFileWriteToolNames: Set<String> = setOf("workspace_write_file", "workspace_edit_file")

fun resolveWorkspaceToolApproval(name: String, overrides: Map<String, Boolean>): Boolean =
    overrides[name] ?: WorkspaceToolDefaultApprovals[name] ?: false

suspend fun createWorkspaceTools(
    workspaceId: String?,
    workspaceRepository: WorkspaceRepository,
    cwd: String? = null,
    conversationId: Uuid? = null,
    backgroundShellManager: BackgroundShellManager? = null,
): List<Tool> {
    if (workspaceId.isNullOrBlank()) return emptyList()
    val layout = workspaceRepository.layout(workspaceId) ?: return emptyList()
    val workspace = workspaceRepository.getById(workspaceId) ?: return emptyList()
    val approvalOverrides = workspace.toolApprovalOverrides()
    fun needsApproval(name: String) = resolveWorkspaceToolApproval(name, approvalOverrides)

    val shellCwd = cwd?.let(layout::relativeToWorkspace)
    // 后台命令按会话归属, 没有会话时不提供
    val background = backgroundShellManager
        ?.takeIf { workspace.capabilities().backgroundShell }
        ?.let { manager -> conversationId?.let { BackgroundShell(manager, it) } }

    return buildList {
        add(createReadFileTool(workspaceId, layout, ::needsApproval, workspaceRepository))
        if (workspace.capabilities().fileWriteTools) {
            add(createWriteFileTool(workspaceId, layout, ::needsApproval, workspaceRepository))
            add(createEditFileTool(workspaceId, layout, ::needsApproval, workspaceRepository))
        }
        add(createShellTool(workspaceId, layout, ::needsApproval, workspaceRepository, shellCwd))
        add(createShellWaitTool(workspaceId, ::needsApproval, workspaceRepository))
        add(createShellWriteTool(workspaceId, ::needsApproval, workspaceRepository))
    }
}

private val IMAGE_EXTENSIONS = setOf(
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "heic", "heif", "avif", "ico",
)

private fun String.isImagePath(): Boolean =
    substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS

private fun createReadFileTool(
    workspaceId: String,
    layout: WorkspaceLayout,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_read_file",
    description = """
        Read a file using the assistant's bound workspace Rootfs. Paths must be absolute inside Rootfs.
        Use ${layout.workspaceDir} for the workspace files area.
        Supports UTF-8 text files and image files (png, jpg, jpeg, gif, webp, bmp, svg, heic, heif, avif, ico).
        NOTE: For image files, you can also embed them directly in your response using markdown
        image syntax: ![alt text](/workspace/path/to/image.png)
        This will display the image inline in the chat without needing to call this tool.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(layout, required = true)
            },
            required = listOf("path"),
        )
    },
    needsApproval = { needsApproval("workspace_read_file") },
    execute = {
        val path = it.jsonObject.absolutePath("path")
        if (path.isImagePath()) {
            workspaceRepository.readImageInRootfs(workspaceId, path)
        } else {
            val text = workspaceRepository.readTextInRootfs(workspaceId, path)
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("path", path)
                        put("text", text)
                    }.toString()
                )
            )
        }
    },
)

private fun createWriteFileTool(
    workspaceId: String,
    layout: WorkspaceLayout,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_write_file",
    description = """
        Write a UTF-8 text file using the assistant's bound workspace Rootfs. Paths must be absolute inside Rootfs.
        Use ${layout.workspaceDir} for the workspace files area.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(layout, required = true)
                put("text", buildJsonObject {
                    put("type", "string")
                    put("description", "UTF-8 text content to write")
                })
                put("overwrite", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Whether to overwrite an existing file. Defaults to true.")
                })
            },
            required = listOf("path", "text"),
        )
    },
    needsApproval = { needsApproval("workspace_write_file") || it.pathOutsideWritableRoots(layout, "path") },
    execute = {
        val params = it.jsonObject
        val path = params.absolutePath("path")
        val text = params.string("text") ?: error("text is required")
        val overwrite = params["overwrite"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true
        val entry = workspaceRepository.writeAbsoluteText(workspaceId, path, text, overwrite)
        listOf(UIMessagePart.Text(entry.toJson().toString()))
    },
)

private fun createEditFileTool(
    workspaceId: String,
    layout: WorkspaceLayout,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_edit_file",
    description = """
        Edit a UTF-8 text file using the assistant's bound workspace Rootfs. Paths must be absolute inside Rootfs.
        Use ${layout.workspaceDir} for the workspace files area.
        Provide old_text and new_text. By default old_text must occur exactly once; set replace_all=true to replace every occurrence.
        If no exact match is found, whitespace-tolerant line matching is attempted automatically.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(layout, required = true)
                put("old_text", buildJsonObject {
                    put("type", "string")
                    put("description", "Exact text to replace")
                })
                put("new_text", buildJsonObject {
                    put("type", "string")
                    put("description", "Replacement text")
                })
                put("replace_all", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Whether to replace every occurrence. Defaults to false.")
                })
            },
            required = listOf("path", "old_text", "new_text"),
        )
    },
    needsApproval = { needsApproval("workspace_edit_file") || it.pathOutsideWritableRoots(layout, "path") },
    execute = {
        val params = it.jsonObject
        val path = params.absolutePath("path")
        val oldText = params.string("old_text") ?: error("old_text is required")
        val newText = params.string("new_text") ?: error("new_text is required")
        val replaceAll = params["replace_all"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        require(oldText.isNotEmpty()) { "old_text must not be empty" }

        val original = workspaceRepository.readTextInRootfs(workspaceId, path)
        // 逐级尝试 exact -> line_trimmed -> block_anchor 替换器, 见 TextReplacers.kt
        val result = try {
            replaceText(original, oldText, newText, replaceAll)
        } catch (e: IllegalArgumentException) {
            error("${e.message} (path: $path)")
        }
        val entry = workspaceRepository.writeAbsoluteText(workspaceId, path, result.updated, overwrite = true)
        val diff = generateUnifiedDiff(original, result.updated, entry.path)
        listOf(
            UIMessagePart.Text(
                text = buildJsonObject {
                    put("path", entry.path)
                    put("replacements", result.replacements)
                    if (result.strategy != ExactReplacer.name) put("matchStrategy", result.strategy)
                    put("sizeBytes", entry.sizeBytes)
                    put("updatedAt", entry.updatedAt)
                }.toString(),
                // diff 存入 metadata 供 UI 渲染 diff view, 不会随工具结果发送给 API
                metadata = diff?.let { d -> DiffMetadata(diff = d).toMetadata() },
            )
        )
    },
)

private class BackgroundShell(val manager: BackgroundShellManager, val conversationId: Uuid)

private fun createShellTool(
    workspaceId: String,
    layout: WorkspaceLayout,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
    defaultCwd: String? = null,
    background: BackgroundShell? = null,
) = Tool(
    name = "workspace_shell",
    description = buildString {
        append("Run a shell command in the assistant's bound workspace Rootfs using an interactive PTY. ")
        append("The workspace files area is mounted at ${layout.workspaceDir}. ")
        append("Use cwd for a path relative to the workspace files root. ")
        if (!defaultCwd.isNullOrBlank()) {
            append("Defaults to '$defaultCwd'. ")
        }
        append("Requires Rootfs to be installed and ready. ")
        append("Long-running commands update this tool automatically until completion. ")
        append("Use workspace_shell_write when the process requests input or needs an interrupt.")
    },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("command", buildJsonObject {
                    put("type", "string")
                    put("description", "Shell command to run")
                })
                put("cwd", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        if (!defaultCwd.isNullOrBlank()) {
                            "Working directory relative to the workspace files root. Defaults to '$defaultCwd'."
                        } else {
                            "Working directory relative to the workspace files root. Defaults to root."
                        }
                    )
                })
                put("timeout", buildJsonObject {
                    put("type", "integer")
                    put(
                        "description",
                        buildString {
                            append("Command timeout in seconds. Defaults to 30, max $SHELL_TIMEOUT_MAX_SECONDS.")
                            if (background != null) append(" Ignored when running in the background.")
                        }
                    )
                })
                put(SHELL_YIELD_TIME_KEY, buildJsonObject {
                    put("type", "integer")
                    put(
                        "description",
                        "How long to wait before returning a background session. Defaults to " +
                            "$SHELL_INITIAL_YIELD_MILLIS, max $SHELL_YIELD_MAX_MILLIS."
                    )
                })
                put("rows", buildJsonObject {
                    put("type", "integer")
                    put("description", "Initial PTY height. Defaults to $DEFAULT_TERMINAL_ROWS.")
                })
                put("columns", buildJsonObject {
                    put("type", "integer")
                    put("description", "Initial PTY width. Defaults to $DEFAULT_TERMINAL_COLUMNS.")
                })
                put("auto_wait", buildJsonObject {
                    put("type", "boolean")
                    put(
                        "description",
                        "Automatically follow output until completion. Defaults to true; " +
                            "set false for interactive input."
                    )
                })
            },
            required = listOf("command"),
        )
    },
    needsApproval = { needsApproval("workspace_shell") },
    execute = {
        val params = it.jsonObject
        val command = params.string("command") ?: error("command is required")
        val cwd = layout.relativeToWorkspace(params.string("cwd") ?: defaultCwd.orEmpty())
        val timeoutMillis = params.string("timeout")?.toLongOrNull()
            ?.coerceIn(1L, SHELL_TIMEOUT_MAX_SECONDS)
            ?.times(1_000L)
            ?: WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS
        val yieldMillis = params.string(SHELL_YIELD_TIME_KEY)?.toLongOrNull()
            ?.coerceIn(0L, SHELL_YIELD_MAX_MILLIS)
            ?: SHELL_INITIAL_YIELD_MILLIS
        val terminalRows = params.string("rows")?.toIntOrNull()?.coerceIn(1, 500) ?: DEFAULT_TERMINAL_ROWS
        val terminalColumns = params.string("columns")?.toIntOrNull()?.coerceIn(1, 500)
            ?: DEFAULT_TERMINAL_COLUMNS
        val autoWait = params.boolean("auto_wait") ?: true
        val result = workspaceRepository.startCommandSession(
            id = workspaceId,
            command = command,
            cwd = cwd,
            timeoutMillis = timeoutMillis,
            yieldMillis = yieldMillis,
            terminalRows = terminalRows,
            terminalColumns = terminalColumns,
        )
        result.toMessageParts(autoContinue = autoWait)
    },
    continueExecution = { output -> continueWorkspaceShell(workspaceId, workspaceRepository, output) },
)

private fun createShellWaitTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_shell_wait",
    description = """
        Resume automatic observation of a running workspace_shell session and return new stdout/stderr.
        It follows the session until completion or until the PTY appears to request input.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("session_id", buildJsonObject {
                    put("type", "string")
                    put("description", "Session ID returned by workspace_shell")
                })
                put(SHELL_YIELD_TIME_KEY, buildJsonObject {
                    put("type", "integer")
                    put(
                        "description",
                        "How long to wait for new output. Defaults to " +
                            "$SHELL_AUTO_WAIT_MILLIS, max $SHELL_YIELD_MAX_MILLIS."
                    )
                })
            },
            required = listOf("session_id"),
        )
    },
    needsApproval = { needsApproval("workspace_shell_wait") },
    execute = {
        val params = it.jsonObject
        val sessionId = params.string("session_id") ?: error("session_id is required")
        val yieldMillis = params.string(SHELL_YIELD_TIME_KEY)?.toLongOrNull()
            ?.coerceIn(0L, SHELL_YIELD_MAX_MILLIS)
            ?: SHELL_AUTO_WAIT_MILLIS
        val result = workspaceRepository.waitCommandSession(workspaceId, sessionId, yieldMillis)
        result.toMessageParts()
    },
    continueExecution = { output -> continueWorkspaceShell(workspaceId, workspaceRepository, output) },
)

private fun createShellWriteTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_shell_write",
    description = """
        Interact with a running workspace_shell PTY: write text, send Ctrl+C or EOF, resize, or terminate the process.
        Approval follows the workspace tool settings.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("session_id", buildJsonObject {
                    put("type", "string")
                    put("description", "Session ID returned by workspace_shell")
                })
                put("stdin", buildJsonObject {
                    put("type", "string")
                    put("description", "Text to write to the process stdin")
                })
                put("close_stdin", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Close stdin after writing. Defaults to false.")
                })
                put("interrupt", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Send Ctrl+C/SIGINT to the PTY foreground process. Defaults to false.")
                })
                put("terminate", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Forcefully terminate the process. Defaults to false.")
                })
                put("rows", buildJsonObject {
                    put("type", "integer")
                    put("description", "New PTY height; columns must also be provided.")
                })
                put("columns", buildJsonObject {
                    put("type", "integer")
                    put("description", "New PTY width; rows must also be provided.")
                })
            },
            required = listOf("session_id"),
        )
    },
    needsApproval = { needsApproval("workspace_shell_write") },
    execute = {
        val params = it.jsonObject
        val sessionId = params.string("session_id") ?: error("session_id is required")
        val stdin = params.string("stdin")
        val closeStdin = params.boolean("close_stdin") ?: false
        val interrupt = params.boolean("interrupt") ?: false
        val terminate = params.boolean("terminate") ?: false
        val terminalRows = params.string("rows")?.toIntOrNull()?.coerceIn(1, 500)
        val terminalColumns = params.string("columns")?.toIntOrNull()?.coerceIn(1, 500)
        require((terminalRows == null) == (terminalColumns == null)) {
            "rows and columns must be provided together"
        }
        require(stdin != null || closeStdin || interrupt || terminate || terminalRows != null) {
            "At least one input, interrupt, resize, or termination action is required"
        }
        val result = workspaceRepository.updateCommandSession(
            id = workspaceId,
            sessionId = sessionId,
            stdin = stdin?.toByteArray(Charsets.UTF_8),
            closeStdin = closeStdin,
            interrupt = interrupt,
            terminate = terminate,
            terminalRows = terminalRows,
            terminalColumns = terminalColumns,
        )
        result.toMessageParts()
    },
    continueExecution = { output -> continueWorkspaceShell(workspaceId, workspaceRepository, output) },
)

private fun createShellKillTool(background: BackgroundShell) = Tool(
    name = "workspace_shell_kill",
    description = "Stop a background command started by workspace_shell with run_in_background, together with every process it started.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("task_id", buildJsonObject {
                    put("type", "string")
                    put("description", "Task id returned by workspace_shell")
                })
            },
            required = listOf("task_id"),
        )
    },
    execute = {
        val taskId = it.jsonObject.string("task_id") ?: error("task_id is required")
        val task = background.manager.kill(background.conversationId, taskId)
        listOf(UIMessagePart.Text(task.toJson().toString()))
    },
)

private fun BackgroundShellTask.toJson(extra: JsonObjectBuilder.() -> Unit = {}) = buildJsonObject {
    put("taskId", id)
    put("status", status.name.lowercase())
    exitCode?.let { put("exitCode", it) }
    put("logPath", logPath)
    extra()
}

private fun kotlinx.serialization.json.JsonObject.string(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull

private fun kotlinx.serialization.json.JsonObject.boolean(name: String): Boolean? =
    this[name]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()

private fun WorkspaceShellSessionResult.toJson() = buildJsonObject {
    put("status", status.name.lowercase())
    sessionId?.let { put("sessionId", it) }
    put("stdout", stdout)
    put("stderr", stderr)
    if (status == WorkspaceShellSessionStatus.COMPLETED) {
        exitCode?.let { put("exitCode", it) }
        put("timedOut", timedOut)
    }
    if (truncated) put("truncated", true)
    if (pty) put("pty", true)
}

private suspend fun continueWorkspaceShell(
    workspaceId: String,
    workspaceRepository: WorkspaceRepository,
    previousOutput: List<UIMessagePart>,
): List<UIMessagePart>? {
    val content = previousOutput.shellContent() ?: return null
    if (content.string("status") != "running") return null
    if (!previousOutput.shouldAutoContinueShell()) return null
    if (previousOutput.looksLikeTerminalPrompt()) return null
    val sessionId = content.string("sessionId") ?: return null
    val next = workspaceRepository.waitCommandSession(
        id = workspaceId,
        sessionId = sessionId,
        yieldMillis = SHELL_AUTO_WAIT_MILLIS,
    )
    return next.toMessageParts(previousOutput)
}

private fun WorkspaceShellSessionResult.toMessageParts(
    previousOutput: List<UIMessagePart> = emptyList(),
    autoContinue: Boolean = true,
): List<UIMessagePart> {
    val previousContent = previousOutput.shellContent()
    val previousRaw = previousOutput.filterIsInstance<UIMessagePart.Text>()
        .firstOrNull()
        ?.metadata
        ?.get(SHELL_TERMINAL_OUTPUT_METADATA_KEY)
        ?.jsonPrimitive
        ?.contentOrNull
        .orEmpty()
    val accumulatedStdout = previousContent?.string("stdout").orEmpty() + stdout.toPlainTerminalText()
    val accumulatedStderr = previousContent?.string("stderr").orEmpty() + stderr.toPlainTerminalText()
    val limitedOutput = limitShellModelOutput(accumulatedStdout, accumulatedStderr)
    val shouldAutoContinue = previousOutput.shouldAutoContinueShell(defaultValue = autoContinue)
    val merged = copy(
        stdout = limitedOutput.stdout,
        stderr = limitedOutput.stderr,
        truncated = truncated || limitedOutput.truncated || previousContent?.boolean("truncated") == true,
    )
    return listOf(
        UIMessagePart.Text(
            text = merged.toJson().toString(),
            metadata = buildJsonObject {
                put(SHELL_TERMINAL_OUTPUT_METADATA_KEY, previousRaw + stdout + stderr)
                put(SHELL_AUTO_CONTINUE_METADATA_KEY, shouldAutoContinue)
            },
        )
    )
}

private data class LimitedShellOutput(
    val stdout: String,
    val stderr: String,
    val truncated: Boolean,
)

private fun limitShellModelOutput(stdout: String, stderr: String): LimitedShellOutput {
    if (stdout.length + stderr.length <= SHELL_MODEL_OUTPUT_MAX_CHARS) {
        return LimitedShellOutput(stdout, stderr, truncated = false)
    }
    val stdoutBudget = when {
        stdout.isEmpty() -> 0
        stderr.isEmpty() -> SHELL_MODEL_OUTPUT_MAX_CHARS
        else -> SHELL_MODEL_OUTPUT_MAX_CHARS * 3 / 4
    }
    val stderrBudget = SHELL_MODEL_OUTPUT_MAX_CHARS - stdoutBudget
    return LimitedShellOutput(
        stdout = stdout.truncateTerminalOutput(stdoutBudget),
        stderr = stderr.truncateTerminalOutput(stderrBudget),
        truncated = true,
    )
}

private fun String.truncateTerminalOutput(maxChars: Int): String {
    if (length <= maxChars) return this
    if (maxChars <= 0) return ""
    val marker = "\n... terminal output truncated ...\n"
    if (maxChars <= marker.length) return takeLast(maxChars)
    val headLength = minOf(2_048, (maxChars - marker.length) / 4)
    val tailLength = maxChars - marker.length - headLength
    return take(headLength) + marker + takeLast(tailLength)
}

private fun List<UIMessagePart>.shellContent() =
    filterIsInstance<UIMessagePart.Text>()
        .firstOrNull()
        ?.text
        ?.let { text -> runCatching { JsonInstant.parseToJsonElement(text).jsonObject }.getOrNull() }

private fun List<UIMessagePart>.shouldAutoContinueShell(defaultValue: Boolean = true): Boolean =
    filterIsInstance<UIMessagePart.Text>()
        .firstOrNull()
        ?.metadata
        ?.get(SHELL_AUTO_CONTINUE_METADATA_KEY)
        ?.jsonPrimitive
        ?.contentOrNull
        ?.toBooleanStrictOrNull()
        ?: defaultValue

private fun List<UIMessagePart>.looksLikeTerminalPrompt(): Boolean {
    val raw = filterIsInstance<UIMessagePart.Text>()
        .firstOrNull()
        ?.metadata
        ?.get(SHELL_TERMINAL_OUTPUT_METADATA_KEY)
        ?.jsonPrimitive
        ?.contentOrNull
        ?.toPlainTerminalText()
        ?: return false
    if (raw.isEmpty() || raw.endsWith('\n')) return false
    val tail = raw.substringAfterLast('\n').trimEnd().lowercase()
    return tail.endsWith(':') || tail.endsWith('?') || tail.endsWith('>') || tail.endsWith('$') ||
        tail.endsWith('#') || tail.endsWith("password") || tail.endsWith("passphrase") ||
        tail.matches(Regex(".*(?:\\[[^]]*[yn][^]]*]|\\([^)]*[yn][^)]*\\))$"))
}

private val ANSI_OSC = Regex("\\u001B\\][^\\u0007]*(?:\\u0007|\\u001B\\\\)")
private val ANSI_CSI = Regex("\\u001B\\[[0-?]*[ -/]*[@-~]")

private fun String.toPlainTerminalText(): String {
    val withoutAnsi = replace(ANSI_OSC, "").replace(ANSI_CSI, "")
    return buildString(withoutAnsi.length) {
        withoutAnsi.forEachIndexed { index, char ->
            when {
                char == '\r' && withoutAnsi.getOrNull(index + 1) == '\n' -> Unit
                char == '\r' -> append('\n')
                char == '\b' -> if (isNotEmpty() && last() != '\n') deleteAt(lastIndex)
                char == '\n' || char == '\t' || char >= ' ' -> append(char)
            }
        }
    }
}

private suspend fun WorkspaceRepository.readTextInRootfs(
    workspaceId: String,
    path: String,
): String = readRootfsBuffer(workspaceId, path).toString(Charsets.UTF_8.name())

/**
 * 按环境内绝对路径读入内存。路径映射交给工作区后端, 本地工作区由它统一处理
 * /workspace、bind mount 与 Rootfs 内部路径。
 */
private suspend fun WorkspaceRepository.readRootfsBuffer(
    workspaceId: String,
    path: String,
): ByteArrayOutputStream {
    val size = absoluteFileSize(workspaceId, path)
    require(size <= MAX_READ_FILE_BYTES) {
        "File is too large to read: $path (${size / 1024 / 1024}MB, max ${MAX_READ_FILE_BYTES / 1024 / 1024}MB). Use shell commands like head, tail, or grep to read parts of it."
    }
    return ByteArrayOutputStream(size.toInt()).also { exportAbsoluteFile(workspaceId, path, it) }
}

private suspend fun WorkspaceRepository.readImageInRootfs(
    workspaceId: String,
    path: String,
): List<UIMessagePart> {
    val bytes = readRootfsBuffer(workspaceId, path).toByteArray()

    val filesManager = getKoin().get<FilesManager>()
    val uris = filesManager.createChatFilesByByteArrays(listOf(bytes))
    return listOf(
        UIMessagePart.Image(url = uris.first().toString()),
        UIMessagePart.Text(
            buildJsonObject {
                put("path", path)
                put("description", "Image file read successfully")
            }.toString()
        ),
    )
}

private fun kotlinx.serialization.json.JsonObject.absolutePath(name: String): String {
    val path = string(name)?.replace('\\', '/')?.trim() ?: error("$name is required")
    require(path.isNotBlank()) { "$name is required" }
    require(path.startsWith("/")) { "$name must be an absolute path inside Rootfs" }
    require(!path.contains('\u0000')) { "$name contains invalid character" }
    return path
}

// 免强制审批的可写安全区由工作区后端给出, 本地为工作区文件目录、临时目录和技能目录
private fun kotlinx.serialization.json.JsonElement.pathOutsideWritableRoots(
    layout: WorkspaceLayout,
    name: String,
): Boolean =
    runCatching {
        !layout.isWritable(jsonObject.absolutePath(name))
    }.getOrDefault(true)

private fun JsonObjectBuilder.putPathProperty(layout: WorkspaceLayout, required: Boolean) {
    put("path", buildJsonObject {
        put("type", "string")
        put(
            "description",
            if (required) {
                "Absolute path inside Rootfs. Use ${layout.workspaceDir} for the workspace files area."
            } else {
                "Optional absolute path inside Rootfs. Use ${layout.workspaceDir} for the workspace files area."
            }
        )
    })
}

private fun WorkspaceFileEntry.toJson() = buildJsonObject {
    put("path", path)
    put("name", name)
    put("isDirectory", isDirectory)
    put("sizeBytes", sizeBytes)
    put("updatedAt", updatedAt)
}
