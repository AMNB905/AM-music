package com.am.music.data.search

import android.content.Context

/**
 * 搜索历史（最近 8 条关键词），只放本地 SharedPreferences：
 * 不上传、不落数据库表，清缓存时一并清掉。
 */
class SearchHistoryRepository(context: Context) {

    private val prefs = context.getSharedPreferences("search_history", Context.MODE_PRIVATE)

    fun recent(): List<String> {
        val raw = prefs.getString(KEY_HISTORY, null) ?: return emptyList()
        return raw.split(SEPARATOR).filter { it.isNotBlank() }.take(MAX_ITEMS)
    }

    fun push(keyword: String): List<String> {
        val word = keyword.trim()
        if (word.isEmpty()) return recent()
        val list = (listOf(word) + recent().filter { it != word }).take(MAX_ITEMS)
        prefs.edit().putString(KEY_HISTORY, list.joinToString(SEPARATOR)).apply()
        return list
    }

    fun remove(keyword: String): List<String> {
        val list = recent().filter { it != keyword }
        prefs.edit().putString(KEY_HISTORY, list.joinToString(SEPARATOR)).apply()
        return list
    }

    fun clear(): List<String> {
        prefs.edit().remove(KEY_HISTORY).apply()
        return emptyList()
    }

    private companion object {
        const val KEY_HISTORY = "keywords"
        const val SEPARATOR = "\u001F"
        const val MAX_ITEMS = 8
    }
}
