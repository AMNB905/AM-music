package com.am.music.data.audio

import com.am.music.data.model.ClipHit
import com.am.music.data.model.Song
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * 通用「一条音轨里有多首歌」扫描器。
 *
 * 不绑定任何平台：调用方只需要提供两件事——
 * 1. 总时长（未知可以传 0）；
 * 2. `decode(startSec, seconds)`：按时间窗解出一段 8kHz/16bit/单声道 PCM（没有数据返回 null）。
 *
 * 这套流程对「抖音/快手/B站的链接」和「用户本地选的一个视频文件」完全一样，
 * 所以放在 `data/audio` 而不是某条业务链路里。
 *
 * ## 为什么不是「每 20 秒盲切一刀」
 *
 * 盲切的代价是跟时长线性增长的识别请求：4 分半要 13 次，10 分钟要 30 次。
 * 而合辑视频里真正的「歌」数量远少于这个数，中间还有大段口播/停顿/前奏留白。
 *
 * 所以先做一遍**能量地图**（只解码、不上传，本地开销几乎为零）：
 * - 每 [windowSeconds] 秒算一次平均幅度（RMS）；
 * - 低于阈值（≈0.7% 满量程）的窗口判为「没内容」，直接不进候选；
 * - 剩下的「有声窗口」全都要：**有声窗数量 ≤ [maxCalls] 就一个不漏**（重复命中的靠 key 去重），
 *   超出预算才按均匀间隔抽到 [maxCalls] 个。
 *
 * 为什么不是「每隔 22 秒取一个」：实测一条 64 秒的纯音乐合辑，曲目大约每 12~16 秒换一首，
 * 22 秒间距直接把 36 秒处那首跳过去了（3 首 vs 实际 4 首）。合辑工况下「少发请求」的收益
 * 远不如「别漏歌」重要，所以改成预算优先：预算内全覆盖，超预算才降密度。
 *
 * ## 两个刻意的取舍
 *
 * 1. **不做音频指纹级别的分段**：端上跑 chroma/MFCC 聚类要几十 MB 的 DSP 代码，
 *    换来的只是「边界更准」，而边界对用户没那么重要（他要知道的是"这首歌叫什么"）；
 * 2. **不去猜「没听出来」的窗口是什么**：识别不到就是没有，界面只列命中项，
 *    不编造占位条目。
 *
 * ## 并发
 *
 * 实测上游单次识别要 30~60 秒，10 个窗口串行要 5~7 分钟。这里按 [concurrency] 路并发，
 * 2 路就能把墙钟时间砍半，同时在 `rate_per_device 50` / `rate_per_ip 120` 的口径之内。
 * 结果是按窗口起点顺序回吐的（先等这一批全部返回，再依次 emit），所以界面上的时间点不会乱序。
 */
class MultiSongScanner(
    private val windowSeconds: Int = 12,
    /** 一次扫描最多上传多少次：有声窗超预算时按均匀间隔抽到这个数。 */
    private val maxCalls: Int = 10,
    /** 判定「有声」的平均绝对幅度阈值（16bit 满量程 32768，约 0.7%）。 */
    private val minRms: Int = 220,
    /** 同时上传几段。1 = 串行；2 是实测下来既快又不会撞限流的档位。 */
    private val concurrency: Int = 2,
) {

    /**
     * 扫描并返回命中；[onHit] 每听出一首回调一次（界面可以边扫边显示）。
     *
     * [fromSec]/[toSec] 给「只听这一段」用：`toSec = 0` 表示到结尾。
     * 区间识别前先按区间裁掉能量地图的范围，请求数直接等于区间内的有声窗数。
     *
     * 这个方法自身不持有任何 Android 对象，`decode` 与 `recognize` 都由调用方注入，
     * 便于单测和换平台。
     */
    suspend fun scan(
        durationSec: Int,
        decode: (startSec: Int, seconds: Int) -> ByteArray?,
        recognize: suspend (ByteArray) -> Song?,
        fromSec: Int = 0,
        toSec: Int = 0,
        onProgress: (String) -> Unit = {},
        onHit: (ClipHit) -> Unit = {},
    ): List<ClipHit> {
        val samples = samplePoints(durationSec, fromSec, toSec, decode, onProgress)
        if (samples.isEmpty()) return emptyList()

        val hits = mutableListOf<ClipHit>()
        var index = 0
        while (index < samples.size) {
            val edge = minOf(index + concurrency.coerceAtLeast(1), samples.size)
            val batch = samples.subList(index, edge)
            onProgress(
                if (batch.size > 1) {
                    "第 ${index + 1}-$edge/${samples.size} 段 · 第 ${batch.first()} 秒起"
                } else {
                    "第 $edge/${samples.size} 段 · 第 ${batch.first()} 秒"
                }
            )

            // 并发只影响等待时间，不影响顺序：先整批返回，再按窗口起点依次入库
            val results = coroutineScope {
                batch.map { start ->
                    async {
                        runCatching {
                            val pcm = decode(start, windowSeconds) ?: return@runCatching null
                            // 8kHz/16bit 单声道：每秒 16000 字节，不足半窗的没有识别价值
                            if (pcm.size < windowSeconds * 8000) return@runCatching null
                            recognize(pcm)
                        }.getOrNull()
                    }
                }.awaitAll()
            }

            batch.forEachIndexed { offset, start ->
                val song = results[offset] ?: return@forEachIndexed
                // 同一首在相邻采样点会重复命中：按 key 去重，保留最早的位置
                if (hits.any { it.song.key == song.key }) return@forEachIndexed
                val hit = ClipHit(song, start)
                hits += hit
                onHit(hit)
            }
            index = edge
        }
        return hits
    }

    /**
     * 第一遍：能量地图 + 采样点。
     *
     * 时长未知（0）时退化成「一直解到没有数据为止」，上限仍是 [maxCalls] 个窗口，
     * 这样至少不会静默返回空。
     */
    private suspend fun samplePoints(
        durationSec: Int,
        fromSec: Int,
        toSec: Int,
        decode: (startSec: Int, seconds: Int) -> ByteArray?,
        onProgress: (String) -> Unit,
    ): List<Int> {
        val loud = mutableListOf<Int>()
        var at = fromSec.coerceAtLeast(0)
        var scanned = 0
        onProgress("正在扫描有声段落…")
        while (durationSec <= 0 || at + windowSeconds <= durationSec) {
            if (toSec > 0 && at + windowSeconds > toSec) break
            if (durationSec <= 0 && scanned >= maxCalls) break
            val pcm = decode(at, windowSeconds) ?: break
            if (pcm.size < windowSeconds * 500) break
            if (rms(pcm) >= minRms) loud += at
            at += windowSeconds
            scanned++
            if (durationSec > 0 && scanned > MAX_MAP_WINDOWS) break
        }
        if (loud.isEmpty()) {
            // 整条都很安静（纯环境声/静音），只试开头一段，别直接判死
            return if (decode(fromSec.coerceAtLeast(0), windowSeconds) != null) {
                listOf(fromSec.coerceAtLeast(0))
            } else {
                emptyList()
            }
        }
        // 预算优先：有声窗能全扫就全扫（重复的靠 key 去重），超预算才降到均匀间隔
        return if (loud.size <= maxCalls) loud else evenly(loud, maxCalls)
    }

    /** 候选太多时均匀抽 [count] 个，保证覆盖整段的首尾。 */
    private fun evenly(windows: List<Int>, count: Int): List<Int> {
        if (windows.size <= count) return windows
        val picked = mutableListOf<Int>()
        for (i in 0 until count) {
            val index = (i.toLong() * (windows.size - 1) / (count - 1).coerceAtLeast(1)).toInt()
            val start = windows[index.coerceIn(0, windows.lastIndex)]
            if (picked.isEmpty() || picked.last() != start) picked += start
        }
        return picked
    }

    /** 平均绝对幅度，0..32768。 */
    private fun rms(pcm: ByteArray): Int {
        val samples = pcm.size / 2
        if (samples <= 0) return 0
        var sum = 0L
        var i = 0
        while (i < samples) {
            val value = ((pcm[2 * i].toInt() and 0xFF) or (pcm[2 * i + 1].toInt() shl 8)).toShort().toInt()
            sum += kotlin.math.abs(value).toLong()
            i++
        }
        return (sum / samples).toInt()
    }

    private companion object {
        /** 能量地图最多扫多少窗（4 分半 23 窗，10 分钟 51 窗；封顶 60 窗≈12 分钟）。 */
        const val MAX_MAP_WINDOWS = 60
    }
}
