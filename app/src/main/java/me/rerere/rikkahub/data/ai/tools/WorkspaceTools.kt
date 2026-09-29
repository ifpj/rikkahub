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

private const val SHELL_TIMEOUT_MAX_MILLIS = 600_000L
private const val SHELL_YIELD_MAX_MILLIS = 30_000L
private const val SHELL_INITIAL_YIELD_MILLIS = 1_000L
private const val SHELL_YIELD_TIME_KEY = "yield_time_ms"
private const val SHELL_MODEL_OUTPUT_MAX_CHARS = 16 * 1024
private const val MAX_READ_FILE_BYTES = 8L * 1024 * 1024

internal const val SHELL_TERMINAL_OUTPUT_METADATA_KEY = "shellTerminalOutput"

val WorkspaceToolDefaultApprovals: Map<String, Boolean> = mapOf(
    "workspace_read_file" to false,
    "workspace_write_file" to false,
    "workspace_edit_file" to false,
    "workspace_exec_command" to true,
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
        add(createShellExecCommandTool(workspaceId, layout, ::needsApproval, workspaceRepository, shellCwd))
        add(createShellWriteStdinTool(workspaceId, workspaceRepository))
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

private fun createShellExecCommandTool(
    workspaceId: String,
    layout: WorkspaceLayout,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
    defaultCwd: String? = null,
    background: BackgroundShell? = null,
) = Tool(
    name = "workspace_exec_command",
    description = buildString {
        append("Run a shell command in the assistant's bound workspace Rootfs using an interactive PTY. ")
        append("The workspace files area is mounted at ${layout.workspaceDir}. ")
        append("Use workdir for a path relative to the workspace files root. ")
        if (!defaultCwd.isNullOrBlank()) {
            append("Defaults to '$defaultCwd'. ")
        }
        append("Requires Rootfs to be installed and ready. ")
        append("If the command is still running after yield_time_ms, use workspace_write_stdin with its session_id. ")
        append("Do not start a second command just to continue an existing session.")
    },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("command", buildJsonObject {
                    put("type", "string")
                    put("description", "Shell command to run")
                })
                put("workdir", buildJsonObject {
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
                put("timeout_ms", buildJsonObject {
                    put("type", "integer")
                    put(
                        "description",
                        "Command timeout in milliseconds. Defaults to 30000, max $SHELL_TIMEOUT_MAX_MILLIS."
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
                put("max_output_chars", buildJsonObject {
                    put("type", "integer")
                    put("description", "Maximum output characters returned to the model. Defaults to $SHELL_MODEL_OUTPUT_MAX_CHARS.")
                })
                put("rows", buildJsonObject {
                    put("type", "integer")
                    put("description", "Initial PTY height. Defaults to $DEFAULT_TERMINAL_ROWS.")
                })
                put("columns", buildJsonObject {
                    put("type", "integer")
                    put("description", "Initial PTY width. Defaults to $DEFAULT_TERMINAL_COLUMNS.")
                })
            },
            required = listOf("command"),
        )
    },
    needsApproval = { needsApproval("workspace_exec_command") },
    execute = {
        val params = it.jsonObject
        val command = params.string("command") ?: error("command is required")
        val cwd = layout.relativeToWorkspace(params.string("workdir") ?: defaultCwd.orEmpty())
        val timeoutMillis = params.string("timeout_ms")?.toLongOrNull()
            ?.coerceIn(1_000L, SHELL_TIMEOUT_MAX_MILLIS)
            ?: WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS
        val yieldMillis = params.string(SHELL_YIELD_TIME_KEY)?.toLongOrNull()
            ?.coerceIn(0L, SHELL_YIELD_MAX_MILLIS)
            ?: SHELL_INITIAL_YIELD_MILLIS
        val maxOutputChars = params.string("max_output_chars")?.toIntOrNull()
            ?.coerceIn(256, SHELL_MODEL_OUTPUT_MAX_CHARS)
            ?: SHELL_MODEL_OUTPUT_MAX_CHARS
        val terminalRows = params.string("rows")?.toIntOrNull()?.coerceIn(1, 500) ?: DEFAULT_TERMINAL_ROWS
        val terminalColumns = params.string("columns")?.toIntOrNull()?.coerceIn(1, 500)
            ?: DEFAULT_TERMINAL_COLUMNS
        val result = workspaceRepository.startCommandSession(
            id = workspaceId,
            command = command,
            cwd = cwd,
            timeoutMillis = timeoutMillis,
            yieldMillis = yieldMillis,
            terminalRows = terminalRows,
            terminalColumns = terminalColumns,
        )
        result.toMessageParts(maxOutputChars = maxOutputChars)
    },
)

private fun createShellWriteStdinTool(
    workspaceId: String,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_write_stdin",
    description = """
        Continue an existing workspace_exec_command PTY session. Use empty chars to poll output,
        chars to send input, interrupt for Ctrl+C, close_stdin for EOF, or terminate to stop the process.
    """.trimIndent().replace("\n", " "),
    parameters = ::workspaceWriteStdinSchema,
    needsApproval = { false },
    execute = {
        val params = it.jsonObject
        val sessionId = params.string("session_id")?.takeIf { value -> value.isNotBlank() }
            ?: error("missing required param: session_id")
        val chars = params.string("chars")
        val yieldMillis = params.string(SHELL_YIELD_TIME_KEY)?.toLongOrNull()
            ?.coerceIn(0L, SHELL_YIELD_MAX_MILLIS)
            ?: SHELL_INITIAL_YIELD_MILLIS
        val closeStdin = params.boolean("close_stdin") ?: false
        val interrupt = params.boolean("interrupt") ?: false
        val terminate = params.boolean("terminate") ?: false
        val terminalRows = params.string("rows")?.toIntOrNull()?.coerceIn(1, 500)
        val terminalColumns = params.string("columns")?.toIntOrNull()?.coerceIn(1, 500)
        require((terminalRows == null) == (terminalColumns == null)) {
            "rows and columns must be provided together"
        }
        require(chars != null || closeStdin || interrupt || terminate || terminalRows != null) {
            "At least one poll, input, interrupt, resize, or termination action is required"
        }
        val result = workspaceRepository.updateCommandSession(
            id = workspaceId,
            sessionId = sessionId,
            stdin = chars?.toByteArray(Charsets.UTF_8),
            closeStdin = closeStdin,
            interrupt = interrupt,
            terminate = terminate,
            terminalRows = terminalRows,
            terminalColumns = terminalColumns,
            yieldMillis = yieldMillis,
        )
        result.toMessageParts()
    },
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

internal fun workspaceWriteStdinSchema() = InputSchema.Obj(
    properties = buildJsonObject {
        put("session_id", buildJsonObject {
            put("type", "string")
            put("description", "Session ID returned by workspace_exec_command")
        })
        put("chars", buildJsonObject {
            put("type", "string")
            put("description", "Text to write to the process stdin")
        })
        put(SHELL_YIELD_TIME_KEY, buildJsonObject {
            put("type", "integer")
            put(
                "description",
                "How long to wait for output after writing or polling. Defaults to " +
                    "$SHELL_INITIAL_YIELD_MILLIS, max $SHELL_YIELD_MAX_MILLIS."
            )
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

private fun WorkspaceShellSessionResult.toMessageParts(
    maxOutputChars: Int = SHELL_MODEL_OUTPUT_MAX_CHARS,
): List<UIMessagePart> {
    val limitedOutput = limitShellModelOutput(
        stdout.toPlainTerminalText(),
        stderr.toPlainTerminalText(),
        maxOutputChars,
    )
    val merged = copy(
        stdout = limitedOutput.stdout,
        stderr = limitedOutput.stderr,
        truncated = truncated || limitedOutput.truncated,
    )
    return listOf(
        UIMessagePart.Text(
            text = merged.toJson().toString(),
            metadata = buildJsonObject {
                put(SHELL_TERMINAL_OUTPUT_METADATA_KEY, stdout + stderr)
            },
        )
    )
}

private data class LimitedShellOutput(
    val stdout: String,
    val stderr: String,
    val truncated: Boolean,
)

private fun limitShellModelOutput(stdout: String, stderr: String, maxChars: Int): LimitedShellOutput {
    if (stdout.length + stderr.length <= maxChars) {
        return LimitedShellOutput(stdout, stderr, truncated = false)
    }
    val stdoutBudget = when {
        stdout.isEmpty() -> 0
        stderr.isEmpty() -> maxChars
        else -> maxChars * 3 / 4
    }
    val stderrBudget = maxChars - stdoutBudget
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
