package com.am.music.data.lyrics

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import com.am.music.data.model.LyricLine
import com.am.music.data.model.Song
import com.am.music.data.model.SongSource
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LyricsRepository(private val context: Context) {

    private val prefs = context.getSharedPreferences("lyrics_mapping", Context.MODE_PRIVATE)

    suspend fun load(song: Song): List<LyricLine>? = withContext(Dispatchers.IO) {
        readImported(song.id)?.let { return@withContext it }
        if (song.source == SongSource.LOCAL) {
            readAdjacentLrc(song)?.let { return@withContext it }
        }
        null
    }

    fun saveImported(songId: String, uri: Uri) {
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        prefs.edit().putString(songId, uri.toString()).apply()
    }

    /** 每首歌的歌词校准偏移（毫秒），长按歌词一键对齐时写入。 */
    suspend fun calibration(songId: String): Long = withContext(Dispatchers.IO) {
        prefs.getLong(KEY_CALIB + songId, 0L)
    }

    suspend fun setCalibration(songId: String, offsetMs: Long) {
        withContext(Dispatchers.IO) {
            prefs.edit().putLong(KEY_CALIB + songId, offsetMs).apply()
        }
    }

    fun hasCalibration(songId: String): Boolean = prefs.contains(KEY_CALIB + songId)

    fun clearCalibrations() {
        val edit = prefs.edit()
        prefs.all.keys.filter { it.startsWith(KEY_CALIB) }.forEach(edit::remove)
        edit.apply()
    }

    /**
     * 歌词落盘缓存：`filesDir/lyrics/<sha1(songKey)>.lrc`。
     *
     * 之前每次进歌词页都要「重新拉一遍 + 重新对齐」，慢的原因是这些结果只活在内存里。
     * 现在把**在线拿到的和对齐过的**歌词都写成 LRC，第二次打开是纯本地读取（零网络）。
     * 写盘走 `.tmp` 再 rename，避免退到后台/进程被杀时留半个文件。
     */
    suspend fun readCache(songKey: String): List<LyricLine>? = withContext(Dispatchers.IO) {
        val file = cacheFile(songKey)
        if (!file.exists()) return@withContext null
        runCatching {
            LrcParser.parse(String(file.readBytes(), Charsets.UTF_8)).takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    suspend fun writeCache(songKey: String, lines: List<LyricLine>) = withContext(Dispatchers.IO) {
        if (lines.isEmpty()) return@withContext
        runCatching {
            val file = cacheFile(songKey)
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeBytes(LrcParser.serialize(lines).toByteArray(Charsets.UTF_8))
            if (file.exists()) file.delete()
            if (!tmp.renameTo(file)) {
                file.writeBytes(tmp.readBytes())
                tmp.delete()
            }
        }
    }

    /** 这份缓存是否已经做过本地对齐（对齐过就不用每次再跑一遍算法）。 */
    fun isAligned(songKey: String): Boolean = prefs.getBoolean(KEY_ALIGNED + fileName(songKey), false)

    fun markAligned(songKey: String) {
        prefs.edit().putBoolean(KEY_ALIGNED + fileName(songKey), true).apply()
    }

    /** 是否已经尝试过「跨音源补逐字」——没补到也记一笔，免得每次进歌词页都重搜一遍。 */
    fun isWordsChecked(songKey: String): Boolean =
        prefs.getBoolean(KEY_WORDS + fileName(songKey), false)

    fun markWordsChecked(songKey: String) {
        prefs.edit().putBoolean(KEY_WORDS + fileName(songKey), true).apply()
    }

    suspend fun cacheBytes(): Long = withContext(Dispatchers.IO) {
        val dir = cacheDir()
        if (!dir.exists()) return@withContext 0L
        runCatching { dir.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L }.getOrDefault(0L)
    }

    suspend fun clearCache() = withContext(Dispatchers.IO) {
        runCatching {
            cacheDir().listFiles()?.forEach { it.delete() }
            val edit = prefs.edit()
            prefs.all.keys.filter { it.startsWith(KEY_ALIGNED) }.forEach(edit::remove)
            prefs.all.keys.filter { it.startsWith(KEY_WORDS) }.forEach(edit::remove)
            edit.apply()
        }
        Unit
    }

    private fun cacheDir(): File = File(context.filesDir, "lyrics")

    private fun fileName(songKey: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(songKey.toByteArray(Charsets.UTF_8))
        val hex = buildString { digest.forEach { append(String.format("%02x", it.toInt() and 0xFF)) } }
        return hex + ".lrc"
    }

    private fun cacheFile(songKey: String): File {
        val dir = cacheDir()
        if (!dir.exists()) dir.mkdirs()
        return File(dir, fileName(songKey))
    }

    private fun readImported(songId: String): List<LyricLine>? {
        val uriString = prefs.getString(songId, null) ?: return null
        return runCatching {
            context.contentResolver.openInputStream(Uri.parse(uriString))?.use { parseStream(it) }
        }.getOrNull()
    }

    private fun readAdjacentLrc(song: Song): List<LyricLine>? {
        val path = song.localPath ?: return null
        val baseName = path.substringBeforeLast('.')
        val lrcName = baseName.substringAfterLast(File.separatorChar) + ".lrc"

        for (candidate in listOf("$baseName.lrc", "$baseName.LRC")) {
            val file = File(candidate)
            val parsed = runCatching {
                if (file.canRead()) file.inputStream().use { parseStream(it) } else null
            }.getOrNull()
            if (parsed != null) return parsed
        }

        return runCatching {
            context.contentResolver.query(
                MediaStore.Files.getContentUri("external"),
                arrayOf(MediaStore.Files.FileColumns._ID),
                "${MediaStore.Files.FileColumns.DISPLAY_NAME} = ?",
                arrayOf(lrcName),
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val uri = ContentUris.withAppendedId(
                    MediaStore.Files.getContentUri("external"),
                    cursor.getLong(0),
                )
                context.contentResolver.openInputStream(uri)?.use { parseStream(it) }
            }
        }.getOrNull()
    }

    private fun parseStream(stream: InputStream): List<LyricLine>? {
        val bytes = stream.readBytes()
        val utf8 = String(bytes, Charsets.UTF_8)
        val content = if (utf8.contains('\uFFFD')) String(bytes, charset("GBK")) else utf8
        return LrcParser.parse(content).takeIf { it.isNotEmpty() }
    }

    private companion object {
        const val KEY_CALIB = "calib_"
        const val KEY_ALIGNED = "aligned_"
        const val KEY_WORDS = "words_"
    }
}