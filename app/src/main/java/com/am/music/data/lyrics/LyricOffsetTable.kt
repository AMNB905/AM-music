package com.am.music.data.lyrics

/**
 * 逐曲离线校准表。
 *
 * 少数歌曲的歌词与后端返回的音频根本不是同一条录制（旧版 / 重制 / 剪辑），
 * 这类偏移只能靠「听」来定——把音频解码出来做人声起点的能量对齐，
 * 算出的偏移量写在这里，App 端零成本、零风险地直接套用。
 *
 * key 为 [com.am.music.data.model.Song.key]（在线曲目是「平台:id」），值为时间轴整体平移的毫秒数：
 * 正数表示歌词晚一点出现，负数表示提前。
 */
object LyricOffsetTable {

    private val offsets: Map<String, Long> = emptyMap()

    fun offsetFor(songKey: String): Long? = offsets[songKey]
}
