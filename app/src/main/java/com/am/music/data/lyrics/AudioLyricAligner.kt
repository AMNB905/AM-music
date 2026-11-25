package com.am.music.data.lyrics

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import com.am.music.data.model.LyricLine
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 端上「人声-歌词」自动对齐。
 *
 * 歌词与音频对不上（人声已到歌词没切 / 歌词先于人声出现）本质是**整条时间轴的常数偏移**，
 * 只能靠音频本身来定。做法：用 [MediaExtractor]+[MediaCodec] 把音频前 ~150 秒解码出来，
 * 按 20ms 帧算能量包络，再在 [-10, +10] 秒内扫一个整体平移 δ，
 * 使「每条歌词时间点 ±120ms 内的能量跃变均值」最大。
 *
 * 宁可返回 null 也不猜：要求候选偏移足够大（≥2s）、且显著优于 δ=0 与次优解，
 * 否则保持原歌词。
 */
object AudioLyricAligner {

    private const val TAG = "AudioLyricAligner"
    private const val WINDOW_MS = 150_000L
    private const val FRAME_MS = 20
    private const val SWEEP_MIN_S = -10f
    private const val SWEEP_MAX_S = 10f
    private const val SWEEP_STEP_S = 0.2f
    private const val LINE_WINDOW_S = 0.12f

    fun align(streamUrl: String, lines: List<LyricLine>): Long? {
        if (streamUrl.isBlank() || lines.size < 15) return null
        val env = runCatching { decodeEnvelope(streamUrl) }.getOrElse {
            Log.w(TAG, "decode failed: ${it.message}")
            return null
        } ?: return null
        return chooseDelta(env, lines)
    }

    private data class Env(val frameMs: Float, val flux: FloatArray)

    private fun decodeEnvelope(url: String): Env? {
        val extractor = MediaExtractor()
        runCatching { extractor.setDataSource(url, emptyMap()) }.getOrElse {
            Log.w(TAG, "setDataSource: ${it.message}"); return null
        }
        var trackIndex = -1
        var mime: String? = null
        for (i in 0 until extractor.trackCount) {
            val fmt = extractor.getTrackFormat(i)
            val m = fmt.getString(MediaFormat.KEY_MIME) ?: continue
            if (m.startsWith("audio/")) { trackIndex = i; mime = m; break }
        }
        if (trackIndex < 0 || mime == null) { extractor.release(); return null }
        val trackFormat = extractor.getTrackFormat(trackIndex)
        val sampleRate = trackFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = trackFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        extractor.selectTrack(trackIndex)

        val codec = runCatching { MediaCodec.createDecoderByType(mime) }.getOrElse {
            extractor.release(); return null
        }
        codec.configure(trackFormat, null, null, 0)
        codec.start()

        // 累积 16-bit 小端 PCM（按帧求 RMS，边解码边算，不整段驻留内存）
        val frameSamples = sampleRate * FRAME_MS / 1000
        val frameBuf = ByteArrayOutputStream()
        val energies = ArrayList<Float>()
        var eosInput = false
        var eosOutput = false
        val info = MediaCodec.BufferInfo()

        try {
            while (!eosOutput) {
                if (!eosInput) {
                    val inIndex = codec.dequeueInputBuffer(5_000)
                    if (inIndex >= 0) {
                        val buf = codec.inputBufferAt(inIndex) ?: continue
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            eosInput = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, 5_000)
                when {
                    outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    outIndex >= 0 -> {
                        val outBuf = codec.outputBufferAt(outIndex)
                        if (outBuf != null && info.size > 0) {
                            val chunk = ByteArray(info.size)
                            outBuf.get(chunk)
                            frameBuf.write(chunk)
                            // 凑满一帧 20ms 就算一个 RMS
                            val need = frameSamples * 2 * channels
                            while (frameBuf.size() >= need) {
                                val b = frameBuf.toByteArray()
                                var sum = 0.0
                                var n = 0
                                var j = 0
                                while (j + 2 <= need) {
                                    val lo = b[j].toInt() and 0xFF
                                    val hi = b[j + 1].toInt()
                                    var s = (hi shl 8) or lo
                                    if (s >= 32768) s -= 65536
                                    // 双声道相邻两采样求平均
                                    if (channels == 2 && j + 4 <= need) {
                                        val lo2 = b[j + 2].toInt() and 0xFF
                                        val hi2 = b[j + 3].toInt()
                                        var s2 = (hi2 shl 8) or lo2
                                        if (s2 >= 32768) s2 -= 65536
                                        s = (s + s2) / 2
                                        j += 4
                                    } else j += 2
                                    sum += (s * s).toDouble()
                                    n++
                                }
                                energies.add(sqrt(sum / n).toFloat())
                                // 移除已消费的 need 字节
                                frameBuf.reset()
                                frameBuf.write(b, need, b.size - need)
                            }
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) eosOutput = true
                        if (energies.size * FRAME_MS >= WINDOW_MS.toInt()) eosOutput = true
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "decode loop: ${t.message}")
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            extractor.release()
        }
        if (energies.size < 50) return null
        // 对数能量 → 正向差分（onset 包络）
        val logE = FloatArray(energies.size) { kotlin.math.log10(energies[it] + 1f) }
        val flux = FloatArray(logE.size)
        for (i in logE.indices) {
            val d = logE[i] - (if (i > 0) logE[i - 1] else logE[i])
            flux[i] = if (d > 0f) d else 0f
        }
        // 轻平滑
        val smooth = FloatArray(flux.size)
        for (i in flux.indices) {
            val a = flux[maxOf(0, i - 1)]
            val b = flux[i]
            val c = flux[minOf(flux.size - 1, i + 1)]
            smooth[i] = (a + b + c) / 3f
        }
        val mean = smooth.average().toFloat()
        val std = sqrt(smooth.map { (it - mean) * (it - mean) }.average()).toFloat()
        if (std <= 0f) return null
        val norm = FloatArray(smooth.size) { (smooth[it] - mean) / std }
        return Env(FRAME_MS.toFloat(), norm)
    }

    private fun chooseDelta(env: Env, lines: List<LyricLine>): Long? {
        val hopS = env.frameMs / 1000f
        val audioS = env.flux.size * hopS
        val lineTimes = lines.map { it.timeMs / 1000f }.filter { it in 5f..(audioS - 1f) }
        if (lineTimes.size < 12) return null

        fun score(offsetS: Float): Pair<Float, Int> {
            var sum = 0.0
            var cnt = 0
            val half = (LINE_WINDOW_S / hopS).toInt().coerceAtLeast(1)
            for (t in lineTimes) {
                val tt = t + offsetS
                if (tt < 0.5f || tt > audioS - 0.5f) continue
                val i = (tt / hopS).toInt()
                var m = Float.NEGATIVE_INFINITY
                for (k in (i - half)..(i + half)) {
                    if (k in env.flux.indices) m = maxOf(m, env.flux[k])
                }
                if (m.isFinite()) { sum += m; cnt++ }
            }
            return (sum / cnt.toDouble()).toFloat() to cnt
        }

        val scores = ArrayList<Triple<Float, Float, Int>>()
        var d = SWEEP_MIN_S
        while (d <= SWEEP_MAX_S + 1e-6) {
            val (s, c) = score(d)
            if (c >= 12) scores.add(Triple(d, s, c))
            d += SWEEP_STEP_S
        }
        if (scores.size < 5) return null
        scores.sortByDescending { it.second }

        val best = scores[0]
        val zero = scores.firstOrNull { abs(it.first) < 0.01f }?.second ?: best.second
        val runnerUp = scores[1].second

        // 证据门槛：偏移量要够大、明显优于 δ=0、明显优于次优解
        val marginToZero = if (zero > 0f) best.second / zero else 99f
        val marginToRunner = if (runnerUp > 0f) best.second / runnerUp else 99f
        val accepted = abs(best.first) >= 2f && marginToZero >= 1.5f && marginToRunner >= 1.25f

        Log.d(
            TAG,
            "best d=${"%.2f".format(best.first)}s score=${"%.2f".format(best.second)} " +
                "score0=${"%.2f".format(zero)} runner=${"%.2f".format(runnerUp)} " +
                "lines=${best.third} accept=$accepted"
        )
        return if (accepted) (best.first * 1000).toLong() else null
    }
}

private fun MediaCodec.inputBufferAt(index: Int): ByteBuffer? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) getInputBuffer(index)
    else inputBuffers.getOrNull(index)

private fun MediaCodec.outputBufferAt(index: Int): ByteBuffer? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) getOutputBuffer(index)
    else outputBuffers.getOrNull(index)
