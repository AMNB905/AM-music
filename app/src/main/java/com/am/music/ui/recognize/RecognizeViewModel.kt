package com.am.music.ui.recognize

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.am.music.AppContainer
import com.am.music.data.model.ClipHit
import com.am.music.data.model.ClipRecognize
import com.am.music.data.model.ClipStage
import com.am.music.data.model.Song
import com.am.music.data.model.VideoClip
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class RecognizePhase { IDLE, RECORDING, UPLOADING, DONE }

/** 安全上限：只防止无限录，不是「到点强制上传」。 */
private const val RECORD_MAX_SECONDS = 60

/** 上传最短时长，太短后端匹配不到。 */
private const val RECORD_MIN_SECONDS = 3

data class RecognizeUiState(
    val phase: RecognizePhase = RecognizePhase.IDLE,
    val elapsedSeconds: Int = 0,
    val song: Song? = null,
    val message: String? = null,
    val playing: Boolean = false,
    /** 粘贴框里的内容 */
    val link: String = "",
    /** 只扫这一段：`0:30-2:00`；空 = 整条。解析失败会直接提示，不会静默按整条扫 */
    val range: String = "",
    /** 非空 = 链接识别正在进行，界面据此显示具体阶段而不是干转圈 */
    val linkStage: ClipStage? = null,
    /** 分段识别的更细进度（"第 3/13 段 · 第 48 秒"），空则不显示 */
    val progress: String? = null,
    /** 长视频分段识别出来的全部命中，>1 条时界面出列表 */
    val hits: List<ClipHit> = emptyList(),
    /** 正在解析播放的是哪一首（列表里按 key 区分） */
    val playingKey: String? = null,
    /** 解析出来的视频信息（识别失败时也要显示"是哪条视频没识别出来"） */
    val clip: VideoClip? = null,
)

class RecognizeViewModel(private val container: AppContainer) : ViewModel() {

    private val repository = container.onlineMusicRepository

    private val _uiState = MutableStateFlow(RecognizeUiState())
    val uiState: StateFlow<RecognizeUiState> = _uiState.asStateFlow()

    private var job: Job? = null
    private var active: AudioRecord? = null

    /** 用户点「停止」时置位，录制循环据此收尾并上传。 */
    @Volatile
    private var stopRequested: Boolean = false

    fun start() {
        if (_uiState.value.phase == RecognizePhase.RECORDING) return
        job?.cancel()
        stopRequested = false
        job = viewModelScope.launch {
            _uiState.value = RecognizeUiState(phase = RecognizePhase.RECORDING, elapsedSeconds = 0)
            val pcm = withContext(Dispatchers.IO) { record() }
            if (pcm == null || pcm.isEmpty()) {
                _uiState.update {
                    it.copy(phase = RecognizePhase.IDLE, message = "没录到声音，看看麦克风权限")
                }
                return@launch
            }
            // 裸 PCM 16bit 单声道 8kHz：2 字节/采样，8000 采样/秒
            val seconds = pcm.size / 2 / SAMPLE_RATE
            if (seconds < RECORD_MIN_SECONDS) {
                _uiState.update {
                    it.copy(
                        phase = RecognizePhase.IDLE,
                        message = "太短：${seconds} 秒，至少 ${RECORD_MIN_SECONDS} 秒",
                    )
                }
                return@launch
            }
            _uiState.update { it.copy(phase = RecognizePhase.UPLOADING) }
            val song = withContext(Dispatchers.IO) { runCatching { repository.recognize(pcm) }.getOrNull() }
            if (song == null) {
                _uiState.update {
                    it.copy(phase = RecognizePhase.IDLE, message = "没听出来，靠近点再试")
                }
                return@launch
            }
            // 识别出来还要确认「放不放得了」：QQ 没版权时按同名同作者换网易云
            _uiState.update { it.copy(progress = "正在确认音源…") }
            val variant = withContext(Dispatchers.IO) { runCatching { repository.playableVariant(song) }.getOrNull() }
            _uiState.update {
                it.copy(
                    phase = RecognizePhase.DONE,
                    song = variant?.song ?: song,
                    progress = null,
                    message = variant?.message,
                    hits = listOf(ClipHit(variant?.song ?: song, 0)),
                )
            }
        }
    }

    /** 用户点停止：结束录制，随后自动进入识别。 */
    fun stopAndRecognize() {
        if (_uiState.value.phase != RecognizePhase.RECORDING) return
        stopRequested = true
    }

    fun cancel() {
        job?.cancel()
        job = null
        runCatching { active?.stop() }
        _uiState.value = RecognizeUiState(phase = RecognizePhase.IDLE, message = "已取消")
    }

    fun reset() {
        if (_uiState.value.phase == RecognizePhase.RECORDING) return
        if (_uiState.value.linkStage != null) return
        // 保留粘贴框内容：用户多半想换条链接再试
        _uiState.value = RecognizeUiState(
            link = _uiState.value.link,
            range = _uiState.value.range,
        )
    }

    // ------------------------------------------------------------ 链接识别

    fun setLink(value: String) {
        _uiState.update { it.copy(link = value) }
    }

    fun setRange(value: String) {
        _uiState.update { it.copy(range = value) }
    }

    /**
     * 解析「只听这一段」：`0:30-2:00`、`30-120`、`1:00-` 都行。
     * 空串返回 null（整条）；写错返回 null 由调用方给提示，不要静默当整条。
     */
    private fun parseRange(text: String): IntRange? {
        val body = text.trim()
        if (body.isEmpty()) return null
        val parts = body.split("-", "~", "～", "—").map { it.trim() }
        if (parts.size != 2) return null
        fun seconds(part: String): Int? {
            if (part.isEmpty()) return 0
            val bits = part.split(":")
            return when (bits.size) {
                1 -> bits[0].toIntOrNull()
                2 -> {
                    val m = bits[0].toIntOrNull() ?: return null
                    val s = bits[1].toIntOrNull() ?: return null
                    if (s > 59) null else m * 60 + s
                }
                else -> null
            }
        }
        val from = seconds(parts[0]) ?: return null
        val to = seconds(parts[1]) ?: return null
        if (from < 0) return null
        val end = if (to <= 0) 0 else to
        if (end in 1..from) return null
        return from..end
    }

    /**
     * 粘贴链接 → 解析 → 下载 → 解码 → 识别。
     *
     * 全程把当前阶段写进 `linkStage`：这四步里「解析」可能被抖音风控卡几秒、
     * 「下载」视网络可能十几秒，不给具体文字用户就会以为 App 死了。
     */
    fun recognizeLink() {
        val link = _uiState.value.link.trim()
        if (link.isEmpty()) {
            _uiState.update { it.copy(message = "先粘一条视频链接") }
            return
        }
        if (_uiState.value.linkStage != null) return
        val rawRange = _uiState.value.range.trim()
        val range = if (rawRange.isEmpty()) null else parseRange(rawRange)
        if (rawRange.isNotEmpty() && range == null) {
            _uiState.update {
                it.copy(message = "时间格式不对，照「0:30-2:00」写")
            }
            return
        }
        job?.cancel()
        job = viewModelScope.launch {
            _uiState.update {
                it.copy(linkStage = ClipStage.PARSING, message = null, song = null, clip = null, hits = emptyList(), progress = null)
            }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    repository.recognizeLink(
                        link,
                        onStage = { stage ->
                            // 换阶段就清掉上一阶段的细粒度提示，免得停在「正在下载…」不动
                            _uiState.update {
                                if (it.linkStage == stage) it else it.copy(linkStage = stage, progress = null)
                            }
                        },
                        onProgress = { detail ->
                            // 下载/解码阶段同样要刷提示：这两段最慢，旧口径只让上传阶段显示，
                            // 用户看到的就是长时间一句「正在下载音轨」不动
                            _uiState.update { state ->
                                if (state.linkStage == null) state else state.copy(progress = detail)
                            }
                        },
                        // 长视频会现场扫出多首：听出一首就先排进列表，别等全部扫完才一次刷
                        onHits = { live ->
                            if (live.size > 1) {
                                _uiState.update { it.copy(hits = live) }
                            }
                        },
                        rangeSec = range,
                    )
                }.getOrElse { error ->
                    ClipRecognize(null, null, "出错：" + (error.message ?: "未知错误"))
                }
            }
            // 单条结果先确认能不能播（列表结果按需在点播放时确认，免得一次发十几条请求）
            var shownSong = result.song
            var shownMessage = result.message
            if (result.song != null && result.hits.size <= 1) {
                _uiState.update { it.copy(progress = "正在确认音源…") }
                val variant = withContext(Dispatchers.IO) {
                    runCatching { repository.playableVariant(result.song) }.getOrNull()
                }
                if (variant != null) {
                    shownSong = variant.song ?: result.song
                    // 换源说明优先；没有换源时保留原结果里的说明（比如"上次已经识别过这条"）
                    shownMessage = variant.message ?: result.message
                }
            }
            _uiState.update {
                it.copy(
                    linkStage = null,
                    progress = null,
                    clip = result.clip,
                    song = shownSong,
                    message = shownMessage,
                    // 多首结果必须原样保留：之前这里把 >1 的命中压成一条，
                    // 扫描中列出来的合辑会在收尾时「缩水」成一首
                    hits = when {
                        result.hits.size > 1 -> result.hits
                        shownSong != null -> listOf(ClipHit(shownSong, 0))
                        else -> result.hits
                    },
                    phase = if (shownSong != null || result.hits.size > 1) {
                        RecognizePhase.DONE
                    } else {
                        RecognizePhase.IDLE
                    },
                )
            }
        }
    }

    /** 长视频列表里点某一首：先确认能不能播（必要时换网易云），再交给播放器。 */
    fun playHit(index: Int) {
        val hit = _uiState.value.hits.getOrNull(index) ?: return
        val song = hit.song
        if (_uiState.value.playingKey != null) return
        viewModelScope.launch {
            _uiState.update { it.copy(playingKey = song.key, message = null) }
            val variant = withContext(Dispatchers.IO) {
                runCatching { repository.playableVariant(song) }.getOrNull()
            }
            _uiState.update { it.copy(playingKey = null) }
            val playable = variant?.song
            if (playable == null) {
                _uiState.update {
                    it.copy(message = variant?.message ?: "这首暂时没播放地址")
                }
                return@launch
            }
            if (variant.switched) {
                _uiState.update { state ->
                    state.copy(
                        message = variant.message,
                        hits = state.hits.map { h -> if (h.song.key == song.key) h.copy(song = playable) else h },
                    )
                }
            }
            container.playbackManager.playContext(listOf(playable), 0)
        }
    }

    fun playRecognized() {
        val song = _uiState.value.song ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(playing = true, message = null) }
            // 播放前再确认一次音源：识别那一刻能放、几分钟后直链过期也可能放不了
            val variant = withContext(Dispatchers.IO) {
                runCatching { repository.playableVariant(song) }.getOrNull()
            }
            _uiState.update { it.copy(playing = false) }
            val playable = variant?.song
            if (playable == null) {
                _uiState.update {
                    it.copy(message = variant?.message ?: "这首暂时没播放地址")
                }
                return@launch
            }
            _uiState.update { state ->
                state.copy(
                    song = playable,
                    message = variant.message,
                    hits = if (state.hits.isEmpty()) {
                        listOf(ClipHit(playable, 0))
                    } else {
                        state.hits.map { h -> if (h.song.key == song.key) h.copy(song = playable) else h }
                    },
                )
            }
            container.playbackManager.playContext(listOf(playable), 0)
        }
    }

    override fun onCleared() {
        job?.cancel()
        runCatching { active?.stop() }
        super.onCleared()
    }

    /**
     * 录 8kHz / 16bit / 单声道 PCM：后端 `qq/api/recognize?pcm=1` 直接吃裸 PCM，
     * 省掉端上编码成 mp3 的步骤。
     *
     * 录多久由用户决定 —— 点「停止」就收尾上传；`RECORD_MAX_SECONDS` 只是安全上限，
     * 防止忘记停把缓冲堆起来，不是「到点强制上传」。
     */
    private suspend fun record(): ByteArray? {
        val sampleRate = SAMPLE_RATE
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) return null
        val bufferSize = maxOf(minBuf * 2, 4096)
        val recorder = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
            )
        }.getOrNull() ?: return null
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { recorder.release() }
            return null
        }

        active = recorder
        val total = ByteArrayOutputStream(bufferSize * 4)
        val buffer = ByteArray(bufferSize)
        val start = SystemClock.elapsedRealtime()
        try {
            recorder.startRecording()
            while (currentCoroutineContext().isActive && !stopRequested) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read > 0) total.write(buffer, 0, read)
                val elapsed = ((SystemClock.elapsedRealtime() - start) / 1000L).toInt()
                if (_uiState.value.elapsedSeconds != elapsed) {
                    _uiState.update { it.copy(elapsedSeconds = elapsed) }
                }
                if (elapsed >= RECORD_MAX_SECONDS) break
            }
        } catch (e: Exception) {
            return null
        } finally {
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
            active = null
        }
        return total.toByteArray()
    }

    companion object {
        const val MAX_SECONDS = RECORD_MAX_SECONDS
        const val MIN_SECONDS = RECORD_MIN_SECONDS
        private const val SAMPLE_RATE = 8000
    }
}
