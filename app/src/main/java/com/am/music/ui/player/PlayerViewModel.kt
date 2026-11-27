package com.am.music.ui.player

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.am.music.AppContainer
import com.am.music.data.lyrics.AudioLyricAligner
import com.am.music.data.lyrics.LrcParser
import com.am.music.data.lyrics.LyricOffsetTable
import com.am.music.data.model.LyricLine
import com.am.music.data.model.Song
import com.am.music.playback.PlaybackState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface LyricsUiState {
    data object Loading : LyricsUiState
    data object NotFound : LyricsUiState
    data class Ready(val lines: List<LyricLine>) : LyricsUiState
}

class PlayerViewModel(private val container: AppContainer) : ViewModel() {

    private companion object {
        /** 是否允许从另一个音源「搬」逐字时间过来。默认关闭：逐字只认当前歌词源自带的数据。 */
        const val CROSS_SOURCE_WORDS = false
    }

    val playbackState: StateFlow<PlaybackState> = container.playbackManager.state

    private val _lyricsState = MutableStateFlow<LyricsUiState>(LyricsUiState.NotFound)
    val lyricsState: StateFlow<LyricsUiState> = _lyricsState.asStateFlow()

    val isFavorite: StateFlow<Boolean> = combine(
        container.playbackManager.state,
        container.favoritesRepository.favoriteIds,
    ) { state, ids -> state.currentSong?.key in ids }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    init {
        viewModelScope.launch {
            container.playbackManager.state
                .map { it.currentSong }
                .distinctUntilChanged()
                .collectLatest { song ->
                    if (song == null) {
                        _lyricsState.value = LyricsUiState.NotFound
                    } else {
                        loadLyrics(song)
                    }
                }
        }
        // 音频真实时长要等播放器就绪才有；拿到之后补做一次对齐校验（搜索接口的时长有偏差）
        viewModelScope.launch {
            container.playbackManager.state.collect { state ->
                val song = state.currentSong ?: return@collect
                val duration = state.durationMs
                if (duration <= 0L || validatedKey == song.key) return@collect
                if (!song.isOnline || baseLines.isEmpty() || offsetMs != 0L) {
                    validatedKey = song.key
                    return@collect
                }
                validatedKey = song.key
                val aligned = runCatching {
                    container.lyricAligner.align(song, baseLines, duration, trustedDuration = true)
                }.getOrNull()
                if (aligned != null) {
                    container.lyricsRepository.writeCache(song.key, aligned)
                    container.lyricsRepository.markAligned(song.key)
                }
                if (aligned != null && aligned != baseLines &&
                    playbackState.value.currentSong?.key == song.key
                ) {
                    baseLines = aligned
                    publishLines()
                }
            }
        }
        // 播到 25 秒后做一次「人声-歌词」能量对齐：解码音频前 150 秒，自动求整体偏移
        viewModelScope.launch {
            container.playbackManager.state.collect { state ->
                val song = state.currentSong ?: return@collect
                if (!song.isOnline || state.positionMs < 25_000L) return@collect
                if (autoAlignedKey == song.key) return@collect
                autoAlignedKey = song.key
                if (offsetMs != 0L || baseLines.size < 15) return@collect
                applyAutoOffset(song)
            }
        }
    }

    private suspend fun applyAutoOffset(song: Song) {
        val url = runCatching { container.onlineMusicRepository.streamUrl(song) }.getOrNull() ?: return
        val delta = withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { AudioLyricAligner.align(url, baseLines) }.getOrNull()
        } ?: return
        if (playbackState.value.currentSong?.key != song.key) return
        offsetMs += delta
        publishLines()
        container.lyricsRepository.setCalibration(song.key, offsetMs)
    }

    fun importLyrics(uri: Uri) {
        val song = playbackState.value.currentSong ?: return
        container.lyricsRepository.saveImported(song.id, uri)
        viewModelScope.launch { loadLyrics(song) }
    }

    fun togglePlayPause() = container.playbackManager.togglePlayPause()
    fun next() = container.playbackManager.next()
    fun previous() = container.playbackManager.previous()
    fun seekTo(positionMs: Long) = container.playbackManager.seekTo(positionMs)
    fun cyclePlayMode() = container.playbackManager.cyclePlayMode()

    fun cycleSpeed() = container.playbackManager.cycleSpeed()

    fun toggleFavorite() {
        val song = playbackState.value.currentSong ?: return
        viewModelScope.launch { container.favoritesRepository.toggle(song) }
    }

    private var baseLines: List<LyricLine> = emptyList()
    private var offsetMs = 0L
    private var validatedKey: String? = null
    private var autoAlignedKey: String? = null

    /**
     * 歌词来源优先级：用户导入的 LRC → 本地同名 .lrc → **磁盘缓存** → 在线接口
     * （QQ：qq/api/lyric?mid=；网易：wy/api/lyric?id=）。
     *
     * 缓存命中就不再碰网络，所以第二次进歌词页是「立刻有词」；在线拿到的那一份也会
     * 立刻写盘，下次直接用。对齐（版本校验 / 人声能量对齐）只对没对齐过的缓存跑一次，
     * 结果同样写回缓存并打标记，避免每次进页面都重算。
     */
    private suspend fun loadLyrics(song: Song) {
        _lyricsState.value = LyricsUiState.Loading
        baseLines = emptyList()
        // 用户手动校准优先；其次用离线校准表里的逐曲偏移
        offsetMs = container.lyricsRepository.calibration(song.key)
            .takeIf { it != 0L }
            ?: LyricOffsetTable.offsetFor(song.key)
            ?: 0L
        validatedKey = null
        autoAlignedKey = null

        val local = container.lyricsRepository.load(song)
        if (local != null) {
            baseLines = local
            publishLines()
            return
        }

        val cached = container.lyricsRepository.readCache(song.key)
        val lines = cached ?: onlineLines(song)
        if (lines == null) {
            _lyricsState.value = LyricsUiState.NotFound
            return
        }

        baseLines = lines
        publishLines()
        if (cached == null || lines !== cached) {
            // 在线刚拿到：先落盘，后续进页面零网络
            container.lyricsRepository.writeCache(song.key, lines)
        }
        // 整句歌词已经显示出来了，再在后台试着把逐字时间补上（不挡第一眼）
        enrichWordsAsync(song, lines)

        if (!song.isOnline || offsetMs != 0L) {
            validatedKey = song.key
            return
        }
        // 缓存已标记为「对齐过」，不必再跑算法
        if (cached != null && container.lyricsRepository.isAligned(song.key)) {
            validatedKey = song.key
            return
        }

        val playDuration = playbackState.value.durationMs
        val duration = playDuration.takeIf { it > 0L } ?: song.durationMs
        val aligned = runCatching {
            container.lyricAligner.align(song, lines, duration, trustedDuration = playDuration > 0L)
        }.getOrNull()
        if (aligned != null) {
            container.lyricsRepository.writeCache(song.key, aligned)
            container.lyricsRepository.markAligned(song.key)
            if (aligned != lines && playbackState.value.currentSong?.key == song.key) {
                baseLines = aligned
                publishLines()
            }
        }
        // 还没拿到播放器实测时长：交给 init 里的监听在时长就绪后补一次
        if (playDuration > 0L) validatedKey = song.key
    }

    /** 在线取词：先解析，解析空视为没有。 */
    private suspend fun onlineLines(song: Song): List<LyricLine>? {
        if (!song.isOnline) return null
        val raw = runCatching { container.onlineMusicRepository.lyrics(song) }.getOrNull() ?: return null
        return LrcParser.parse(raw).takeIf { it.isNotEmpty() }
    }

    private fun publishLines() {
        val shifted = if (offsetMs == 0L) {
            baseLines
        } else {
            baseLines.map { line ->
                line.copy(
                    timeMs = (line.timeMs + offsetMs).coerceAtLeast(0L),
                    // 逐字时间也要跟着整份时间轴一起平移，否则扫光会和整句高亮脱节
                    words = line.words.map { word ->
                        word.copy(timeMs = (word.timeMs + offsetMs).coerceAtLeast(0L))
                    },
                    // 拼句前的分段时间同理
                    parts = line.parts.map { part ->
                        part.copy(timeMs = (part.timeMs + offsetMs).coerceAtLeast(0L))
                    },
                )
            }
        }
        _lyricsState.value = LyricsUiState.Ready(shifted)
    }

    /**
     * 后台补逐字时间：网易云只有整句时间，而 QQ 音源带逐字 LRC。
     * 这里只借「时间」，不换文本；补到就重新发布一次歌词（歌词页的扫光会自动开始走）。
     * 每首歌只试一次，补不到也记一笔，不会每次进歌词页都重搜一遍。
     *
     * 2026-11 决定：默认关闭。逐字只认「当前歌词源自己带的时间」——
     * 网易云的歌就老老实实整句高亮，QQ 的歌逐字扫光，不做跨源搬运，
     * 免得两个源的断句/文字版本不一致时扫光位置看着对不上。
     */
    private fun enrichWordsAsync(song: Song, lines: List<LyricLine>) {
        if (!CROSS_SOURCE_WORDS) return
        if (!song.isOnline || lines.any { it.hasWords }) return
        if (container.lyricsRepository.isWordsChecked(song.key)) return
        container.lyricsRepository.markWordsChecked(song.key)
        viewModelScope.launch {
            val enriched = runCatching { container.lyricAligner.enrichWords(song, lines) }
                .getOrNull() ?: return@launch
            if (!enriched.any { it.hasWords }) return@launch
            if (playbackState.value.currentSong?.key != song.key) return@launch
            baseLines = enriched
            publishLines()
            container.lyricsRepository.writeCache(song.key, enriched)
        }
    }

    /**
     * 一键校准：把用户长按的那一句对齐到「此刻」，整份歌词随之平移并记住。
     * 用于歌词版本与音频版本本身就差几秒、无法自动判断的个例。
     */
    fun alignLyricLine(lineTimeMs: Long) {
        val song = playbackState.value.currentSong ?: return
        if (baseLines.isEmpty()) return
        val now = playbackState.value.positionMs
        // lineTimeMs 已经是平移后的显示时间，差值直接累加到偏移上
        val delta = now - lineTimeMs
        if (kotlin.math.abs(delta) < 150L) return
        offsetMs += delta
        publishLines()
        viewModelScope.launch {
            container.lyricsRepository.setCalibration(song.key, offsetMs)
        }
    }
}