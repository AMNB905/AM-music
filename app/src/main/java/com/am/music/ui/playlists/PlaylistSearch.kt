package com.am.music.ui.playlists

import com.am.music.data.model.Song

/**
 * 歌单内搜索的匹配规则。
 *
 * 只做纯函数，是为了能在 JVM 单测里跑（不碰 Compose、不碰 Android）：
 * 搜索「对不对」是逻辑问题，不该靠手点去确认。
 *
 * 规则：
 * - 查询按空白拆成多个关键词，**每个**关键词都要命中（AND），可以一个词落在歌名、另一个落在歌手；
 * - 大小写不敏感，全角空格也当分隔；
 * - 歌名 / 歌手 / 专辑三栏拼在一起做包含匹配，不要求从词首开始（中文里本来也没「词首」）。
 */
internal fun searchTokens(query: String): List<String> =
    query.lowercase()
        .replace('\u3000', ' ')
        .trim()
        .split(Regex("\\s+"))
        .filter { it.isNotEmpty() }

internal fun matchesTokens(fields: List<String>, tokens: List<String>): Boolean {
    if (tokens.isEmpty()) return true
    val haystack = fields.joinToString(" ").lowercase()
    return tokens.all { haystack.contains(it) }
}

/** 通用版：字段怎么取由调用方给，方便单测用字符串列表直接验证。 */
internal fun <T> filterSongs(items: List<T>, query: String, fieldsOf: (T) -> List<String>): List<T> {
    val tokens = searchTokens(query)
    if (tokens.isEmpty()) return items
    return items.filter { matchesTokens(fieldsOf(it), tokens) }
}

/** 歌单里按「歌名 / 歌手 / 专辑」搜，保持原有顺序。 */
fun filterPlaylistSongs(songs: List<Song>, query: String): List<Song> =
    filterSongs(songs, query) { listOf(it.title, it.artist, it.album) }
