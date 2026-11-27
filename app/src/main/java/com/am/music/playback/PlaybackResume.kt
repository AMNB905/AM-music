package com.am.music.playback

import android.content.Context
import android.net.Uri
import com.am.music.data.model.OnlinePlatform
import com.am.music.data.model.Song
import com.am.music.data.model.SongSource
import org.json.JSONArray
import org.json.JSONObject

/**
 * 「上次听到哪」的落盘。
 *
 * 进程被系统杀掉后播放器什么都不剩，下次打开只能从零开始。这里只存两样东西：
 * 最近一次的歌 + 队列 + 位置，启动时按原样摆回去，点播放就从上次那句接着放。
 *
 * 存 JSON 而不是序列化框架：Song 字段不多，用系统自带的 org.json 足够，
 * 也省掉给 model 加注解、给整个工程加插件的代价。
 */
object PlaybackResume {

    private const val PREFS = "playback_resume"
    private const val KEY_SONG = "song"
    private const val KEY_POSITION = "position"
    private const val KEY_QUEUE = "queue"
    private const val KEY_INDEX = "index"

    /** 队列最多存这么多首：太长的队列塞进 SharedPreferences 会拖慢启动。 */
    const val MAX_QUEUE = 120

    data class Snapshot(
        val queue: List<Song>,
        val index: Int,
        val positionMs: Long,
    )

    private var savedQueueHash: Int = 0

    fun save(context: Context, song: Song?, positionMs: Long, queue: List<Song>, index: Int) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (song == null) {
            prefs.edit().clear().apply()
            savedQueueHash = 0
            return
        }
        val trimmed = queue.take(MAX_QUEUE)
        val safeIndex = index.coerceIn(0, (trimmed.size - 1).coerceAtLeast(0))
        val hash = trimmed.hashCode()
        val editor = prefs.edit().putLong(KEY_POSITION, positionMs.coerceAtLeast(0L))
        if (hash != savedQueueHash || !prefs.contains(KEY_QUEUE)) {
            savedQueueHash = hash
            editor
                .putInt(KEY_INDEX, safeIndex)
                .putString(KEY_SONG, song.toJson().toString())
                .putString(KEY_QUEUE, JSONArray().apply {
                    trimmed.forEach { put(it.toJson()) }
                }.toString())
        }
        editor.apply()
    }

    fun load(context: Context): Snapshot? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val array = runCatching { JSONArray(prefs.getString(KEY_QUEUE, null) ?: return null) }.getOrNull()
            ?: return null
        if (array.length() == 0) return null
        val queue = buildList {
            for (i in 0 until array.length()) {
                runCatching { array.getJSONObject(i).toSong() }.getOrNull()?.let { add(it) }
            }
        }
        if (queue.isEmpty()) return null
        val index = prefs.getInt(KEY_INDEX, 0).coerceIn(0, queue.size - 1)
        return Snapshot(queue, index, prefs.getLong(KEY_POSITION, 0L))
    }

    private fun Song.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("artist", artist)
        put("album", album)
        put("durationMs", durationMs)
        put("uri", uri.toString())
        put("source", source.name)
        albumArtUri?.let { put("albumArtUri", it.toString()) }
        localPath?.let { put("localPath", it) }
        platform?.let { put("platform", it.key) }
        coverUrl?.let { put("coverUrl", it) }
        streamUrl?.let { put("streamUrl", it) }
        cachedQuality?.let { put("cachedQuality", it) }
        downloadedQuality?.let { put("downloadedQuality", it) }
        sourceId?.let { put("sourceId", it) }
    }

    private fun JSONObject.toSong(): Song = Song(
        id = getString("id"),
        title = getString("title"),
        artist = getString("artist"),
        album = optString("album", ""),
        durationMs = optLong("durationMs", 0L),
        uri = Uri.parse(optString("uri", "")),
        albumArtUri = optString("albumArtUri", null)?.let { Uri.parse(it) },
        source = SongSource.entries.firstOrNull { it.name == optString("source") } ?: SongSource.LOCAL,
        localPath = optString("localPath", null),
        platform = OnlinePlatform.fromKey(optString("platform", null)),
        coverUrl = optString("coverUrl", null),
        streamUrl = optString("streamUrl", null),
        cachedQuality = optString("cachedQuality", null),
        downloadedQuality = optString("downloadedQuality", null),
        sourceId = optString("sourceId", null),
    )
}
