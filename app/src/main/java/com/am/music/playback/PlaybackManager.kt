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
import kotlinx.coroutines.withContext

data class PlaybackState(
    val currentSong: Song? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    /** 整条队列（歌单全量），包含还没签出直链的曲目——队列页据此显示全部，而不是只显示「已解析的几首」。 */
    val queue: List<Song> = emptyList(),
    val currentIndex: Int = -1,
    val playMode: PlayMode = PlayMode.LIST_LOOP,
)

/**
 * 播放内核：**虚拟队列 + 已解析窗口**。
 *
 * 以前的队列就是 Media3 手里那几张 mediaItem：在线曲要先签直链才能入队，
 * 于是队列里永远只有「当前 + 后面几首」，歌单第二首之后就切不动、往上更切不动，
 * 队列页也只显示这一小截。
 *
 * 现在拆成两层：
 * - [virtual]：用户心里的那条队列（整张歌单 / 搜索结果 / 榜单的全量），顺序、增删、拖动排序都作用在这一层；
 * - [win]：真正交到 Media3 手里、已经签好直链的那一段窗口（前后各留几首，够切几下手感是连续的）。
 *
 * 换歌时先看目标在不在窗口里：在就直接 seek（瞬时）；不在就先签直链、把它摆成新的窗口中心。
 * 窗口以外的曲目永远不需要提前签地址，直链也不会提前放到过期。
 */
@OptIn(UnstableApi::class)
class PlaybackManager(context: Context, private val settings: SettingsRepository) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val prefs = context.getSharedPreferences("playback_settings", Context.MODE_PRIVATE)
    private val appContext = context.applicationContext
    private var controller: MediaController? = null
    private var positionJob: Job? = null

    /** 窗口里的一项：拿 Media3 的下标去查虚拟队列的下标（两者不是一个坐标系）。 */
    private data class WinItem(val vIndex: Int, val song: Song)

    /** 缓存下来的可播曲目 + 落库时刻。 */
    private class CachedAudio(val song: Song, val at: Long)

    /** 用户意义上的整条队列。 */
    private var virtual: List<Song> = emptyList()

    /** 当前曲目在 [virtual] 里的位置。 */
    private var vIndex = -1

    /** 交给 Media3 的那一段（顺序必须与播放器里一致）。 */
    private var win: MutableList<WinItem> = mutableListOf()

    /** 打乱模式下保留一份原始顺序，关掉打乱要能还原。 */
    private var unshuffled: List<Song>? = null

    /** 当前播放模式（队列推进的规则就靠它）。 */
    private var mode: PlayMode = PlayMode.LIST_LOOP

    /** 连续快速切歌时作废旧的那几次「先签直链再落地」，只有最后一次算数。 */
    private var jumpToken = 0

    /**
     * 签出来的直链按曲目缓存一小段时间：来回切、拖动排序、跳回去听同一首都不再重签。
     * 直链本身有有效期，所以带 TTL，过期就重新签。
     */
    private val resolvedCache = object : LinkedHashMap<String, CachedAudio>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedAudio>) =
            size > RESOLVE_CACHE_SIZE
    }

    /** 同一首正在签的时刻标记：并发补料不会为同一首歌发两次请求。 */
    private val resolving = mutableSetOf<String>()

    /** 签失败的时刻：短时间内不重复试同一首（断网时尤其重要）。 */
    private val failedAt = HashMap<String, Long>()

    /** 上次落盘的是哪首 / 何时写的：位置变化频繁，只在必要时刻写盘。 */
    private var persistedSongKey: String? = null
    private var lastPersistAt = 0L

    /** 恢复流程没走完之前不许写盘，否则「空播放器」的一帧会把上次的记录清掉。 */
    private var restoreFinished = false

    /** 正在跑的淡入淡出；连续点 pause/play 时取消上一条，避免两条曲线打架。 */
    private var fadeJob: Job? = null

    /** 队列补料：同一时刻只跑一条，避免并发 append 把顺序打乱。 */
    private var fillJob: Job? = null

    /**
     * 重新解析直链的出口，由 AppContainer 在仓库建好后注入（播放器不认识网络层）。
     * 传 null 表示这一轮没法自救，只能如实告诉用户失败了。
     */
    var networkResolver: (suspend (Song) -> String?)? = null

    /** 队列补料的解析出口：把一首「还没有地址」的候选曲解析成可入队的曲目。 */
    var queueResolver: (suspend (Song) -> Song?)? = null

    /** 已经在为哪首歌重连；同一首只自救一次，避免报错→重连→再报错地转圈。 */
    private var recoveringKey: String? = null

    /** 缓冲看门狗：卡住不动也要说话，别等 Media3 自己超时。 */
    private var bufferJob: Job? = null

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val connected = controller ?: return
            val cIndex = connected.currentMediaItemIndex
            val item = win.getOrNull(cIndex) ?: return
            // 前后插曲目时 Media3 也会回调一次，目标没变就别打断当前状态
            if (item.vIndex != vIndex) {
                vIndex = item.vIndex
                recoveringKey = null
                bufferJob?.cancel()
            }
            syncState()
            kickFill()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            syncState()
            watchBuffering(playbackState)
            // 窗口放完了：交给虚拟队列决定下一首（列表循环回到第一首、单曲循环原地重来）
            if (playbackState == Player.STATE_ENDED) onWindowEnded()
        }

        /**
         * 「卡在一半没人管」的正面修补点：缓存残片、直链过期、断网，表现都是同一个——
         * 进度条停在原地什么提示都没有。统一收口：先给一句话，再拿**新签的**直链从原位置重连。
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
            mode = savedPlayMode()
            applyMode(connected, mode)
            // 进程被杀后重新打开：把上次的歌和位置摆回来（不自动出声）
            restoreLastSession(connected)
            syncState()
            updatePositionPolling()
        }, ContextCompat.getMainExecutor(context))
    }

    // ------------------------------------------------------------------ 起播

    /**
     * 从某个列表的第 [index] 首起播，整条列表都成为队列。
     *
     * [anchor] 是已经签好直链的那一首（调用方通常已经解析过，避免再等一次）。
     * 列表里的其它曲目可以没有地址，需要时再签——队列因此一开始就是完整的。
     */
    fun playContext(songs: List<Song>, index: Int, anchor: Song? = null) {
        if (songs.isEmpty()) return
        val safeIndex = index.coerceIn(0, songs.lastIndex)
        val prepared = songs.toMutableList().apply { anchor?.let { this[safeIndex] = it } }

        val song = prepared[safeIndex]
        if (!isPlayable(song)) {
            // 连被点的那一首都还没有地址：先签它，签出来再走正常流程
            scope.launch {
                val resolved = resolveOne(song)
                if (resolved == null) {
                    PlaybackNotice.brief(
                        if (song.isOnline) "连不上服务器，检查下网络" else "没可播地址：离线只能放缓存/本地的歌"
                    )
                    return@launch
                }
                playContext(prepared, safeIndex, resolved)
            }
            return
        }

        val connected = controller ?: return
        virtual = prepared
        vIndex = safeIndex
        unshuffled = null
        jumpToken++
        win.clear()
        win += WinItem(safeIndex, song)
        connected.setMediaItems(listOf(song.toMediaItem(settings.qualityValue)), 0, 0L)
        connected.prepare()
        connected.play()
        syncState()
        kickFill()
    }

    // ------------------------------------------------------------------ 窗口补料

    private fun isPlayable(song: Song): Boolean =
        song.downloadedQuality != null ||
            song.cachedQuality != null ||
            (song.uri != Uri.EMPTY && song.uri.toString().isNotBlank())

    /** 补料的结果：能播 / 签不出来 / 别人正在签（这一轮先跳过，不重复请求）。 */
    private sealed interface ResolveResult {
        data class Ready(val song: Song) : ResolveResult
        data object Failed : ResolveResult
        data object Busy : ResolveResult
    }

    private suspend fun resolveForQueue(song: Song): ResolveResult {
        if (isPlayable(song)) return ResolveResult.Ready(song)

        val now = SystemClock.elapsedRealtime()
        // 1) 命中缓存：来回切、拖动排序、跳回去听同一首都不再重签
        resolvedCache[song.key]?.takeIf { now - it.at < RESOLVE_TTL_MS }
            ?.let { return ResolveResult.Ready(it.song) }
        // 2) 刚失败过：短时间内不重复打同一个接口（断网时尤其重要）
        failedAt[song.key]?.let { if (now - it < FAIL_TTL_MS) return ResolveResult.Failed }
        // 3) 同一首已经有人在签：这一轮直接跳过
        if (!resolving.add(song.key)) return ResolveResult.Busy

        try {
            val resolved = queueResolver?.let { resolver ->
                runCatching { resolver(song) }.getOrNull()
            } ?: networkResolver?.let { resolver ->
                runCatching { resolver(song) }.getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { song.copy(uri = Uri.parse(it), streamUrl = it, cachedQuality = null) }
            }
            return if (resolved != null) {
                resolvedCache[song.key] = CachedAudio(resolved, SystemClock.elapsedRealtime())
                failedAt.remove(song.key)
                ResolveResult.Ready(resolved)
            } else {
                failedAt[song.key] = SystemClock.elapsedRealtime()
                ResolveResult.Failed
            }
        } finally {
            resolving.remove(song.key)
        }
    }

    /** 「我要这一首，现在就播」：只取能播的结果，其它情况交给调用方给提示。 */
    private suspend fun resolveOne(song: Song): Song? =
        (resolveForQueue(song) as? ResolveResult.Ready)?.song

    /**
     * 围绕当前曲目把窗口铺开：前面留 [BEHIND] 首（保证「上一首」一点就响），
     * 后面留 [AHEAD] 首（正常播放时下一首永远已经签好）。
     *
     * 补料只在「真的不够用」时才跑，而且每次只补一首——一首歌一次请求，
     * 切歌频率再高也不会排队打接口。
     */
    private fun kickFill() {
        if (fillJob?.isActive == true) return
        if (controller == null) return
        if (vIndex < 0 || virtual.isEmpty()) return
        fillJob = scope.launch {
            while (isActive) {
                val progressed = fillBehind() or fillAhead()
                if (!progressed) break
            }
        }
    }

    /** 前向补料：队尾离当前不足 AHEAD 首，就按虚拟顺序往下签。 */
    private suspend fun fillAhead(): Boolean {
        val connected = controller ?: return false
        val nearest = controllerIndexOf(vIndex)
        if (nearest < 0) return false
        val resolvedAhead = win.size - 1 - nearest
        if (resolvedAhead >= AHEAD) return false
        val end = win.maxOfOrNull { it.vIndex } ?: vIndex
        val next = end + 1
        if (next > virtual.lastIndex) return false

        val candidate = virtual[next]
        val resolved = withContext(Dispatchers.IO) { resolveForQueue(candidate) }
        when (resolved) {
            // 别人正在签它：这一轮先停，等下一次 kick
            ResolveResult.Busy -> return false
            ResolveResult.Failed -> {
                // 签不出来（版权/网络）就把这首摘掉，别在队列里摆一首永远放不了的歌
                dropVirtual(next)
                return true
            }
            is ResolveResult.Ready -> {
                win += WinItem(next, resolved.song)
                connected.addMediaItems(listOf(resolved.song.toMediaItem(settings.qualityValue)))
                syncState()
                return true
            }
        }
    }

    /** 后向补料：队头离当前不足 BEHIND 首，就往前签，让「上一首」也能一点就响。 */
    private suspend fun fillBehind(): Boolean {
        val connected = controller ?: return false
        val start = win.minOfOrNull { it.vIndex } ?: vIndex
        val resolvedBehind = controllerIndexOf(vIndex)
        if (resolvedBehind >= BEHIND) return false
        val prev = start - 1
        if (prev < 0) return false

        val candidate = virtual[prev]
        val resolved = withContext(Dispatchers.IO) { resolveForQueue(candidate) }
        when (resolved) {
            ResolveResult.Busy -> return false
            ResolveResult.Failed -> {
                dropVirtual(prev)
                return true
            }
            is ResolveResult.Ready -> {
                win.add(0, WinItem(prev, resolved.song))
                connected.addMediaItems(0, listOf(resolved.song.toMediaItem(settings.qualityValue)))
                syncState()
                return true
            }
        }
    }

    /** 从队列里摘掉一首（解析失败用）。窗口里若有它，一起摘；坐标靠 key 重算。 */
    private fun dropVirtual(index: Int) {
        if (index !in virtual.indices) return
        val connected = controller ?: return
        virtual = virtual.toMutableList().apply { removeAt(index) }

        val winPos = win.indexOfFirst { it.vIndex == index }
        if (winPos >= 0) {
            win.removeAt(winPos)
            runCatching { connected.removeMediaItem(winPos) }
        }
        if (vIndex >= virtual.size) vIndex = virtual.lastIndex
        if (index < vIndex) vIndex--
        reindexWindowByKey()
        if (virtual.isEmpty()) {
            clearQueue()
            return
        }
        if (win.isEmpty()) {
            // 窗口被摘空了：围着当前位置重建
            rebuildAt(vIndex)
        }
        syncState()
    }

    private fun controllerIndexOf(virtualIndex: Int): Int = win.indexOfFirst { it.vIndex == virtualIndex }

    /** 增删 / 排序之后，窗口里每一项的虚拟坐标都要按 key 重新对一遍。 */
    private fun reindexWindowByKey() {
        val positions = virtual.mapIndexed { i, s -> s.key to i }.toMap()
        val fixed = win.mapNotNull { item ->
            positions[item.song.key]?.let { WinItem(it, item.song) }
        }
        win.clear()
        win += fixed
    }

    // ------------------------------------------------------------------ 切歌

    fun next() {
        if (virtual.isEmpty()) return
        jumpTo((vIndex + 1) % virtual.size)
    }

    fun previous() {
        val connected = controller ?: return
        // 放了一会儿再按上一首 = 重放这一首（各家播放器的老规矩）
        if (connected.currentPosition > 3_000L) {
            connected.seekTo(0L)
            syncState()
            return
        }
        if (virtual.isEmpty()) return
        jumpTo(if (vIndex > 0) vIndex - 1 else virtual.lastIndex)
    }

    fun playAt(index: Int) {
        if (index !in virtual.indices) return
        if (index == vIndex) {
            controller?.seekTo(0L)
            controller?.play()
            syncState()
            return
        }
        jumpTo(index)
    }

    /**
     * 跳到虚拟队列的第 [index] 首。
     *
     * 目标在窗口里就直接 seek（不重新 prepare，切歌是瞬时的）；
     * 不在就先把它签出来、摆成新的窗口中心，再由补料把前后铺好。
     */
    private fun jumpTo(index: Int) {
        if (index !in virtual.indices) return
        val connected = controller ?: return
        val cIndex = controllerIndexOf(index)
        if (cIndex >= 0) {
            vIndex = index
            connected.seekTo(cIndex, 0L)
            connected.play()
            syncState()
            kickFill()
            return
        }
        // 目标还没签：先把它标成当前（UI 立刻高亮），再在后台签直链、重建窗口
        val wasPlaying = connected.isPlaying
        vIndex = index
        syncState()
        val target = virtual[index]
        if (isPlayable(target)) {
            rebuildWindow(index, target, play = wasPlaying)
            return
        }
        val token = ++jumpToken
        scope.launch {
            val resolved = resolveOne(target)
            // 期间用户又按了别的：这一次作废，别把画面拽回去
            if (token != jumpToken) return@launch
            if (resolved == null) {
                PlaybackNotice.brief(if (target.isOnline) "连不上服务器，检查下网络" else "这首没有可播地址")
                return@launch
            }
            rebuildWindow(index, resolved, play = true)
        }
    }

    /**
     * 把窗口收成「只有这一首」，再从它往前 / 往后铺开。
     *
     * [positionMs] 用在「原地重排」这类不换歌的场合：接着当前位置继续放，
     * 不至于因为重排窗口就把一整首歌从头再来。
     */
    private fun rebuildWindow(index: Int, song: Song, play: Boolean, positionMs: Long = 0L) {
        val connected = controller ?: return
        if (index !in virtual.indices) return
        // 签出来的这一首写回虚拟队列：落盘、重连、UI 都拿到的是可播版本
        if (isPlayable(song) && virtual[index].key == song.key && virtual[index].uri != song.uri) {
            virtual = virtual.toMutableList().apply { set(index, song) }
        }
        vIndex = index
        win.clear()
        win += WinItem(index, song)
        connected.setMediaItems(listOf(song.toMediaItem(settings.qualityValue)), 0, positionMs)
        connected.prepare()
        if (play) connected.play()
        syncState()
        kickFill()
    }

    /** 用当前位置的曲目重建窗口（摘空、乱序之后用）。 */
    private fun rebuildAt(index: Int) {
        val song = virtual.getOrNull(index) ?: return
        rebuildWindow(index, song, play = false)
    }

    // ------------------------------------------------------------------ 播放中 / 暂停

    fun togglePlayPause() {
        val connected = controller ?: return
        if (connected.isPlaying) pauseWithFade(connected) else playWithFadeIn(connected)
    }

    /**
     * 定时关闭到点时的暂停出口。和用户手动暂停走同一条渐隐曲线，
     * 半夜被「咔」一下断掉是最难受的收尾。
     */
    fun pauseForSleepTimer() {
        val connected = controller ?: return
        if (connected.isPlaying) pauseWithFade(connected) else runCatching { connected.pause() }
    }

    /**
     * 暂停不硬切：240ms 内把音量推到 0 再 pause；停完立刻复位音量到 1，
     * 否则某些设备会记住 0，下次播放一片死寂。
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

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs)
        syncState()
    }

    // ------------------------------------------------------------------ 队列编辑

    fun removeFromQueue(index: Int) {
        if (index !in virtual.indices) return
        val connected = controller ?: return
        val wasCurrent = index == vIndex
        virtual = virtual.toMutableList().apply { removeAt(index) }
        val cIndex = win.indexOfFirst { it.vIndex == index }
        if (cIndex >= 0) {
            win.removeAt(cIndex)
            runCatching { connected.removeMediaItem(cIndex) }
        }
        if (wasCurrent) {
            vIndex = index.coerceAtMost(virtual.lastIndex)
            val nextSong = virtual.getOrNull(vIndex)
            if (nextSong != null) {
                val inWin = controllerIndexOf(vIndex)
                if (inWin >= 0) {
                    connected.seekTo(inWin, 0L)
                    connected.play()
                } else {
                    jumpTo(vIndex)
                }
            } else {
                clearQueue()
                return
            }
        } else if (index < vIndex) {
            vIndex--
        }
        reindexWindowByKey()
        if (virtual.isEmpty()) {
            clearQueue()
            return
        }
        syncState()
        kickFill()
    }

    /**
     * 队列内拖动排序。
     *
     * 只动虚拟顺序；如果两端都在窗口里，顺便把播放器里的顺序也挪一下（不打断播放）。
     * 在窗口外的话，等它被铺进窗口时自然按新顺序进来。
     */
    fun moveInQueue(from: Int, to: Int) {
        if (from == to) return
        if (from !in virtual.indices || to !in virtual.indices) return
        val currentKey = virtual.getOrNull(vIndex)?.key
        virtual = virtual.toMutableList().apply {
            val song = removeAt(from)
            add(to.coerceIn(0, size), song)
        }
        // 当前曲目在队列里的位置可能被拖动改掉，跟着它走
        vIndex = virtual.indexOfFirst { it.key == currentKey }.takeIf { it >= 0 } ?: vIndex

        val connected = controller ?: return
        val fromWin = win.indexOfFirst { it.vIndex == from }
        val toWin = win.indexOfFirst { it.vIndex == to }
        if (fromWin >= 0 && toWin >= 0) {
            runCatching { connected.moveMediaItem(fromWin, toWin) }
            val item = win.removeAt(fromWin)
            win.add(toWin, item)
        }
        reindexWindowByKey()
        syncState()
    }

    /** 插队播放：把这首塞到当前曲目后面，下一首就放它。 */
    fun playNext(song: Song) {
        if (vIndex < 0) {
            playContext(listOf(song), 0)
            return
        }
        scope.launch {
            val resolved = resolveOne(song)
            if (resolved == null) {
                PlaybackNotice.brief(if (song.isOnline) "连不上服务器，检查下网络" else "这首没有可播地址")
                return@launch
            }
            val insertAt = (vIndex + 1).coerceIn(0, virtual.size)
            virtual = virtual.toMutableList().apply { add(insertAt, resolved) }
            if (insertAt <= vIndex) vIndex++
            reindexWindowByKey()

            val connected = controller ?: return@launch
            val currentC = controllerIndexOf(vIndex)
            if (currentC >= 0) {
                // 直接插到播放器中当前项的后面：不重建、不断播，下一首立刻是它
                win.add(currentC + 1, WinItem(insertAt, resolved))
                runCatching { connected.addMediaItems(currentC + 1, listOf(resolved.toMediaItem(settings.qualityValue))) }
            }
            PlaybackNotice.brief("已插到下一首：${resolved.title}")
            syncState()
        }
    }

    fun clearQueue() {
        val connected = controller ?: return
        virtual = emptyList()
        vIndex = -1
        win.clear()
        unshuffled = null
        fillJob?.cancel()
        connected.clearMediaItems()
        syncState()
    }

    // ------------------------------------------------------------------ 播放模式

    fun setPlayMode(mode: PlayMode) {
        val connected = controller ?: return
        this.mode = mode
        applyMode(connected, mode)
        prefs.edit().putString(KEY_PLAY_MODE, mode.name).apply()
        applyQueueOrderingForMode(mode)
        syncState()
    }

    fun cyclePlayMode() {
        val next = when (mode) {
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

    /**
     * 打乱不再交给 Media3（那只会把窗口里那几首打乱，等于反复听同几首），
     * 而是把**虚拟队列里还没听的那一段**洗一遍：后面每一首都随机，且互不重复。
     */
    private fun applyQueueOrderingForMode(mode: PlayMode) {
        if (virtual.size < 2 || vIndex < 0) return
        when (mode) {
            PlayMode.SHUFFLE -> {
                val base = unshuffled ?: virtual
                unshuffled = base
                val curIndex = base.indexOfFirst { it.key == virtual.getOrNull(vIndex)?.key }
                    .takeIf { it >= 0 } ?: vIndex
                // 已经听过的这一段顺序不动，只把后面没听的洗一遍：
                // 当前这首保持原位，切过去之后每一首都是新的
                virtual = base.take(curIndex + 1) + base.drop(curIndex + 1).shuffled()
                vIndex = curIndex
            }
            PlayMode.LIST_LOOP -> {
                val base = unshuffled ?: return
                val currentKey = virtual.getOrNull(vIndex)?.key
                virtual = base
                vIndex = base.indexOfFirst { it.key == currentKey }.takeIf { it >= 0 } ?: 0
                unshuffled = null
            }
            PlayMode.SINGLE_LOOP -> return
        }
        // 顺序变了，窗口里的坐标已经不对：用当前曲目原地重建（接着当前位置放）
        val song = virtual.getOrNull(vIndex) ?: return
        val position = controller?.currentPosition ?: 0L
        val wasPlaying = controller?.isPlaying ?: false
        rebuildWindow(vIndex, song, play = wasPlaying, positionMs = position)
    }

    private fun applyMode(controller: MediaController, mode: PlayMode) {
        // 队列推进由我们自己按虚拟队列算，所以播放器只需要「到底了别自己循环」
        controller.shuffleModeEnabled = false
        controller.repeatMode = if (mode == PlayMode.SINGLE_LOOP) {
            Player.REPEAT_MODE_ONE
        } else {
            Player.REPEAT_MODE_OFF
        }
    }

    /** 窗口放到底了：按虚拟队列往下走。 */
    private fun onWindowEnded() {
        when (mode) {
            PlayMode.SINGLE_LOOP -> {
                controller?.seekTo(0L)
                controller?.play()
            }
            else -> next()
        }
    }

    // ------------------------------------------------------------------ 自愈 / 提示

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
        val song = _state.value.currentSong ?: return
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
            if (wasPlaying) {
                toast(if (song.cachedQuality != null) "缓存不全，改联网播…" else "网络不稳，重连中…")
            }
            val url = runCatching { resolver(song) }.getOrNull()
            if (url.isNullOrBlank()) {
                if (wasPlaying) toast("连不上服务器，检查下网络")
                recoveringKey = null
                return@launch
            }
            val fresh = song.copy(uri = Uri.parse(url), streamUrl = url, cachedQuality = null)
            val cIndex = controllerIndexOf(vIndex)
            if (cIndex >= 0) {
                win[cIndex] = WinItem(vIndex, fresh)
                runCatching { connected.replaceMediaItem(cIndex, fresh.toMediaItem(settings.qualityValue)) }
                runCatching { connected.seekTo(cIndex, position) }
            }
            if (vIndex in virtual.indices) {
                virtual = virtual.toMutableList().apply { set(vIndex, fresh) }
            }
            runCatching { connected.prepare() }
            if (wasPlaying) {
                runCatching { connected.play() }
                toast(if (song.cachedQuality != null) "已改联网播" else "已重连，继续播")
            }
            recoveringKey = null
            syncState()
        }
    }

    // ------------------------------------------------------------------ 状态同步

    private fun syncState() {
        val connected = controller ?: return
        val currentSong = if (vIndex in virtual.indices) virtual[vIndex] else win.getOrNull(connected.currentMediaItemIndex)?.song
        _state.value = PlaybackState(
            currentSong = currentSong,
            isPlaying = connected.isPlaying,
            positionMs = connected.currentPosition.coerceAtLeast(0L),
            durationMs = connected.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0L) ?: 0L,
            queue = virtual,
            currentIndex = vIndex,
            playMode = mode,
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
        // 700 首的歌单没法整条落盘：截当前前后一段，顺序仍然是对的，
        // 下次开机从这首歌接着放，而不是被 coerce 到第 1 首
        val q = current.queue
        val i = current.currentIndex
        val snapshot = if (q.size <= PlaybackResume.MAX_QUEUE) {
            q to i
        } else {
            val start = (i - 40).coerceIn(0, (q.size - PlaybackResume.MAX_QUEUE).coerceAtLeast(0))
            q.subList(start, (start + PlaybackResume.MAX_QUEUE).coerceAtMost(q.size)) to (i - start)
        }
        PlaybackResume.save(appContext, song, current.positionMs, snapshot.first, snapshot.second)
    }

    /** 只在播放器还是空的时候恢复，避免覆盖「服务还活着、正在播」的现状。 */
    private fun restoreLastSession(connected: MediaController) {
        restoreFinished = true
        if (connected.mediaItemCount > 0) return
        val snapshot = PlaybackResume.load(appContext) ?: return
        if (snapshot.queue.isEmpty()) return

        virtual = snapshot.queue
        val anchor = snapshot.queue.getOrNull(snapshot.index) ?: snapshot.queue.first()
        vIndex = snapshot.queue.indexOfFirst { it.key == anchor.key }.coerceAtLeast(0)
        unshuffled = null
        win.clear()

        // 只摆当前这一首（其余等播放时再签）：旧直链可能早过期，提前塞进去只会一开机就报错
        val playable = anchor.downloadedQuality != null || anchor.cachedQuality != null ||
            anchor.uri.toString().isNotBlank()
        if (playable) {
            win += WinItem(vIndex, anchor)
            runCatching {
                connected.setMediaItems(listOf(anchor.toMediaItem(settings.qualityValue)), 0, snapshot.positionMs)
                connected.prepare()
            }
            syncState()
        } else {
            scope.launch {
                val resolved = resolveOne(anchor)
                if (resolved != null) {
                    if (vIndex in virtual.indices) {
                        virtual = virtual.toMutableList().apply { set(vIndex, resolved) }
                    }
                    rebuildWindow(vIndex, resolved, play = false, positionMs = snapshot.positionMs)
                }
            }
        }
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

        /** 淡入/淡出：12 步 × 20ms ≈ 240ms。 */
        private const val FADE_STEPS = 12
        private const val FADE_STEP_MS = 20L

        /**
         * 后面常备几首已签好的：正常播放永远不断档。
         * 3 首 ≈ 十几分钟，够用又不至于一次性打太多接口。
         */
        private const val AHEAD = 3

        /** 前面备 1 首：按「上一首」是一点就响；再往前就现签。 */
        private const val BEHIND = 1

        /** 直链缓存多少首（LRU）：来回切同几首不再重签。 */
        private const val RESOLVE_CACHE_SIZE = 24

        /** 直链缓存有效期：太早签的地址可能已经过期。 */
        private const val RESOLVE_TTL_MS = 10 * 60 * 1000L

        /** 签失败之后的冷却：这段时间不再试同一首。 */
        private const val FAIL_TTL_MS = 60 * 1000L
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
