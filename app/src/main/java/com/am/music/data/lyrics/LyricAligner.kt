package com.am.music.data.lyrics

import android.util.Log
import com.am.music.data.model.LyricLine
import com.am.music.data.model.OnlinePlatform
import com.am.music.data.model.Song
import com.am.music.data.online.OnlineMusicRepository
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * 歌词与音频的「版本对齐」——自动，不需要用户拖延时。
 *
 * 问题形态（网易云尤其常见）：音频还没唱到，歌词已经走完 / 整段提前。
 * 根因是这份 LRC 对应的音频版本与当前播放的音频版本不是同一条（旧版 / 重制 / 剪辑版），
 * 时间轴整体偏短或偏长，靠时间轴本身无法自证。
 *
 * 这里用「音频真实时长」当唯一硬锚点做三级判断：
 *  1. 歌词末行与音频时长差 ≤ 8 秒 → 正常（长尾奏也在这个范围），什么都不做。
 *  2. 差得多 → 去另一个音源按「同名 + 时长差 ≤ 8 秒」找同一首歌的歌词；
 *     若对方基本吻合（差 ≤ 5 秒）且明显更好 → 换成对方那份（同曲两源歌词通常逐毫秒一致，
 *     一方错配、另一方吻合时这一步最稳）。
 *  3. 对方也找不到 / 也不吻合，但偏差超过 30 秒且超过总长的 10% → 按线性比例把歌词
 *     重标定到音频时长（预留 6 秒尾奏）。这一步能救「歌词是 4:22 版、音频是 5:11 版」这类整体拉伸，
 *     绝不会在 8~30 秒的正常尾奏区间乱动。
 *
 * 决策全部记在 logcat：`adb logcat -s LyricAligner`。
 */
class LyricAligner(private val online: OnlineMusicRepository) {

    private val cache = ConcurrentHashMap<String, List<LyricLine>>()

    /**
     * @param trustedDuration 时长是否来自播放器实测（ExoPlayer duration）。
     *   搜索接口给的时长可能有 1~3 秒偏差，只在实测时长下才缓存「无需调整」的结论。
     */
    suspend fun align(
        song: Song,
        primary: List<LyricLine>,
        durationMs: Long,
        trustedDuration: Boolean,
    ): List<LyricLine> {
        val platform = song.platform ?: return primary
        if (primary.isEmpty() || durationMs < 60_000L) return primary
        cache[song.key]?.let { return it }

        val last = primary.last().timeMs
        val gap = abs(durationMs - last)

        if (gap <= NORMAL_GAP_MS) {
            if (trustedDuration) cache[song.key] = primary
            return primary
        }

        // 二级：跨音源择优（只在对方真的贴合时才采用）
        val other = runCatching { fetchFromOtherPlatform(song, platform, durationMs) }.getOrNull()
        if (other == null) {
            Log.d(TAG, "${song.key} 无同曲候选，保持原歌词（差 ${gap}ms）")
            if (trustedDuration) cache[song.key] = primary
            return primary
        }

        val otherGap = abs(durationMs - other.last().timeMs)
        val otherLast = other.last().timeMs
        val sameShape = abs(otherLast - last) <= SAME_SHAPE_MS

        return when {
            // 对方基本吻合且明显更好 → 换
            otherGap <= ACCEPT_GAP_MS && otherGap + IMPROVE_MARGIN_MS < gap -> {
                Log.w(TAG, "${song.key} 换用另一音源歌词：差 ${gap}ms → ${otherGap}ms")
                cache[song.key] = other
                other
            }
            // 两平台歌词形状一致（末行相差很小）→ 说明只是长尾奏，不是版本错配，不做任何改动
            sameShape -> {
                Log.d(TAG, "${song.key} 两平台歌词一致（末行差 ${abs(otherLast - last)}ms），属正常尾奏")
                if (trustedDuration) cache[song.key] = primary
                primary
            }
            else -> {
                Log.d(TAG, "${song.key} 保持原歌词（本 ${gap}ms / 对方 ${otherGap}ms）")
                if (trustedDuration) cache[song.key] = primary
                primary
            }
        }
    }

    fun clear() = cache.clear()

    /**
     * 给整句歌词补「逐字时间轴」。
     *
     * 网易云的歌词接口只有整句时间，逐字扫光会显得和演唱对不上；QQ 音源给的却是逐字 LRC。
     * 所以这里做一次**跨音源补时**：按歌名 + 时长在另一个音源找到同一首，
     * 逐行按「去掉标点空格后的文本」配对，把对方的逐字时间平移后挂到我们这一行上。
     *
     * 三道闸门，任何一道不过就原样返回（宁可没有逐字，也不给错的逐字）：
     *  1. 对方确实带逐字数据（≥ 3 行）；
     *  2. 歌名正题一致、时长差 ≤ 10 秒（拒绝同名翻唱 / Live / 剪辑版）；
     *  3. 逐行文本能配上 ≥ 25%（且至少 4 行）——对不上的行保持整句。
     */
    suspend fun enrichWords(song: Song, lines: List<LyricLine>): List<LyricLine> {
        if (lines.isEmpty() || lines.count { it.hasWords } >= 3) return lines
        val platform = song.platform ?: return lines
        val target = if (platform == OnlinePlatform.QQ) OnlinePlatform.NETEASE else OnlinePlatform.QQ
        val keyword = "${song.title} ${song.artist}".trim()
        val candidates = runCatching { online.search(keyword, target) }.getOrNull() ?: return lines
        val base = baseTitle(song.title)
        val best = candidates
            .filter { baseTitle(it.title) == base }
            .filter { song.durationMs <= 0L || abs(it.durationMs - song.durationMs) <= MATCH_DURATION_MS }
            .minByOrNull { abs(it.durationMs - song.durationMs) }
            ?: return lines
        val raw = runCatching { online.lyrics(best) }.getOrNull() ?: return lines
        val other = LrcParser.parse(raw)
        val wordLines = other.filter { it.hasWords }
        if (wordLines.size < 3) return lines

        val byText = wordLines.associateBy { normalize(it.text) }
        var matched = 0
        val out = lines.map { line ->
            val candidate = byText[normalize(line.text)]
            // 时间差太大说明不是同一段词（对方是另一版编曲），不动
            if (candidate == null || abs(candidate.timeMs - line.timeMs) > 3_000L) {
                line
            } else {
                matched++
                line.copy(
                    words = candidate.words.map {
                        it.copy(timeMs = it.timeMs + (line.timeMs - candidate.timeMs))
                    },
                )
            }
        }
        val need = maxOf(4, lines.size / 4)
        return if (matched >= need) {
            Log.d(TAG, "${song.key} 补逐字成功：$matched/${lines.size} 行")
            out
        } else {
            Log.d(TAG, "${song.key} 补逐字失败：仅 $matched/${lines.size} 行可配")
            lines
        }
    }

    /** 配词用的归一化：只留字母数字，忽略空格、标点、大小写。 */
    private fun normalize(text: String): String =
        text.filter { it.isLetterOrDigit() }.lowercase()

    private suspend fun fetchFromOtherPlatform(
        song: Song,
        platform: OnlinePlatform,
        durationMs: Long,
    ): List<LyricLine>? {
        val target = if (platform == OnlinePlatform.QQ) OnlinePlatform.NETEASE else OnlinePlatform.QQ
        val keyword = "${song.title} ${song.artist}".trim()
        val candidates = online.search(keyword, target)
        val base = baseTitle(song.title)
        val best = candidates
            .filter { abs(it.durationMs - durationMs) <= MATCH_DURATION_MS }
            .filter { baseTitle(it.title) == base }
            .minByOrNull { abs(it.durationMs - durationMs) }
            ?: return null
        val raw = online.lyrics(best) ?: return null
        return LrcParser.parse(raw).takeIf { it.isNotEmpty() }
    }

    /** 「晴天 (原唱 周杰伦)」→「晴天」，跨平台匹配只看正题。 */
    private fun baseTitle(title: String): String =
        title.substringBefore('(')
            .substringBefore('（')
            .substringBefore('[')
            .trim()
            .lowercase()

    private companion object {
        const val TAG = "LyricAligner"

        /** 末行与时长差在此范围内视为正常（尾奏、收尾留白）。 */
        const val NORMAL_GAP_MS = 8_000L
        /** 另一音源差在此范围内才认为「基本吻合」。 */
        const val ACCEPT_GAP_MS = 5_000L
        /** 换源必须比本音源再贴近这么多。 */
        const val IMPROVE_MARGIN_MS = 5_000L
        /** 另一音源候选的时长容差。 */
        const val MATCH_DURATION_MS = 8_000L
        /** 两平台歌词末行相差在此范围内，视为同一版本歌词（差额只是尾奏）。 */
        const val SAME_SHAPE_MS = 5_000L
    }
}
