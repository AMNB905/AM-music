package com.am.music.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.am.music.data.model.Song
import com.am.music.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class PlaybackState(
    val currentSong: Song? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val queue: List<Song> = emptyList(),
    val currentIndex: Int = -1,
    val playMode: PlayMode = PlayMode.LIST_LOOP,
)

@OptIn(UnstableApi::class)
class PlaybackManager(context: Context, private val settings: SettingsRepository) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val prefs = context.getSharedPreferences("playback_settings", Context.MODE_PRIVATE)
    private val appContext = context.applicationContext
    private var controller: MediaController? = null
    private var queue: List<Song> = emptyList()
    private var positionJob: Job? = null

    /** 上次落盘的是哪首 / 何时写的：位置变化频繁，只在必要时刻写盘。 */
    private var persistedSongKey: String? = null
    private var lastPersistAt = 0L

    /** 恢复流程没走完之前不许写盘，否则「空播放器」的一帧会把上次的记录清掉。 */
    private var restoreFinished = false

    /** 正在跑的淡入淡出；连续点pause/play时取消上一条，避免两条曲线打架。 */
    private var fadeJob: Job? = null

    /**
     * 重新解析直链的出口，由 AppContainer 在仓库建好后注入（播放器不认识网络层）。
     * 传 null 表示这一轮没法自救，只能如实告诉用户失败了。
     */
    var networkResolver: (suspend (Song) -> String?)? = null

    /** 已经在为哪首歌重连；同一首只自救一次，避免报错→重连→再报错地转圈。 */
    private var recoveringKey: String? = null

    /** 缓冲看门狗：卡住不动也要说话，别等 Media3 自己超时。 */
    private var bufferJob: Job? = null

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // 换歌了，上一首的重连/看门狗状态作废
            recoveringKey = null
            bufferJob?.cancel()
            syncState()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            syncState()
            watchBuffering(playbackState)
        }

        /**
         * 这里是「卡在一半没人管」的正面修补点。
         *
         * 以前播放器报错只发生在内部，UI 侧一无所知：缓存读到残片末端、直链过期 403、断网，
         * 表现都是同一个——进度条停在原地，什么提示都没有。现在统一收口：
         * 先给用户一句话，再拿一条**新签的**直链从原位置重连；实在拿不到才认输。
         */
        override fun onPlayerError(error: PlaybackException) {
            recover(prefix = if (recoveringKey == null) "播放中断" else "重连失败")
        }
        override fun onRepeatModeChanged(repeatMode: Int) = syncState()
        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = syncState()
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            syncState()
            updatePositionPolling()
        }
    }

    init {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            val connected = future.get()
            controller = connected
            connected.addListener(listener)
            applyMode(connected, savedPlayMode())
            // 进程被杀后重新打开：把上次的歌和位置摆回来（不自动出声）
            restoreLastSession(connected)
            syncState()
            updatePositionPolling()
        }, ContextCompat.getMainExecutor(context))
    }

    fun playSongs(songs: List<Song>, startIndex: Int) {
        val connected = controller ?: return
        // 在线歌曲只有解析出直链（uri）后才可入队；未解析的跳过，锚点索引按 key 重新定位
        val playable = songs.filter { it.uri != Uri.EMPTY && it.uri.toString().isNotBlank() }
        if (playable.isEmpty()) return
        val anchorKey = songs.getOrNull(startIndex)?.key
        val resolvedIndex = playable.indexOfFirst { it.key == anchorKey }.takeIf { it >= 0 } ?: 0

        queue = playable
        connected.setMediaItems(playable.map { it.toMediaItem(settings.qualityValue) }, resolvedIndex, 0L)
        connected.prepare()
        connected.play()
        syncState()
    }

    /**
     * 往队尾追加已解析好的歌曲。首曲先出声音，后续曲目解析好再补进来，
     * 用户不用等整批直链解析完才开始播放。
     */
    fun appendSongs(songs: List<Song>) {
        val connected = controller ?: return
        val playable = songs.filter { it.uri != Uri.EMPTY && it.uri.toString().isNotBlank() }
            .filter { candidate -> queue.none { it.key == candidate.key } }
        if (playable.isEmpty()) return
        queue = queue + playable
        connected.addMediaItems(playable.map { it.toMediaItem(settings.qualityValue) })
        syncState()
    }

    fun togglePlayPause() {
        val connected = controller ?: return
        if (connected.isPlaying) pauseWithFade(connected) else playWithFadeIn(connected)
    }

    /**
     * 定时关闭到点时的暂停出口。
     *
     * 和用户手动暂停走同一条渐隐曲线（240ms 到 0 再 pause，然后复位音量），
     * 半夜被「咔」一下断掉是最难受的收尾。
     */
    fun pauseForSleepTimer() {
        val connected = controller ?: return
        if (connected.isPlaying) pauseWithFade(connected) else runCatching { connected.pause() }
    }

    /**
     * 暂停不硬切：240ms 内把音量推到 0 再 pause，听感是「渐隐」，不是「咔」一下断掉。
     *
     * MediaController 的音量就是会话播放器的音量，逐帧调它就是真的在降音量（不是 UI 假动作）。
     * 停完立刻把音量复位到 1，否则某些设备会记住 0，下次播放一片死寂。
     */
    private fun pauseWithFade(connected: MediaController) {
        fadeJob?.cancel()
        fadeJob = scope.launch {
            val steps = FADE_STEPS
            for (i in 1..steps) {
                runCatching { connected.volume = 1f - i.toFloat() / steps }
                delay(FADE_STEP_MS)
            }
            runCatching { connected.pause() }
            runCatching { connected.volume = 1f }
        }
    }

    /** 恢复播放同样淡入：先把音量压到 0，出声后 240ms 内推回 1。 */
    private fun playWithFadeIn(connected: MediaController) {
        fadeJob?.cancel()
        fadeJob = scope.launch {
            runCatching { connected.volume = 0f }
            runCatching { connected.play() }
            val steps = FADE_STEPS
            for (i in 1..steps) {
                runCatching { connected.volume = i.toFloat() / steps }
                delay(FADE_STEP_MS)
            }
            runCatching { connected.volume = 1f }
        }
    }

    fun next() {
        controller?.seekToNextMediaItem()
    }

    fun previous() {
        controller?.seekToPreviousMediaItem()
    }

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs)
        syncState()
    }

    fun playAt(index: Int) {
        val connected = controller ?: return
        if (index !in queue.indices) return
        connected.seekTo(index, 0L)
        connected.play()
        syncState()
    }

    fun removeFromQueue(index: Int) {
        val connected = controller ?: return
        if (index !in queue.indices) return
        queue = queue.toMutableList().apply { removeAt(index) }
        connected.removeMediaItem(index)
        syncState()
    }

    fun clearQueue() {
        val connected = controller ?: return
        queue = emptyList()
        connected.clearMediaItems()
        syncState()
    }

    fun setPlayMode(mode: PlayMode) {
        val connected = controller ?: return
        applyMode(connected, mode)
        prefs.edit().putString(KEY_PLAY_MODE, mode.name).apply()
        syncState()
    }

    fun cyclePlayMode() {
        val next = when (_state.value.playMode) {
            PlayMode.LIST_LOOP -> PlayMode.SINGLE_LOOP
            PlayMode.SINGLE_LOOP -> PlayMode.SHUFFLE
            PlayMode.SHUFFLE -> PlayMode.LIST_LOOP
        }
        setPlayMode(next)
    }

    private fun savedPlayMode(): PlayMode {
        val name = prefs.getString(KEY_PLAY_MODE, null)
        return PlayMode.entries.firstOrNull { it.name == name } ?: PlayMode.LIST_LOOP
    }

    private fun applyMode(controller: MediaController, mode: PlayMode) {
        when (mode) {
            PlayMode.LIST_LOOP -> {
                controller.shuffleModeEnabled = false
                controller.repeatMode = Player.REPEAT_MODE_ALL
            }
            PlayMode.SINGLE_LOOP -> {
                controller.shuffleModeEnabled = false
                controller.repeatMode = Player.REPEAT_MODE_ONE
            }
            PlayMode.SHUFFLE -> {
                controller.shuffleModeEnabled = true
                controller.repeatMode = Player.REPEAT_MODE_ALL
            }
        }
    }

    /** 显示一句话，几秒后自己消失（不打断任何操作）。 */
    private fun toast(text: String) = PlaybackNotice.brief(text)

    /**
     * 缓冲看门狗：Media3 默认会等很久才报错，等的过程在用户眼里就是「卡死」。
     * 6 秒还没出声音就先说话；再过 14 秒仍无起色，按错误处理（换直链重连）。
     */
    private fun watchBuffering(playbackState: Int) {
        bufferJob?.cancel()
        if (playbackState != Player.STATE_BUFFERING) return
        val song = _state.value.currentSong ?: return
        bufferJob = scope.launch {
            delay(6_000L)
            if (controller?.playbackState != Player.STATE_BUFFERING) return@launch
            toast(
                if (song.cachedQuality != null) "缓存不全，补数据中…"
                else "网络慢，缓冲中：${song.title}",
            )
            delay(14_000L)
            if (controller?.playbackState != Player.STATE_BUFFERING) return@launch
            recover(prefix = "缓冲超时")
        }
    }

    /**
     * 自愈：丢掉失败的地址，重新签一条直链，从当前位置接着播。
     *
     * 缓存优先的曲目在这里会顺带「降级」成网络曲目（cachedQuality 置空），
     * 因为残片已经证明不可靠；后面边播边补，补满了下次自然又走本地。
     */
    private fun recover(prefix: String) {
        val connected = controller ?: return
        val index = connected.currentMediaItemIndex
        val song = queue.getOrNull(index) ?: return
        val resolver = networkResolver

        if (resolver == null || recoveringKey == song.key) {
            toast("$prefix：这首放不了")
            return
        }

        val position = connected.currentPosition.coerceAtLeast(0L)
        val wasPlaying = connected.isPlaying
        recoveringKey = song.key
        bufferJob?.cancel()

        scope.launch {
            // 只在「本来在播」的时候出声提示；启动恢复直链过期时安静补签，别一开 App 就弹提示/直接响起
            if (wasPlaying) {
                toast(if (song.cachedQuality != null) "缓存不全，改联网播…" else "网络不稳，重连中…")
            }
            val url = runCatching { resolver(song) }.getOrNull()
            if (url.isNullOrBlank()) {
                if (wasPlaying) toast("连不上服务器，检查下网络")
                recoveringKey = null
                return@launch
            }
            val fresh = song.copy(
                uri = Uri.parse(url),
                streamUrl = url,
                cachedQuality = null,
            )
            if (index in queue.indices) {
                queue = queue.toMutableList().apply { set(index, fresh) }
            }
            runCatching { connected.replaceMediaItem(index, fresh.toMediaItem(settings.qualityValue)) }
            runCatching { connected.seekTo(index, position) }
            runCatching { connected.prepare() }
            if (wasPlaying) {
                runCatching { connected.play() }
                toast(if (song.cachedQuality != null) "已改联网播" else "已重连，继续播")
            }
            recoveringKey = null
            syncState()
        }
    }

    private fun syncState() {
        val connected = controller ?: return
        val index = connected.currentMediaItemIndex
        _state.value = PlaybackState(
            currentSong = queue.getOrNull(index),
            isPlaying = connected.isPlaying,
            positionMs = connected.currentPosition.coerceAtLeast(0L),
            durationMs = connected.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0L) ?: 0L,
            queue = queue,
            currentIndex = index,
            playMode = when {
                connected.shuffleModeEnabled -> PlayMode.SHUFFLE
                connected.repeatMode == Player.REPEAT_MODE_ONE -> PlayMode.SINGLE_LOOP
                else -> PlayMode.LIST_LOOP
            },
        )
        persistState()
    }

    /**
     * 记住「听到哪」：换歌、暂停、切后台安静下来时都写一次；
     * 播放中按 3 秒一次节流，避免每 500ms 轮询都去碰一次磁盘。
     */
    private fun persistState() {
        if (!restoreFinished) return
        val current = _state.value
        val song = current.currentSong
        if (song == null) {
            if (persistedSongKey != null) {
                PlaybackResume.save(appContext, null, 0L, emptyList(), 0)
                persistedSongKey = null
            }
            return
        }
        val now = SystemClock.elapsedRealtime()
        val force = song.key != persistedSongKey || !current.isPlaying
        if (!force && now - lastPersistAt < 3_000L) return
        lastPersistAt = now
        persistedSongKey = song.key
        PlaybackResume.save(appContext, song, current.positionMs, current.queue, current.currentIndex)
    }

    /** 只在播放器还是空的时候恢复，避免覆盖「服务还活着、正在播」的现状。 */
    private fun restoreLastSession(connected: MediaController) {
        restoreFinished = true
        if (connected.mediaItemCount > 0) return
        val snapshot = PlaybackResume.load(appContext) ?: return
        // 只恢复确实放得出来的曲目：已下载 / 有完整缓存的优先，
        // 其它要求 uri 非空（在线曲目存的是上次签的直链，过期了会走一次重连）
        val playable = snapshot.queue.filter {
            it.downloadedQuality != null || it.cachedQuality != null || it.uri.toString().isNotBlank()
        }
        if (playable.isEmpty()) return
        val anchorKey = snapshot.queue.getOrNull(snapshot.index)?.key
        val index = playable.indexOfFirst { it.key == anchorKey }.takeIf { it >= 0 } ?: 0
        queue = playable
        runCatching {
            connected.setMediaItems(
                playable.map { it.toMediaItem(settings.qualityValue) },
                index,
                snapshot.positionMs,
            )
            connected.prepare()
        }.onFailure { queue = emptyList() }
    }

    private fun updatePositionPolling() {
        positionJob?.cancel()
        val connected = controller ?: return
        if (!connected.isPlaying) return
        positionJob = scope.launch {
            while (isActive) {
                _state.update { it.copy(positionMs = connected.currentPosition.coerceAtLeast(0L)) }
                persistState()
                delay(500)
            }
        }
    }

    companion object {
        private const val KEY_PLAY_MODE = "play_mode"

        /** 淡入/淡出：12 步 × 20ms ≈ 240ms，够听出「渐」又不至于让人等。 */
        private const val FADE_STEPS = 12
        private const val FADE_STEP_MS = 20L
    }
}

/**
 * 曲目 → MediaItem。
 *
 * `cacheKey` 必须带上音质：直链是「一次一签」的地址，URL 本身每次都不同，
 * 不带音质区分会让不同码流挤在同一个缓存 key 上。
 *
 * 带 [Song.cachedQuality] 的曲目表示「整首已落在本地」：这时用占位 uri + 与缓存
 * 档位一致的 cacheKey，播放器直接读磁盘，既不解析直链也不回源（离线也能放）。
 */
@OptIn(UnstableApi::class)
private fun Song.toMediaItem(quality: String?): MediaItem {
    // 已下载：uri 就是本地文件，既不读缓存占位地址，也别再往播放缓存里抄一份
    if (downloadedQuality != null && localPath != null) {
        return MediaItem.Builder()
            .setMediaId(key)
            .setUri(Uri.fromFile(java.io.File(localPath)))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .setAlbumTitle(album)
                    .setArtworkUri(albumArtUri)
                    .build()
            )
            .build()
    }
    val effectiveQuality = cachedQuality ?: quality
    val itemUri = if (cachedQuality != null) {
        MediaCache.placeholderUri(key, cachedQuality)
    } else {
        uri
    }
    return MediaItem.Builder()
        .setMediaId(key)
        .setUri(itemUri)
        .setCustomCacheKey(
            if (effectiveQuality.isNullOrEmpty()) key else MediaCache.cacheKey(key, effectiveQuality)
        )
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .setArtworkUri(albumArtUri)
                .build()
        )
        .build()
}