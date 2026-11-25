package com.am.music.playback

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import com.am.music.MainActivity
import com.am.music.R

/**
 * 媒体通知的「两种形态」，由设置里的「系统媒体岛」开关决定。
 *
 * 为什么需要这个：
 * 各家 ROM 的媒体岛（小米超级岛 / 各家灵动岛）判定的不是「有个通知」，而是
 * **MediaStyle 常驻通知 + MediaSession token** 这一组合。Media3 的默认通知正好
 * 两样都占，所以只要用默认实现，岛就会冒出来，应用侧没有开关可关。
 *
 * 这里做的替换：
 * - 跟随系统（默认）：直接返回 [DefaultMediaNotificationProvider] 的结果，行为与之前一致。
 * - 关闭：自己画一条**普通前台通知**——不 setStyle(MediaStyle)、不带 sessionToken。
 *   系统没有可识别的媒体通知，岛不会被触发；而 MediaSession 本身还在，
 *   所以蓝牙/耳机按键、锁屏播放卡片、Android Auto 都照旧。
 *
 * 开关每次重画通知时读一次，切换后服务会主动刷一次通知，不用重启 App。
 */
@OptIn(UnstableApi::class)
class SwitchableMediaNotificationProvider(
    private val context: Context,
    private val hideSystemIsland: () -> Boolean,
) : MediaNotification.Provider {

    private val systemDefault by lazy {
        DefaultMediaNotificationProvider.Builder(context).build()
    }

    override fun createNotification(
        session: MediaSession,
        customLayout: com.google.common.collect.ImmutableList<CommandButton>,
        actionFactory: MediaNotification.ActionFactory,
        onNotificationChangedCallback: MediaNotification.Provider.Callback,
    ): MediaNotification {
        val inner = systemDefault.createNotification(
            session, customLayout, actionFactory, onNotificationChangedCallback,
        )
        if (!hideSystemIsland()) return inner
        // 自绘失败宁可退回系统默认（出岛）也不要没通知：前台服务没有通知会被系统干掉
        return runCatching {
            MediaNotification(
                inner.notificationId,
                plainNotification(session, actionFactory, inner.notification),
            )
        }.getOrDefault(inner)
    }

    override fun handleCustomCommand(session: MediaSession, commandName: String, args: android.os.Bundle): Boolean =
        systemDefault.handleCustomCommand(session, commandName, args)

    /** 不带 MediaStyle 的普通通知：标题=歌名，副标题=歌手，三个播放控制按钮。 */
    private fun plainNotification(
        session: MediaSession,
        actionFactory: MediaNotification.ActionFactory,
        template: Notification,
    ): Notification {
        val player = session.player
        val metadata = player.mediaMetadata
        val title = metadata.title?.toString()?.takeIf { it.isNotBlank() } ?: "正在播放"
        val subtitle = metadata.artist?.toString()?.takeIf { it.isNotBlank() }

        // 复用系统默认那条的频道，用户的「通知渠道」设置不会被重置
        val channelId = template.channelId ?: DEFAULT_CHANNEL_ID

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notif_small)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(
                mediaAction(actionFactory, session, R.drawable.ic_notif_prev, "上一首", Player.COMMAND_SEEK_TO_PREVIOUS),
            )
            .addAction(
                if (player.isPlaying) {
                    mediaAction(actionFactory, session, R.drawable.ic_notif_pause, "暂停", Player.COMMAND_PLAY_PAUSE)
                } else {
                    mediaAction(actionFactory, session, R.drawable.ic_notif_play, "播放", Player.COMMAND_PLAY_PAUSE)
                },
            )
            .addAction(
                mediaAction(actionFactory, session, R.drawable.ic_notif_next, "下一首", Player.COMMAND_SEEK_TO_NEXT),
            )

        return builder.build()
    }

    private fun mediaAction(
        actionFactory: MediaNotification.ActionFactory,
        session: MediaSession,
        iconRes: Int,
        label: String,
        command: Int,
    ): NotificationCompat.Action = NotificationCompat.Action.Builder(
        iconRes,
        label,
        actionFactory.createMediaActionPendingIntent(session, command.toLong()),
    ).build()

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private companion object {
        const val DEFAULT_CHANNEL_ID = "default_channel_id"
    }
}
