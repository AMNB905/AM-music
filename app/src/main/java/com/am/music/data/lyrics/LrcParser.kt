package com.am.music.data.lyrics

import com.am.music.data.model.LyricLine
import java.util.Locale

/**
 * LRC 解析 + 净化。
 *
 * 净化是「网易云歌词看着落后」的主要本地解法：网易云的 LRC 在 [00:00]～[00:20] 常塞满
 * 「作词 / 作曲 / 编曲 / 人声录音 / 混音」这类制作名单，歌词真正开始前高亮一直停在这些行上，
 * 观感上就像歌词整段慢半拍。这些行对听歌没有意义，直接丢掉；末尾的「发行 / 特别支持」同理。
 *
 * 判定方式不堆词表，而是看「短标签 + 冒号」的形状：`^[^:：，。！？]{1,16}[:：]`
 * （「词：周杰伦」「Vocal edite：汝文博」「录音工程 : 玉乃井光紀」全中），真实歌词极少带冒号。
 */
object LrcParser {

    private val TIME_TAG = Regex("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")
    private val OFFSET_TAG = Regex("^\\[offset:([+-]?\\d+)]$", RegexOption.IGNORE_CASE)
    private val METADATA_TAG = Regex("^\\[(ti|ar|al|by|re|ve|tool|au|length):.*]$", RegexOption.IGNORE_CASE)

    /** 制作名单行：短标签 + 冒号。 */
    private val LABEL_LINE = Regex("^[^:：，。！？,.!?]{1,16}[:：]")

    /** 「歌名 - 歌手」这类标题行（只在开头 5 秒内判定，避免误杀正式歌词）。 */
    private val TITLE_LINE = Regex("\\s[-–—]\\s")

    private val PLACEHOLDER = listOf("纯音乐", "请欣赏", "暂无歌词", "没有填词", "该歌曲无歌词")

    fun parse(content: String): List<LyricLine> {
        var offset = 0L
        val lines = mutableListOf<LyricLine>()

        content.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEach

            OFFSET_TAG.find(line)?.let {
                offset = it.groupValues[1].toLongOrNull() ?: 0L
                return@forEach
            }
            if (METADATA_TAG.matches(line)) return@forEach

            val tags = TIME_TAG.findAll(line).toList()
            if (tags.isEmpty()) return@forEach

            val text = line.substring(tags.last().range.last + 1).trim()
            for (tag in tags) {
                val minutes = tag.groupValues[1].toLong()
                val seconds = tag.groupValues[2].toLong()
                val fraction = when (val digits = tag.groupValues[3]) {
                    "" -> 0L
                    else -> digits.toLong() * when (digits.length) {
                        1 -> 100L
                        2 -> 10L
                        else -> 1L
                    }
                }
                val timeMs = (minutes * 60_000 + seconds * 1_000 + fraction - offset)
                    .coerceAtLeast(0L)
                lines += LyricLine(timeMs, text)
            }
        }

        return mergeFragments(
            lines
                .filterNot { isNoise(it.text, it.timeMs) }
                .distinctBy { it.timeMs }
                .sortedBy { it.timeMs }
        )
    }

    /**
     * 把「看」「听」「就让我」这类被切碎的短句拼回整句。
     *
     * 网易云的 LRC 经常按演唱断点切得很碎（`[00:34.10]看` + `[00:35.74]幻想中的行星`），
     * 单行只有两三个字时会一直占着高亮位，看着像「歌词在乱跳」。拼句后每行读得完整，
     * 行数也随之变少，正是极简歌词页要的效果。
     */
    private fun mergeFragments(lines: List<LyricLine>): List<LyricLine> {
        val merged = mutableListOf<LyricLine>()
        var runLength = 0
        for (line in lines) {
            val last = merged.lastOrNull()
            val gap = if (last == null) Long.MAX_VALUE else line.timeMs - last.timeMs
            val canMerge = last != null &&
                runLength < 3 &&
                last.text.length <= 6 &&
                last.text.length + line.text.length <= 20 &&
                gap in 1..3_500L &&
                line.text.length >= 2 &&
                line.text.none { it in SENTENCE_END } &&
                last.text.none { it in SENTENCE_END }
            if (canMerge) {
                merged[merged.size - 1] = last.copy(text = last.text + line.text)
                runLength++
            } else {
                merged += line
                runLength = 0
            }
        }
        return merged
    }

    /**
     * 反向写出：把解析后（可能已被本地对齐调整过时间轴）的歌词重新序列化成标准 LRC。
     * 只有内存里有一份完整时间轴才写得回来，所以缓存（含对齐结果）用这个口径落盘。
     */
    fun serialize(lines: List<LyricLine>): String = lines.joinToString("\n") { line ->
        val centis = (line.timeMs.coerceAtLeast(0L) + 5L) / 10L
        String.format(
            Locale.US,
            "[%02d:%02d.%02d]%s",
            centis / 6000L,
            (centis % 6000L) / 100L,
            centis % 100L,
            line.text,
        )
    }

    private const val SENTENCE_END = "。！？.!?，,；;：:"

    private fun isNoise(text: String, timeMs: Long): Boolean {
        val value = text.trim()
        if (value.isEmpty()) return true
        if (value.all { !it.isLetterOrDigit() }) return true
        if (LABEL_LINE.containsMatchIn(value)) return true
        if (PLACEHOLDER.any { value.contains(it) }) return true
        if (timeMs < 5_000 && value.length > 6 && TITLE_LINE.containsMatchIn(value)) return true
        return false
    }
}
