package com.am.music.playback

import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.am.music.AMMusicApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** 音乐类音频属性；是否独占焦点由设置决定，见下方 [applyMixMode]。 */
    private val audioAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        .build()

    override fun onCreate() {
        super.onCreate()
        // 通知形态跟着「系统媒体岛」开关走：关闭时换成不带 MediaStyle 的普通通知，
        // 系统/ROM 就不会再顶出媒体岛（自己实现岛的前提）。必须在建 session 之前装上。
        val settings = (application as AMMusicApp).container.settingsRepository
        setMediaNotificationProvider(
            SwitchableMediaNotificationProvider(this) { settings.hideSystemMediaIsland.value }
        )

        // 音频链路：内存缓存 → SimpleCache 磁盘缓存 → 网络。
        // FLAG_IGNORE_CACHE_ON_ERROR 保证缓存文件损坏时不会一首歌彻底播不了，退化为直接回源。
        val upstream: DataSource.Factory = DefaultDataSource.Factory(this)
        val cacheFactory = CacheDataSource.Factory()
            .setCache(MediaCache.get(this))
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(cacheFactory))
            .setAudioAttributes(audioAttributes, handleAudioFocus(this))
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        session = MediaSession.Builder(this, player).build()

        // 设置里改「与其他应用同时播放」时，播放器已经在跑了，必须就地改焦点策略，
        // 不然要重启播放才生效（用户会以为开关坏了）。setAudioAttributes 只能在主线程调。
        scope.launch {
            (application as AMMusicApp).container.settingsRepository.mixWithOthers.collect { mix ->
                runCatching { player.setAudioAttributes(audioAttributes, !mix) }
            }
        }

        // 开关一改，立即按新形态重画一次通知（否则要等下一次播放/暂停才生效）
        scope.launch {
            settings.hideSystemMediaIsland.drop(1).collect {
                session?.let { s -> runCatching { onUpdateNotification(s) } }
            }
        }
    }

    /** 独占焦点 = 别人一出声本 App 让路；允许同时播放 = 不请求焦点，两路声音并存。 */
    private fun handleAudioFocus(context: android.content.Context): Boolean = runCatching {
        !(context.applicationContext as AMMusicApp).container.settingsRepository.mixWithOthersValue
    }.getOrDefault(true)

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}
