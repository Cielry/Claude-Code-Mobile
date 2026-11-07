package com.lyuan.cc

import android.app.Application
import android.os.Build
import com.termux.terminal.TerminalColors
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CcmApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        installCrashHandler()
        // Claude Code 的标题 logo 用 256 色索引 174（#D78787）绘制，换成亮橙色。
        TerminalColors.COLOR_SCHEME.mDefaultColors[174] = 0xFFDA7758.toInt()
    }

    /**
     * 崩溃日志：写入应用私有目录，并另存一份到项目 ccm/（不可写时回退 Download/CCM），
     * 便于在设备上直接读取而不依赖 logcat 权限。
     */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val buffer = StringWriter()
                error.printStackTrace(PrintWriter(buffer))
                val version = runCatching {
                    packageManager.getPackageInfo(packageName, 0).versionName
                }.getOrDefault("?")
                val text = buildString {
                    append("time=")
                    append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
                    append('\n')
                    append("version=").append(version).append('\n')
                    append("sdk=").append(Build.VERSION.SDK_INT).append('\n')
                    append("device=").append(Build.MANUFACTURER).append(' ')
                    append(Build.MODEL).append('\n')
                    append("thread=").append(thread.name).append("\n\n")
                    append(buffer.toString())
                }
                runCatching { File(filesDir, "crash.txt").writeText(text) }
                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                CcmOutput.write(this@CcmApplication, "ccm-crash-$stamp.txt", text)
            }
            previous?.uncaughtException(thread, error)
        }
    }
}