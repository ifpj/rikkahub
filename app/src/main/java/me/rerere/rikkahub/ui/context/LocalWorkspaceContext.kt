package me.rerere.rikkahub.ui.context

import android.net.Uri
import androidx.compose.runtime.staticCompositionLocalOf
import java.io.File

/**
 * 工作区图片路径解析上下文，在 ChatPage 中注入，供 Markdown 渲染器使用
 * 当 AI 回复中包含 `![图片](/workspace/xxx.png)` 时，将路径解析为 file:// URI
 */
data class WorkspaceImageContext(
    val workspaceId: String,
    val workspaceFilesDir: File,
)

val LocalWorkspaceImageContext = staticCompositionLocalOf<WorkspaceImageContext?> { null }

/**
 * 将 rootfs 工作区路径解析为 file:// URI，Coil 和 Web UI 均可直接加载。
 *
 * 支持的路径格式：
 * - /workspace/xxx.png → file:///data/data/.../files/workspaces/<uuid>/files/xxx.png
 *
 * 不支持的路径（/tmp/、/etc/ 等）返回 null，由调用方回退为原始路径。
 *
 * @param context 工作区图片上下文
 * @param rootfsPath rootfs 绝对路径，如 "/workspace/screenshot.png"
 * @return file:// URI 字符串，解析失败返回 null
 */
fun resolveWorkspaceImageUrl(context: WorkspaceImageContext, rootfsPath: String): String? {
    return when {
        rootfsPath.startsWith("/workspace/") -> {
            val relativePath = rootfsPath.removePrefix("/workspace/")
            val file = File(context.workspaceFilesDir, relativePath).canonicalFile
            // 安全检查：确保解析后的规范化路径仍在工作区文件目录内
            if (!file.path.startsWith(context.workspaceFilesDir.canonicalPath)) return null
            if (!file.isFile) return null
            Uri.fromFile(file).toString() // "file:///data/data/.../files/..." (Android 三斜杠格式，Web UI regex 可匹配)
        }
        else -> null // 非工作区路径，不处理
    }
}
