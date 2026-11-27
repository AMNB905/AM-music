package com.am.music.data.online

import com.am.music.data.model.OnlinePlatform
import com.am.music.data.model.Song
import com.am.music.data.model.SongFacts
import com.am.music.data.model.SongSource
import org.json.JSONArray
import org.json.JSONObject

/**
 * 接口 JSON → 领域对象的全部解析逻辑。
 *
 * 从 `MusicApi.kt` 里拆出来（原来那个文件 750 行，请求、签名、缓存、解析挤在一起，
 * 改任何一处都得先在几百行里找位置）。这里只做纯函数：给一段 JSON，回一个对象，
 * 不碰网络、不碰磁盘，所以也能被缓存快照那条路径直接复用。
 */
internal object MusicParsing {

    private val TIME_TAG = Regex("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")
    private val QRC_TUPLE = Regex("\\(\\d+,\\d+\\)")


    fun qqDaily(body: String): List<Song> = runCatching {
        val songs = JSONObject(body).optJSONObject("data")?.optJSONArray("songs") ?: JSONArray()
        (0 until songs.length()).mapNotNull { index ->
            val obj = songs.optJSONObject(index) ?: return@mapNotNull null
            val mid = obj.optString("mid").ifBlank { obj.optString("songmid") }
            if (mid.isBlank()) return@mapNotNull null
            val albumMid = obj.optString("albummid")
            val cover = albumMid.takeIf { it.isNotBlank() }
                ?.let { "https://y.qq.com/music/photo_new/T002R500x500M000$it.jpg" }
            Song(
                id = mid,
                title = obj.optString("name").ifBlank { "未命名歌曲" },
                artist = obj.optString("singer").ifBlank {
                    buildList {
                        val singers = obj.optJSONArray("singers") ?: JSONArray()
                        for (i in 0 until singers.length()) {
                            singers.optJSONObject(i)?.optString("name")
                                ?.takeIf { name -> name.isNotBlank() }?.let { add(it) }
                        }
                    }.joinToString(" / ").ifBlank { "未知艺术家" }
                },
                album = obj.optString("album"),
                durationMs = obj.optLong("interval", 0L) * 1000L,
                uri = android.net.Uri.EMPTY,
                albumArtUri = cover?.let { android.net.Uri.parse(it) },
                source = SongSource.ONLINE,
                platform = OnlinePlatform.QQ,
                coverUrl = cover,
                sourceId = obj.optString("id").takeIf { it.isNotBlank() },
            )
        }
    }.getOrDefault(emptyList())


    fun similarSong(obj: JSONObject?, platform: OnlinePlatform): Song? {
        if (obj == null) return null
        val id = when (platform) {
            OnlinePlatform.NETEASE -> obj.optString("id")
            OnlinePlatform.QQ -> obj.optString("mid").ifBlank { obj.optString("songmid") }
        }
        if (id.isBlank()) return null
        val qqAlbumMid = obj.optString("albummid")
        val cover = when (platform) {
            OnlinePlatform.NETEASE -> obj.optString("coverImgUrl")
            OnlinePlatform.QQ -> if (qqAlbumMid.isBlank()) "" else
                "https://y.qq.com/music/photo_new/T002R500x500M000$qqAlbumMid.jpg"
        }
        val durationMs = when (platform) {
            // 网易给的是毫秒（duration），QQ 给的是秒（interval）
            OnlinePlatform.NETEASE -> obj.optLong("duration", 0L)
            OnlinePlatform.QQ -> obj.optLong("interval", 0L) * 1000L
        }
        return Song(
            id = id,
            title = obj.optString("name").ifBlank { "未命名歌曲" },
            artist = when (platform) {
                OnlinePlatform.NETEASE -> obj.optString("artistNames")
                OnlinePlatform.QQ -> obj.optString("singer")
            }.ifBlank { "未知艺术家" },
            album = obj.optString("album"),
            durationMs = durationMs,
            uri = android.net.Uri.EMPTY,
            albumArtUri = url(cover)?.let { android.net.Uri.parse(it) },
            source = SongSource.ONLINE,
            platform = platform,
            coverUrl = url(cover),
            sourceId = if (platform == OnlinePlatform.QQ) {
                obj.optString("id").takeIf { it.isNotBlank() }
            } else {
                null
            },
        )
    }

    fun qqFacts(data: JSONObject, song: Song): SongFacts {
        val rows = buildList {
            data.optString("public_time").takeIf { it.isNotBlank() }?.let { add("发行时间" to it) }
            data.optString("genre").takeIf { it.isNotBlank() }?.let { add("曲风" to it) }
            data.optString("language").takeIf { it.isNotBlank() }?.let { add("语种" to it) }
            data.optString("company").takeIf { it.isNotBlank() }?.let { add("发行公司" to it) }
            data.optString("subtitle").takeIf { it.isNotBlank() }?.let { add("副标题" to it) }
        }
        return SongFacts(
            title = data.optString("name").ifBlank { song.title },
            rows = rows,
            desc = data.optString("desc").takeIf { it.isNotBlank() },
        )
    }

    fun neteaseFacts(data: JSONObject, song: Song): SongFacts {
        val base = data.optJSONArray("base") ?: JSONArray()
        val rows = buildList {
            for (index in 0 until base.length()) {
                val item = base.optJSONObject(index) ?: continue
                val title = item.optString("title")
                val text = item.optString("text")
                if (title.isNotBlank() && text.isNotBlank()) add(title to text)
            }
            // 制作名单：按「角色：人名/人名」压成一行，避免一长串小行把面板撑满
            val credits = data.optJSONArray("credits") ?: JSONArray()
            for (index in 0 until credits.length()) {
                val item = credits.optJSONObject(index) ?: continue
                val role = item.optString("role")
                val persons = item.optJSONArray("persons") ?: JSONArray()
                val names = buildList {
                    for (p in 0 until persons.length()) {
                        persons.optJSONObject(p)?.optString("name")?.takeIf { it.isNotBlank() }?.let { add(it) }
                    }
                }
                if (role.isNotBlank() && names.isNotEmpty()) add(role to names.joinToString(" / "))
            }
        }
        return SongFacts(
            title = data.optJSONObject("song")?.optString("songName")?.takeIf { it.isNotBlank() }
                ?: song.title,
            rows = rows,
            desc = null,
        )
    }

    fun song(obj: JSONObject?, platform: OnlinePlatform): Song? {
        obj ?: return null
        val id = obj.optString("id").ifBlank {
            obj.optJSONObject("extra")?.optString("songmid").orEmpty()
        }
        if (id.isBlank()) return null

        val title = obj.optString("name").ifBlank { obj.optString("title") }
        val artist = obj.optString("artist").ifBlank { obj.optString("singer") }
        val album = obj.optString("album")
        val durationMs = obj.optLong("durationMs", 0L).takeIf { it > 0 } ?: (obj.optLong("duration", 0L) * 1000L)
        val cover = obj.optString("cover").ifBlank { obj.optString("coverUrl") }
            .ifBlank { obj.optString("cover_small") }

        return Song(
            id = id,
            title = title.ifBlank { "未命名歌曲" },
            artist = artist.ifBlank { "未知艺术家" },
            album = album,
            durationMs = durationMs,
            uri = android.net.Uri.EMPTY,
            albumArtUri = url(cover)?.let { android.net.Uri.parse(it) },
            source = SongSource.ONLINE,
            platform = platform,
            coverUrl = url(cover),
            sourceId = if (platform == OnlinePlatform.QQ) {
                obj.optJSONObject("extra")?.optString("song_id")?.takeIf { it.isNotBlank() }
                    ?: obj.optString("song_id").takeIf { it.isNotBlank() }
            } else {
                null
            },
        )
    }

    fun playlist(obj: JSONObject?, platform: OnlinePlatform): OnlinePlaylist? {
        obj ?: return null
        val id = obj.optString("id")
        if (id.isBlank()) return null
        val cover = obj.optString("cover").ifBlank { obj.optString("picUrl") }
        return OnlinePlaylist(
            id = id,
            name = obj.optString("name").ifBlank { "未命名歌单" },
            coverUrl = url(cover),
            playCount = obj.optLong("play_count", 0L).takeIf { it > 0 }
                ?: obj.optLong("playCount", 0L),
            trackCount = obj.optInt("track_count", 0).takeIf { it > 0 }
                ?: obj.optInt("trackCount", 0),
            creator = obj.optString("creator"),
            platform = platform,
        )
    }

    fun url(value: String): String? = when {
        value.startsWith("https://") -> value
        value.startsWith("http://") -> "https://" + value.removePrefix("http://")
        else -> null
    }

    /**
     * 歌单元信息（名称 / 作者 / 封面 / 曲目数）。
     *
     * 两个平台的壳不一样：
     * - 网易云 `wy/api/playlist`：`data{name,cover,creator,track_count}`
     * - QQ `qq/api/playlist/detail`：`data{meta{name,cover,creator,track_count}, songs[]}`
     *
     * 取不到名字不算失败——导入照样能完成，界面用「导入的歌单」兜底。
     */
    fun playlistMeta(body: String, platform: OnlinePlatform): PlaylistMeta? = runCatching {
        val root = JSONObject(body).optJSONObject("data") ?: return@runCatching null
        val source = if (platform == OnlinePlatform.QQ) {
            root.optJSONObject("meta") ?: root
        } else {
            root
        }
        PlaylistMeta(
            name = source.optString("name").takeIf { it.isNotBlank() },
            creator = source.optString("creator").takeIf { it.isNotBlank() },
            coverUrl = url(
                source.optString("cover").ifBlank { source.optString("coverImgUrl") }
            ),
            trackCount = source.optInt("track_count", 0).takeIf { it > 0 }
                ?: source.optInt("trackCount", 0),
        )
    }.getOrNull()

    fun lrc(raw: String): String {
        if (raw.isBlank()) return ""
        return raw.lineSequence().mapNotNull { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@mapNotNull null
            val tags = TIME_TAG.findAll(trimmed).toList()
            when {
                tags.isEmpty() -> trimmed
                tags.size == 1 -> trimmed
                // 逐字格式（`[00:00.00]晴[00:00.16]天`）：原样保留，交给 LrcParser 解出 words。
                // 之前的写法会把后面的时间标签全删掉，逐字信息在这里就被抹平了。
                isWordTimed(trimmed, tags) -> trimmed
                else -> {
                    val text = TIME_TAG.replace(trimmed, "").replace(QRC_TUPLE, "").trim()
                    if (text.isEmpty()) null else tags.first().value + text
                }
            }
        }.joinToString("\n")
    }

    /** 最后一个时间标签之前是否已经出现文字（区分逐字行与「一句挂多个时间」）。 */
    private fun isWordTimed(line: String, tags: List<MatchResult>): Boolean {
        if (tags.size < 2) return false
        for (i in 0 until tags.size - 1) {
            val from = tags[i].range.last + 1
            val to = tags[i + 1].range.first
            if (from < to && line.substring(from, to).isNotBlank()) return true
        }
        return false
    }
}
