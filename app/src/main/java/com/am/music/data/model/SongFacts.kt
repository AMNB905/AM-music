package com.am.music.data.model

/**
 * 歌曲资料（来自新接口的百科能力）。
 *
 * 只承载「能一行一行读完」的东西：键值行 + 一段简介。
 * 不把原始结构整个搬进 UI——界面上多一块能滚的地区，就不简洁了。
 */
data class SongFacts(
    val title: String,
    val rows: List<Pair<String, String>>,
    val desc: String?,
)
