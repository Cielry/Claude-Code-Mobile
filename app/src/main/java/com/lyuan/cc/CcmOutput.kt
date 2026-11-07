package com.lyuan.cc

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * 调试与诊断输出目录。
 *
 * 优先写入应用外部专属目录（Android/data/<包名>/files/ccm），便于通过文件管理器或
 * adb 查看；该位置不可用时回退到 Download/CCM（MediaStore）。
 */
object CcmOutput {

    fun outputDir(context: Context): File =
        context.getExternalFilesDir("ccm") ?: File(context.filesDir, "ccm")

    fun write(context: Context, name: String, text: String) {
        val ok = runCatching {
            val dir = outputDir(context)
            dir.mkdirs()
            File(dir, name).writeText(text)
        }.isSuccess
        if (!ok) writeDownload(context, name, text)
    }

    private fun writeDownload(context: Context, name: String, text: String) {
        runCatching {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/CCM",
                )
            }
            val uri = context.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                values,
            )
            uri?.let {
                context.contentResolver.openOutputStream(it)?.use { output ->
                    output.write(text.toByteArray())
                }
            }
        }
    }
}