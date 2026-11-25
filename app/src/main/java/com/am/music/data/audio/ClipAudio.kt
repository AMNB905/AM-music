package com.am.music.data.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import com.am.music.data.model.VideoClip
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer
import kotlin.math.floor
import kotlin.math.min

/**
 * 把视频里的音频变成「识曲要的那一口」：8kHz / 16bit / 单声道 PCM。
 *
 * 为什么在端上做而不是服务端：服务器没装 ffmpeg（实测 `command -v ffmpeg` 为空），
 * 而抖音/快手给的是混流 MP4、B 站给的是 DASH 音轨，PHP 侧解不开 AAC/MP3；
 * 端上 MediaExtractor + MediaCodec 免费就能解，省掉往服务器装一个 70MB 的二进制的麻烦。
 *
 * 两条纪律：
 * 1. **先按 Range 只取前 4MB**：25 秒音频用不了整条，B 站整轨可能几十 MB；
 * 2. **取不全就退回整条**：MP4 的 moov 有可能在文件尾部，截断后解不开，
 *    这时再按完整文件拉一次（上限 12MB），宁可多等几秒也别直接判死。
 */
object ClipAudio {

    private const val TARGET_RATE = 8000
    private const val MAX_SECONDS = 25
    private const val RANGE_BYTES = 4L * 1024 * 1024
    private const val FULL_LIMIT_BYTES = 12L * 1024 * 1024

    /**
     * 从第 [startSec] 秒起解 [seconds] 秒音频。
     *
     * 分段识别（一条长视频里可能有好几首歌）就靠它：每次只解一个 12 秒窗口，
     * 而不是把整条 4 分半的音频一次全解进内存——44.1kHz 立体声 270 秒的原始
     * ShortArray 是 2300 万个 short（≈47MB），低端机直接 OOM。
     *
     * [MediaExtractor.seekTo] 用 `SEEK_TO_CLOSEST_SYNC`：AAC 的关键帧间隔通常半秒内，
     * 从这里起解最多多出十几毫秒的头部，识曲窗口里完全够用。
     */

    /** 少于 3 秒的音频上游基本不可能识别出来（3 秒是它给的最低门槛）。 */
    const val MIN_PCM_BYTES = TARGET_RATE * 2 * 3

    /** 浏览器 UA：抖音/快手 CDN 对非浏览器 UA 偶发 403 */
    private const val UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Mobile Safari/537.36"

    /**
     * 下载结果。失败时带一句**人话原因**：图文的空壳地址、403 风控、只拿到 0 字节，
     * 在后面是三种完全不同的处置方式，不能都显示成「可能加密」。
     */
    data class Fetch(val file: File?, val failure: String?)

    /**
     * 下载音轨到 [dir]，返回本地文件；失败返回 null。
     *
     * [partial] = true 时只取前 4MB（只够识别开头 25 秒）；
     * false 时整条，上限 [maxBytes]（默认 12MB）。
     *
     * 分段识别会传更大的 [maxBytes]：4 分半的混流 MP4 常有 30~50MB，
     * 只取 12MB 的话后半段根本没有数据可解，只能识别到前一两首。
     */
    fun download(
        dir: File,
        clip: VideoClip,
        partial: Boolean,
        maxBytes: Long = if (partial) RANGE_BYTES else FULL_LIMIT_BYTES,
    ): File? = fetch(dir, clip, partial, maxBytes).file

    /** 带原因版下载：调用方要把「为什么没成」带到界面上。 */
    fun fetch(
        dir: File,
        clip: VideoClip,
        partial: Boolean,
        maxBytes: Long = if (partial) RANGE_BYTES else FULL_LIMIT_BYTES,
    ): Fetch {
        val url = clip.audioUrl
        if (!url.startsWith("http")) return Fetch(null, "地址不是 http")
        if (!dir.exists() && !dir.mkdirs()) return Fetch(null, "缓存目录建不出来")
        val out = File(dir, "clip-" + System.currentTimeMillis() + if (partial) ".part" else ".full")
        val limit = maxBytes
        val connection = runCatching { URL(url).openConnection() as HttpURLConnection }.getOrNull()
            ?: return Fetch(null, "连不上这个地址")
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", UA)
            connection.setRequestProperty("Accept", "*/*")
            if (clip.referer.isNotBlank()) {
                connection.setRequestProperty("Referer", clip.referer)
            }
            // Range 一律带：B 站 CDN 对不带 Range 的大文件请求会直接 567 拒绝，
            // 带 Range 才 206。上限必须跟着 maxBytes 走——原来恒写 12MB，
            // 传进来的 40MB 口径其实一字节都没多拿，长视频后半段自然识别不到。
            connection.setRequestProperty(
                "Range",
                "bytes=0-" + (limit - 1).coerceAtLeast(0),
            )
            if (connection.responseCode !in 200..299) {
                out.delete()
                return Fetch(null, "HTTP ${connection.responseCode}")
            }
            var total = 0L
            connection.inputStream.use { input ->
                out.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (total < limit) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        val take = min(read.toLong(), limit - total).toInt()
                        output.write(buffer, 0, take)
                        total += take
                        if (take < read) break
                    }
                }
            }
            if (out.length() < 8 * 1024) {
                val got = out.length()
                out.delete()
                return Fetch(null, "只拿到 $got 字节")
            }
            return Fetch(out, null)
        } catch (e: Exception) {
            out.delete()
            return Fetch(null, e.javaClass.simpleName + (e.message?.let { "：" + it } ?: ""))
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    /** 下载到本地后读真实音频时长（毫秒）。读不到返回 null。 */
    fun durationMs(file: File): Long? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.takeIf { it > 0 }
        } catch (e: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** 解码 + 降混 + 降到 8kHz，返回裸 PCM（16bit 小端）。解不开返回 null。 */
    fun decodeToPcm(
        file: File,
        startSec: Int = 0,
        seconds: Int = MAX_SECONDS,
    ): ByteArray? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull { index ->
                val mime = runCatching {
                    extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                }.getOrNull().orEmpty()
                mime.startsWith("audio/")
            } ?: return null
            extractor.selectTrack(track)
            if (startSec > 0) {
                runCatching {
                    extractor.seekTo(startSec * 1_000_000L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                }
            }
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null

            var sampleRate = runCatching { format.getInteger(MediaFormat.KEY_SAMPLE_RATE) }.getOrDefault(0)
            var channels = runCatching { format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }.getOrDefault(0)
            if (sampleRate <= 0) sampleRate = 44_100
            if (channels <= 0) channels = 2

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val capacity = (seconds * sampleRate + sampleRate * 2) * channels
            val raw = ShortArray(capacity)
            var rawSize = 0
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var idleLoops = 0

            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buffer = codec.getInputBuffer(inIndex)
                        val size = if (buffer == null) -1 else extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(
                                inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                when (val outIndex = codec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFormat = codec.outputFormat
                        sampleRate = runCatching {
                            outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        }.getOrDefault(sampleRate)
                        channels = runCatching {
                            outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }.getOrDefault(channels)
                    }

                    MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        // 编解码器偶尔空转：连续太多次就当这条解不出来，别把界面卡死
                        idleLoops++
                        if (idleLoops > 400) return null
                    }

                    else -> {
                        if (outIndex >= 0) {
                            idleLoops = 0
                            val buffer = codec.getOutputBuffer(outIndex)
                            if (buffer != null && info.size > 0) {
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                val shorts = buffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                                val count = shorts.remaining()
                                if (rawSize + count <= raw.size) {
                                    shorts.get(raw, rawSize, count)
                                    rawSize += count
                                }
                            }
                            codec.releaseOutputBuffer(outIndex, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                outputDone = true
                            }
                            // 窗口已经攒满：不必把整条音频解到底，直接收（分段识别时这是常态）
                            if (rawSize >= seconds * sampleRate * channels) {
                                outputDone = true
                            }
                        }
                    }
                }
            }
            if (rawSize < sampleRate * channels / 2) return null // 不足半秒，说明没解出东西
            return resampleTo8kMono(raw, rawSize, channels, sampleRate, seconds)
        } catch (e: Throwable) {
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    /**
     * 降混 + 降到 8kHz。
     *
     * 直接每 N 个取一个会产生严重混叠（高频折回低频，识曲特征会被搅乱），所以用一个
     * 宽度≈抽取比的箱型平均当作低通：它在输出采样率处刚好落零点，4kHz 以上被压下去，
     * 比"隔点取样"稳得多，又不用引入重采样库。
     */
    private fun resampleTo8kMono(
        raw: ShortArray,
        size: Int,
        channels: Int,
        sampleRate: Int,
        limitSeconds: Int,
    ): ByteArray {
        if (channels <= 0 || size <= 0) return ByteArray(0)
        val frames = size / channels
        if (frames <= 0) return ByteArray(0)

        val mono = FloatArray(frames)
        for (i in 0 until frames) {
            var sum = 0f
            val base = i * channels
            for (c in 0 until channels) {
                sum += raw[base + c].toFloat()
            }
            mono[i] = sum / channels
        }

        if (sampleRate == TARGET_RATE) {
            return framesToBytes(mono, min(frames, TARGET_RATE * limitSeconds))
        }

        val ratio = sampleRate.toDouble() / TARGET_RATE.toDouble()
        val outFrames = min((frames / ratio).toInt(), TARGET_RATE * limitSeconds)
        if (outFrames <= 0) return ByteArray(0)

        val outMono = FloatArray(outFrames)
        val half = ratio / 2.0
        for (o in 0 until outFrames) {
            val center = o * ratio
            val start = floor(center - half).toInt().coerceAtLeast(0)
            val end = (center + half).toInt().coerceAtMost(frames - 1)
            var acc = 0.0
            var weight = 0.0
            var i = start
            while (i <= end) {
                // 边缘按实际覆盖宽度加权，避免首尾被削
                val left = maxOf(i.toDouble(), center - half)
                val right = minOf(i + 1.0, center + half)
                val w = (right - left).coerceAtLeast(0.0)
                if (w > 0.0) {
                    acc += mono[i] * w
                    weight += w
                }
                i++
            }
            outMono[o] = if (weight > 0.0) (acc / weight).toFloat() else 0f
        }
        return framesToBytes(outMono, outFrames)
    }

    private fun framesToBytes(samples: FloatArray, count: Int): ByteArray {
        val bytes = ByteArray(count * 2)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) {
            val v = samples[i]
            val clamped = when {
                v > 32767f -> 32767
                v < -32768f -> -32768
                else -> v.toInt()
            }
            buffer.putShort(clamped.toShort())
        }
        return bytes
    }
}
