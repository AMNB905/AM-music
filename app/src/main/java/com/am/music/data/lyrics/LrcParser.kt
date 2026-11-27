package com.am.music.data.lyrics

import com.am.music.data.model.LyricLine
import com.am.music.data.model.LyricWord
import java.util.Locale

/**
 * LRC 解析 + 净化。
 *
 * 两种时间轴都吃：
 * - 普通 LRC：`[00:28.95]故事的小黄花`（网易云就是这个粒度）；
 * - 逐字 LRC：`[00:28.95]故[00:29.20]事[00:29.41]的...`（QQ 音源给的就是这个粒度）。
 *
 * 逐字那条会被完整保留成 [LyricLine.words]，歌词页据此做真正的逐字扫光；
 * 只有整句时间的行不伪造逐字，宁可不扫光也不让歌词看起来「跑在声音前面」。
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

            // tags[i] 之后、tags[i+1] 之前的那段文字
            val segments = tags.indices.map { i ->
                val from = tags[i].range.last + 1
                val to = if (i + 1 < tags.size) tags[i + 1].range.first else line.length
                line.substring(from, to)
            }

            // 逐字格式：最后一个时间标签之前就出现文字（`晴[00:00.16]天`）。
            // `[00:12.00][00:24.00]副歌` 这种「同一句挂多个时间」的前几段都是空的，不算逐字。
            val wordMode = tags.size >= 2 && segments.take(tags.size - 1).any { it.isNotBlank() }

            if (wordMode) {
                val text = segments.joinToString("")
                if (text.isBlank()) return@forEach
                val words = segments.mapIndexedNotNull { i, seg ->
                    if (seg.isEmpty()) {
                        null
                    } else {
                        LyricWord((toMillis(tags[i]) - offset).coerceAtLeast(0L), seg)
                    }
                }
                lines += LyricLine(
                    timeMs = (toMillis(tags[0]) - offset).coerceAtLeast(0L),
                    text = text.trim(),
                    words = words,
                )
            } else {
                val text = segments.last().trim()
                for (tag in tags) {
                    lines += LyricLine((toMillis(tag) - offset).coerceAtLeast(0L), text)
                }
            }
        }

        return mergeFragments(
            lines
                .filterNot { isNoise(it.text, it.timeMs) }
                .distinctBy { it.timeMs }
                .sortedBy { it.timeMs }
        )
    }

    private fun toMillis(tag: MatchResult): Long {
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
        return minutes * 60_000 + seconds * 1_000 + fraction
    }

    /**
     * 把「看」「听」「就让我」这类被切碎的短句拼回整句。
     *
     * 网易云的 LRC 经常按演唱断点切得很碎（`[00:34.10]看` + `[00:35.74]幻想中的行星`），
     * 单行只有两三个字时会一直占着高亮位，看着像「歌词在乱跳」。拼句后每行读得完整，
     * 行数也随之变少，正是极简歌词页要的效果。
     *
     * 带逐字时间轴的行一律不动：它的碎是「演唱粒度」，不是排版问题。
     */
    private fun mergeFragments(lines: List<LyricLine>): List<LyricLine> {
        val merged = mutableListOf<LyricLine>()
        var runLength = 0
        for (line in lines) {
            val last = merged.lastOrNull()
            val gap = if (last == null) Long.MAX_VALUE else line.timeMs - last.timeMs
            val canMerge = last != null &&
                !last.hasWords &&
                !line.hasWords &&
                runLength < 3 &&
                last.text.length <= 6 &&
                last.text.length + line.text.length <= 20 &&
                gap in 1..3_500L &&
                line.text.length >= 2 &&
                line.text.none { it in SENTENCE_END } &&
                last.text.none { it in SENTENCE_END } &&
                // 只拼中文碎片：英文单词之间本来就有空格断句，
                // 把 "hello" + "world" 粘成 "helloworld" 是解析事故，不是修排版
                isCjkFragment(last.text) &&
                isCjkFragment(line.text)
            if (canMerge) {
                // 拼句不丢时间：把这一行本该单独占用的时间记进 parts，
                // 歌词页据此按段推进高亮，而不是整行在第一段就全亮。
                val parts = if (last.parts.isEmpty()) {
                    listOf(LyricWord(last.timeMs, last.text))
                } else {
                    last.parts
                } + LyricWord(line.timeMs, line.text)
                merged[merged.size - 1] = last.copy(text = last.text + line.text, parts = parts)
                runLength++
            } else {
                merged += line
                runLength = 0
            }
        }
        return merged
    }

    /**
     * 反向写出：把解析后（可能已被本地对齐调整过时间轴）的歌词重新序列化成 LRC。
     * 只有内存里有一份完整时间轴才写得回来，所以缓存（含对齐结果）用这个口径落盘。
     *
     * 逐字行按「每个词前面挂自己的时间」写回，重新解析时能原样恢复 words，
     * 不需要为缓存另建格式。拼句行则按段拆回多行写——每段原本就是独立的一行。
     */
    fun serialize(lines: List<LyricLine>): String = lines.joinToString("\n") { line ->
        when {
            line.hasWords -> buildString {
                line.words.forEach { word ->
                    append(timeTag(word.timeMs))
                    append(word.text)
                }
            }
            line.parts.size >= 2 -> line.parts.joinToString("\n") { part ->
                timeTag(part.timeMs) + part.text
            }
            else -> timeTag(line.timeMs) + line.text
        }
    }

    private fun timeTag(timeMs: Long): String {
        val ms = timeMs.coerceAtLeast(0L)
        return String.format(
            Locale.US,
            "[%02d:%02d.%03d]",
            ms / 60_000L,
            (ms % 60_000L) / 1_000L,
            ms % 1_000L,
        )
    }

    private const val SENTENCE_END = "。！？.!?，,；;：:"

    /** 至少含一个汉字、且不含拉丁字母——用来判断「这是被切碎的中文短句」。 */
    private fun isCjkFragment(text: String): Boolean =
        text.any { it.code in 0x4E00..0x9FFF } && text.none { it in 'a'..'z' || it in 'A'..'Z' }

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
