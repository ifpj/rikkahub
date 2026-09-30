package me.rerere.rikkahub.utils

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import me.rerere.workspace.RootfsPatcher
import me.rerere.workspace.WorkspaceBindMount
import java.io.File

object WorkspaceSharedStorage {
    @Suppress("DEPRECATION")
    fun rootDirectory(): File = Environment.getExternalStorageDirectory()

    val legacyPermissions = arrayOf(
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.WRITE_EXTERNAL_STORAGE,
    )

    fun hasAccess(context: Context): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        legacyPermissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun permissionIntent(context: Context): Intent {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        return Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            "package:${context.packageName}".toUri(),
        )
    }

    fun bindMounts(context: Context, enabled: Boolean, linuxDir: File): List<WorkspaceBindMount> {
        if (!enabled) return emptyList()
        check(hasAccess(context)) { "挂载手机共享存储需要授予文件访问权限，请在工作区设置中重新授权" }
        val root = rootDirectory()
        RootfsPatcher().ensureMountPointDirectory(linuxDir, root.absolutePath)
        return listOf(WorkspaceBindMount(root, root.absolutePath))
    }
}
