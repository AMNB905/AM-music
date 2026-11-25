package com.am.music.data.model

/**
 * 一条视频分享链接解析出来的东西。
 *
 * 服务端（`sv/index.php`）只负责"把链接变成这些字段"，不做转码 ——
 * 那台机器没有 ffmpeg，抽音轨交给端上的 MediaExtractor/MediaCodec（见 ClipAudio）。
 */
data class VideoClip(
    val platform: String,
    val title: String,
    val author: String,
    val coverUrl: String?,
    val durationSec: Double,
    val musicTitle: String,
    val musicAuthor: String,
    /** 音轨直链；抖音/快手是 BGM 原声或混流 MP4，B站是 DASH 独立音轨 */
    val audioUrl: String,
    /** `audio/192K`、`music/bgm`、`mix/mp4` 这类，用来在界面上说清"抽到的是什么" */
    val audioKind: String,
    /** B站音轨必须带 Referer，否则 403 */
    val referer: String,
    /**
     * 解码失败时的第二选择（B站 DASH 音轨是 fMP4 分片，端上不一定吃得下；
     * 退到 `durl` 混流 MP4 一定有音轨）。没有则为空串。
     */
    val fallbackUrl: String = "",
    val fallbackKind: String = "",
    val fallbackReferer: String = "",
) {
    val platformName: String
        get() = when (platform) {
            "douyin" -> "抖音"
            "kuaishou" -> "快手"
            "bilibili" -> "B 站"
            else -> platform.ifBlank { "视频" }
        }

    /** 结果页的副标题：平台 + 视频标题 + 作者 */
    val sourceLine: String
        get() = listOf(platformName, title, author)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
}

/** `parseClip` 的结果：成功给 clip，失败给一句能读懂的 message。 */
data class ClipParse(val clip: VideoClip?, val message: String?)

/** 链接识别的四个阶段，界面据此给等待提示（不能干转圈）。 */
enum class ClipStage { PARSING, DOWNLOADING, DECODING, UPLOADING }

/** 一步到位的结果：命中歌曲 / 命中了但没识别出来 / 中间哪一步失败。 */
data class ClipRecognize(
    val song: Song?,
    val clip: VideoClip?,
    val message: String?,
    /** 长视频分段识别出来的全部命中；短视频只有一条（就等于 [song]）。 */
    val hits: List<ClipHit> = emptyList(),
)

/**
 * 分段识别的一条命中：[startSec] 是它在视频里的位置（秒）。
 *
 * 一条 4 分半的合辑视频里可能连着好几首，用户需要知道「第几分几秒是这首」，
 * 所以位置必须带回去，不能只给一串歌名。
 */
data class ClipHit(val song: Song, val startSec: Int)

/**
 * 识曲结果的「能不能放」判定。
 *
 * QQ 识曲返回的曲目经常因为没有版权而拿不到直链；这时按同名同作者去网易云找一条能放的。
 * [song] 为空表示两边都没有可播放的版本（界面上仍保留识别到的信息，只是不能播）。
 */
data class PlayableVariant(
    val song: Song?,
    val switched: Boolean,
    val message: String?,
)
