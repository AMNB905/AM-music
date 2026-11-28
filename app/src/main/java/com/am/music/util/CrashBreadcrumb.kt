package com.am.music.util

/**
 * 崩溃现场的面包屑：崩之前人在哪个页面、最后做了什么。
 *
 * 混淆后的栈只说明「崩了」，不说明「在哪儿崩的」；把这两个字段写进
 * `last_crash.txt` 的头部，下一次截图就能直接对上页面，不用猜。
 */
object CrashBreadcrumb {
    @Volatile
    var route: String = ""

    @Volatile
    var note: String = ""

    fun visit(route: String, note: String = "") {
        this.route = route
        if (note.isNotEmpty()) this.note = note
    }

    fun action(note: String) {
        this.note = note
    }
}
