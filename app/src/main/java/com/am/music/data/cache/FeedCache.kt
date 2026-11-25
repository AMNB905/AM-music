package com.am.music.data.cache

import android.content.Context
import java.io.File

/**
 * 首页型数据（榜单 / 每日推荐 / 推荐歌单）的磁盘快照。
 *
 * 口径是「每个进程启动只拉一次」：`fresh` 记录本次进程里已经成功拉过的 key，
 * 同一 key 再次访问直接读盘，不再发请求；用户点刷新才强制重拉。
 * 这样切榜、来回进出页面都不会重新打接口，冷启动第一次打开也是秒出（先盘后网）。
 *
 * 存的是**接口原始 JSON**：解析仍走原来那套 parse，不必为缓存再写一遍序列化。
 */
class FeedCache(context: Context) {

    private val dir = File(context.filesDir, "feeds").apply { runCatching { mkdirs() } }
    private val fresh = HashSet<String>()

    private fun file(key: String) = File(dir, key.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".json")

    @Synchronized
    fun isFresh(key: String): Boolean = key in fresh

    @Synchronized
    fun markFresh(key: String) {
        fresh.add(key)
    }

    @Synchronized
    fun read(key: String): String? {
        val f = file(key)
        if (!f.exists()) return null
        return runCatching { f.readText() }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    @Synchronized
    fun write(key: String, body: String) {
        runCatching {
            val f = file(key)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(body)
            if (f.exists()) f.delete()
            if (!tmp.renameTo(f)) {
                f.writeText(body)
                tmp.delete()
            }
        }
    }

    /** 最后写入时间（设置页或调试用，便于判断缓存是否过期）。 */
    @Synchronized
    fun stamp(key: String): Long = file(key).let { if (it.exists()) it.lastModified() else 0L }

    /** 清空首页型缓存（设置页「清理缓存」时一并清掉）。 */
    @Synchronized
    fun clear() {
        runCatching { dir.listFiles()?.forEach { it.delete() } }
        fresh.clear()
    }
}
