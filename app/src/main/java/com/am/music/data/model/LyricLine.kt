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
    /**
     * 合并句里每一段的原始时间（每段在源歌词里都是独立的一行）。
     *
     * 网易云的整句歌词经常把一句话按呼吸切成两三行（`[00:34.57]我们重复沉默`
     * `[00:37.64]这样子单方面的守候`），为了版面把它们拼成一行之后，
     * 各段自己的时间不能丢——否则整行会在第一段开口时就全部点亮，
     * 后面那段明明还没唱。这里把每段连同它的时间一起留下来。
     */
    val parts: List<LyricWord> = emptyList(),
) {
    /** 真正可用的逐字数据（至少要两个词，否则和整句没区别）。 */
    val hasWords: Boolean get() = words.size >= 2

    /**
     * 可以按时间推进高亮的单元：有逐字用逐字，没有逐字就用合并前的分段。
     * 两者都是「歌词源自己给出的时间」，不是按字数均分的猜测。
     */
    val timeline: List<LyricWord> get() = when {
        words.size >= 2 -> words
        parts.size >= 2 -> parts
        else -> emptyList()
    }

    /** 这一行能不能做「按时间推进」的扫光。 */
    val hasTimeline: Boolean get() = timeline.isNotEmpty()
}
