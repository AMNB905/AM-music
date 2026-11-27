package com.am.music

import android.app.Application
import android.content.Context
import android.net.Uri
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import com.am.music.data.announce.AnnouncementRepository
import com.am.music.data.cache.AppCacheRepository
import com.am.music.data.cache.FeedCache
import com.am.music.data.cover.CoverCache
import com.am.music.data.db.AppDatabase
import com.am.music.data.favorites.FavoritesRepository
import com.am.music.data.local.LocalMusicRepository
import com.am.music.data.lyrics.LrcParser
import com.am.music.data.lyrics.LyricAligner
import com.am.music.data.lyrics.LyricsRepository
import com.am.music.data.model.Song
import com.am.music.data.download.DownloadsRepository
import com.am.music.data.online.MusicApi
import com.am.music.data.online.OnlineMusicRepository
import com.am.music.data.online.RemoteOnlineMusicRepository
import com.am.music.data.playlist.PlaylistRepository
import com.am.music.data.recent.RecentlyPlayedRepository
import com.am.music.data.search.SearchHistoryRepository
import com.am.music.data.security.SecuritySession
import com.am.music.data.settings.SettingsRepository
import com.am.music.data.update.UpdateChecker
import com.am.music.playback.PlaybackManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class AppContainer(context: Context, imageLoader: ImageLoader) {

    /** 给需要落盘的界面逻辑用（歌单封面拷进私有目录），别让各 ViewModel 各自去拿 context。 */
    val appContext: Context = context.applicationContext

    init {
        // 运行环境体检要读自己的包签名，先把 context 交出去
        com.am.music.data.security.Integrity.appContext = context.applicationContext
    }
    private val database = AppDatabase.create(context)
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val localMusicRepository = LocalMusicRepository(context)
    val settingsRepository = SettingsRepository(context)
    val securitySession = SecuritySession(context, MusicApi.BASE_URL)
    val announcementRepository = AnnouncementRepository(context)

    /** 云更新：启动拉一次 version.json，决定要不要弹、能不能跳过。 */
    val updateChecker = UpdateChecker(context)

    /** 榜单 / 每日推荐 / 推荐歌单的磁盘快照，口径是「每个进程启动只真正拉一次」。 */
    val feedCache = FeedCache(context)

    /** 下载：文件落 filesDir，和播放缓存互不影响。 */
    val downloadsRepository = DownloadsRepository(context, database.downloadsDao())

    val onlineMusicRepository: OnlineMusicRepository =
        RemoteOnlineMusicRepository(
            settings = settingsRepository,
            api = MusicApi(security = securitySession, feedCache = feedCache),
            context = context.applicationContext,
            downloads = downloadsRepository,
        )
    val lyricsRepository = LyricsRepository(context)

    /** 封面落盘（filesDir，不随系统清缓存消失）：在线看一次，离线照样有图。 */
    val coverCache = CoverCache(context)
    val lyricAligner = LyricAligner(onlineMusicRepository)
    val favoritesRepository = FavoritesRepository(database.favoritesDao())
    val playlistRepository = PlaylistRepository(database.playlistDao())
    val recentlyPlayedRepository = RecentlyPlayedRepository(database.recentSongsDao())
    val searchHistoryRepository = SearchHistoryRepository(context)
    val playbackManager = PlaybackManager(context, settingsRepository)

    /** 定时关闭：到点让播放器走同一条 240ms 渐隐暂停曲线。 */
    val sleepTimer = com.am.music.playback.SleepTimer(
        onExpire = { playbackManager.pauseForSleepTimer() },
    )
    val cacheRepository = AppCacheRepository(lyricsRepository, imageLoader, feedCache, coverCache)

    init {
        // 播放中断时的自救通道：缓存残片 / 直链过期 / 断网，统一重新签一条直链续播
        playbackManager.networkResolver = { song ->
            onlineMusicRepository.freshStreamUrl(song)
        }

        // 队列补料：离队尾只剩几首时，从「当前播放上下文」里按序签下几首追加进队列。
        // 700 首的歌单不会一次性解析，也不会因为用户切歌就断掉补给。
        playbackManager.queueResolver = { song ->
            when {
                song.uri != Uri.EMPTY -> song
                song.isOnline -> onlineMusicRepository.resolve(song)
                else -> null
            }
        }

        // 下载也要一条「当下有效」的直链：下载可能几分钟后才开始下一档，
        // 复用播放直链缓存容易撞上过期地址。下档位不同，键也不同。
        downloadsRepository.urlProvider = { song, quality ->
            onlineMusicRepository.freshStreamUrl(song, quality)
        }
        // 兜底：这一档拿不到地址时，退回播放链路那条（带重签与降档）的解析
        downloadsRepository.resolver = { song ->
            onlineMusicRepository.resolve(song)
        }

        // 下载完成顺手把封面落本地（离线打开本地页也有图）
        downloadsRepository.coverPrefetch = { url -> coverCache.ensure(url) }

        // 上次运行留下的失效记录先清掉，本地页不该出现点了没反应的幽灵行
        appScope.launch { runCatching { downloadsRepository.prune() } }

        // 音频缓存从 cacheDir 搬到 filesDir 之后，旧目录里那份既读不到也占着空间，一次性收掉。
        // 只在启动时做一次：SimpleCache 还没碰过旧目录，删它是安全的。
        appScope.launch {
            runCatching {
                val legacy = java.io.File(context.applicationContext.cacheDir, "audio_cache")
                if (legacy.exists()) legacy.deleteRecursively()
            }
        }

        // 启动预热：榜单 / 每日推荐在后台各拉一次并落盘，之后各页直接读缓存。
        appScope.launch { runCatching { onlineMusicRepository.prewarm() } }

        // 播放链路不关心「是谁点的歌」，这里统一记账：当前曲目一变就写进最近播放
        // （本地/在线、来自搜索/歌单/收藏/识曲都覆盖），同时**预热封面与歌词**：
        // 在线听一次就把图和词落到本地，之后断网重开也照样有封面、有歌词。
        appScope.launch {
            playbackManager.state
                .map { it.currentSong?.key }
                .distinctUntilChanged()
                .collect { key ->
                    val song = playbackManager.state.value.currentSong ?: return@collect
                    if (song.key != key) return@collect
                    runCatching { recentlyPlayedRepository.record(song) }
                    runCatching { coverCache.ensure(song.coverUrl ?: song.albumArtUri?.toString()) }
                    runCatching { prewarmLyrics(song) }
                }
        }
    }

    /**
     * 歌词预热：磁盘里没有就先拉一份写进去（对齐交给播放页，第一次进歌词页会顺手做）。
     *
     * 为什么放在「切歌」这个时机：用户点开歌词页才落盘太晚——很多人是先听、断网之后
     * 才想翻歌词，那时已经没有网络可拉了。这里只要播过一次就有词。
     */
    private suspend fun prewarmLyrics(song: Song) {
        if (!song.isOnline) return
        if (lyricsRepository.readCache(song.key) != null) return
        val raw = runCatching { onlineMusicRepository.lyrics(song) }.getOrNull() ?: return
        val lines = runCatching { LrcParser.parse(raw) }.getOrNull() ?: return
        if (lines.isEmpty()) return
        lyricsRepository.writeCache(song.key, lines)
    }
}

class AMMusicApp : Application(), ImageLoaderFactory {

    lateinit var container: AppContainer
        private set

    /**
     * 封面统一 220ms 淡入，避免图片「啪」一下出现，列表滚动时观感也更稳。
     *
     * 磁盘缓存 512 MB：歌单网格和播放页封面第二次打开直接读本地。
     * **不要再开 `respectCacheHeaders(false)`**——0.6.18 之前就是它让封面一张都不出来
     * （详见 `AMMusic-回滚恢复说明.md` 第二十二节）。
     *
     * 实例在这里一次性建好并交给 [AppCacheRepository]：设置页清理封面缓存时要同时清
     * 内存与磁盘，拿不到同一个实例就清不掉。`filesDir/covers` 那层另算，由 [CoverCache] 管。
     */
    override fun newImageLoader(): ImageLoader = imageLoader

    private val imageDiskCache: DiskCache by lazy {
        DiskCache.Builder()
            .directory(cacheDir.resolve("image_cache"))
            .maxSizeBytes(IMAGE_CACHE_BYTES)
            .build()
    }

    private val imageLoader: ImageLoader by lazy {
        ImageLoader.Builder(this)
            .crossfade(220)
            // 以前这里开过 respectCacheHeaders(false)：图床爱回 no-store，尊重它就等于每次重下。
            // 但那会把请求退化成「只读缓存」，封面反而经常一张都不出来。
            // 现在封面自己落盘（CoverCache）兜住了这个问题，这里回到默认行为。
            .diskCache(imageDiskCache)
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        // 崩溃落盘：装得越早越好，AppContainer 构造里出的问题也要能记下来
        com.am.music.util.CrashLog.install(this)
        // 提前触发一次，保证 container 建好时封面缓存已经就位
        imageLoader
        container = AppContainer(this, imageLoader)
    }

    private companion object {
        /** 封面磁盘缓存上限：512 MB，按 LRU 淘汰。 */
        const val IMAGE_CACHE_BYTES = 512L * 1024 * 1024
    }
}
