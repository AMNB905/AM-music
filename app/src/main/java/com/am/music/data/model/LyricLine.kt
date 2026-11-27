package com.am.music.data.model

/**
 * 歌词里的一个「字 / 词组」以及它开口的时间。
 *
 * QQ 音源返回的是逐字 LRC（`[00:00.00]晴[00:00.16]天`），解析后每一段都带自己的时间。
 * 网易云只给到整句，所以这里允许 [LyricLine.words] 为空——没有逐字数据时不假装有，
 * 歌词页就不做逐字扫光，宁可不炫也不能让进度看起来对不上。
 */
data class LyricWord(
    val timeMs: Long,
    val text: String,
)

data class LyricLine(
    val timeMs: Long,
    val text: String,
    /** 逐字时间轴；为空 = 这一行只有整句时间。 */
    val words: List<LyricWord> = emptyList(),
) {
    /** 真正可用的逐字数据（至少要两个词，否则和整句没区别）。 */
    val hasWords: Boolean get() = words.size >= 2
}
