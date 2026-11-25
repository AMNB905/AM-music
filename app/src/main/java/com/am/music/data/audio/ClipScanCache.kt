package com.am.music.data.audio

import com.am.music.data.model.ClipHit
import com.am.music.data.model.ClipRecognize
import com.am.music.data.model.OnlinePlatform
import com.am.music.data.model.Song
import com.am.music.data.model.SongSource
import com.am.music.data.model.VideoClip
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * 链接识别结果缓存：同一条链接第二次进来直接给上次的结果，不再下载、不再上传。
 *
 * 为什么值得做：实测一次多曲扫描（10 个窗口）串行要 5~7 分钟，用户重刷同一条视频
 * 或者隔天又打开一次的概率不低。缓存命中后整条链路（解析/下载/解码/识别）全省。
 *
 * 只缓存**有命中**的结果：识别失败、音轨解不开这些不留档——下次多半网络或内容变了，
 * 用旧失败结论答复用户比重新跑一遍更糟。
 *
 * 目录 `filesDir/recognize_cache`，键是链接的 sha1；有效期 7 天，最多 200 条（按写入时间淘汰）。
 */
class ClipScanCache(private val dir: File) {

    fun read(link: String): ClipRecognize? {
        val file = fileOf(link)
        if (!file.exists()) return null
        val json = runCatching { JSONObject(file.readText()) }.getOrNull()
            ?: return null.also { file.delete() }
        if (System.currentTimeMillis() - json.optLong("savedAt") > TTL_MS) {
            file.delete()
            return null
        }
        val clip = readClip(json.optJSONObject("clip")) ?: return null
        val array = json.optJSONArray("hits") ?: return null
        val hits = (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val song = readSong(item.optJSONObject("song")) ?: return@mapNotNull null
            ClipHit(song, item.optInt("start"))
        }
        if (hits.isEmpty()) return null
        return ClipRecognize(
            song = hits.first().song,
            clip = clip,
            message = "上次已经识别过这条，直接给结果（要重新听一遍就再点一次识别）",
            hits = hits,
        )
    }

    fun write(link: String, clip: VideoClip?, hits: List<ClipHit>) {
        if (clip == null || hits.isEmpty()) return
        runCatching {
            if (!dir.exists()) dir.mkdirs()
            trim()
            val json = JSONObject().apply {
                put("v", 1)
                put("savedAt", System.currentTimeMillis())
                put("clip", writeClip(clip))
                put("hits", JSONArray().apply {
                    hits.forEach { hit ->
                        put(JSONObject().apply {
                            put("start", hit.startSec)
                            put("song", writeSong(hit.song))
                        })
                    }
                })
            }
            fileOf(link).writeText(json.toString())
        }
    }

    /** 超过 200 条就按修改时间删最旧的，避免长期占用空间。 */
    private fun trim() {
        val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
        if (files.size < MAX_FILES) return
        files.take(files.size - MAX_FILES + 1).forEach { it.delete() }
    }

    private fun fileOf(link: String): File =
        File(dir, sha1(link.trim().lowercase()) + ".json")

    private fun writeSong(song: Song): JSONObject = JSONObject().apply {
        put("id", song.id)
        put("title", song.title)
        put("artist", song.artist)
        put("album", song.album)
        put("durationMs", song.durationMs)
        put("cover", song.coverUrl ?: "")
        put("sourceId", song.sourceId ?: "")
        put("platform", song.platform?.key ?: "")
    }

    private fun readSong(json: JSONObject?): Song? {
        if (json == null) return null
        val id = json.optString("id").ifBlank { return null }
        return Song(
            id = id,
            title = json.optString("title").ifBlank { "未命名歌曲" },
            artist = json.optString("artist").ifBlank { "未知艺术家" },
            album = json.optString("album"),
            durationMs = json.optLong("durationMs", 0L),
            uri = android.net.Uri.EMPTY,
            source = SongSource.ONLINE,
            platform = when (json.optString("platform")) {
                OnlinePlatform.NETEASE.key -> OnlinePlatform.NETEASE
                else -> OnlinePlatform.QQ
            },
            coverUrl = json.optString("cover").ifBlank { null },
            sourceId = json.optString("sourceId").ifBlank { null },
        )
    }

    private fun writeClip(clip: VideoClip): JSONObject = JSONObject().apply {
        put("platform", clip.platform)
        put("title", clip.title)
        put("author", clip.author)
        put("cover", clip.coverUrl ?: "")
        put("durationSec", clip.durationSec)
        put("musicTitle", clip.musicTitle)
        put("musicAuthor", clip.musicAuthor)
        put("audioUrl", clip.audioUrl)
        put("audioKind", clip.audioKind)
        put("referer", clip.referer)
        put("fallbackUrl", clip.fallbackUrl)
        put("fallbackKind", clip.fallbackKind)
        put("fallbackReferer", clip.fallbackReferer)
    }

    private fun readClip(json: JSONObject?): VideoClip? {
        if (json == null) return null
        return VideoClip(
            platform = json.optString("platform"),
            title = json.optString("title"),
            author = json.optString("author"),
            coverUrl = json.optString("cover").ifBlank { null },
            durationSec = json.optDouble("durationSec", 0.0),
            musicTitle = json.optString("musicTitle"),
            musicAuthor = json.optString("musicAuthor"),
            audioUrl = json.optString("audioUrl"),
            audioKind = json.optString("audioKind"),
            referer = json.optString("referer"),
            fallbackUrl = json.optString("fallbackUrl"),
            fallbackKind = json.optString("fallbackKind"),
            fallbackReferer = json.optString("fallbackReferer"),
        )
    }

    private fun sha1(value: String): String =
        java.security.MessageDigest.getInstance("SHA-1")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val TTL_MS = 7L * 24 * 60 * 60 * 1000
        const val MAX_FILES = 200
    }
}
