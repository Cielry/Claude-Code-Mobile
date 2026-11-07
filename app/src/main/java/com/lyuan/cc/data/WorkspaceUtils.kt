package com.lyuan.cc.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File

object WorkspaceUtils {

    /** 默认工作区：应用私有目录下的工作目录。 */
    fun defaultDir(context: Context): File =
        File(context.filesDir, "rt/work").apply { mkdirs() }

    /**
     * 把 SAF 的 tree URI 映射为文件系统路径。
     * 仅支持 externalstorage 文档提供器（primary / 其他卷）。
     */
    fun pathFromTreeUri(uri: Uri): String? {
        return try {
            val docId = DocumentsContract.getTreeDocumentId(uri)
            val parts = docId.split(":", limit = 2)
            val volume = parts[0]
            val relative = if (parts.size > 1) parts[1] else ""
            when (volume) {
                "primary" -> File("/storage/emulated/0", relative).absolutePath
                "home" -> File("/storage/emulated/0/Documents", relative).absolutePath
                else -> File("/storage/$volume", relative).absolutePath
            }
        } catch (_: Throwable) {
            null
        }
    }

    fun isUsable(path: String?): Boolean {
        val dir = path?.let(::File) ?: return false
        return dir.isDirectory && dir.canRead()
    }
}