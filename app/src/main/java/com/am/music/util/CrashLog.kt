package com.am.music.util

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * 崩溃落盘。
 *
 * 远端调试的死结在于：用户看到的是「应用已停止运行」，我们这边什么也拿不到。
 * 这里把未捕获异常的栈写进 filesDir/last_crash.txt，下次启动时由根布局弹出原样文本，
 * 用户截图或复制一段就能定位，不用连 USB、不用抓 logcat。
 *
 * 只做两件事：落盘 + 把异常继续交给系统默认处理（该弹系统崩溃框还是弹，不改变行为）。
 */
object CrashLog {

    private const val FILE_NAME = "last_crash.txt"

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(app, thread, error) }
            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                System.exit(10)
            }
        }
    }

    private fun write(context: Context, thread: Thread, error: Throwable) {
        val buffer = StringWriter()
        error.printStackTrace(PrintWriter(buffer))
        val head = buildString {
            append("时间: ").append(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date())).append('\n')
            append("线程: ").append(thread.name).append('\n')
            append("页面: ").append(CrashBreadcrumb.route.ifBlank { "?" })
                .append(" · ").append(CrashBreadcrumb.note.ifBlank { "-" }).append('\n')
            append("版本: ").append(android.os.Build.VERSION.SDK_INT).append(" / ")
            append(android.os.Build.MANUFACTURER).append(' ').append(android.os.Build.MODEL).append('\n')
            append('\n')
        }
        File(context.filesDir, FILE_NAME).writeText(head + buffer.toString())
    }

    /** 上一次崩溃的栈，没有就是 null。 */
    fun read(context: Context): String? {
        val file = File(context.applicationContext.filesDir, FILE_NAME)
        if (!file.exists()) return null
        return runCatching { file.readText() }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    fun clear(context: Context) {
        runCatching { File(context.applicationContext.filesDir, FILE_NAME).delete() }
    }
}
