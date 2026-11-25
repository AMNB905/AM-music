package com.am.music.data.download

import android.content.Context
import android.net.Uri
import android.os.StatFs
import com.am.music.data.db.DownloadEntity
import com.am.music.data.db.DownloadsDao
import com.am.music.data.model.OnlinePlatform
import com.am.music.data.model.Song
import com.am.music.data.model.SongSource
import com.am.music.playback.PlaybackNotice
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** 下载槽位：一档音质一个文件、一条记录，互不覆盖。 */
enum class DownloadStatus { RUNNING, DONE, FAILED }

data class DownloadTask(
    val songKey: String,
    val quality: String,
    val received: Long = 0L,
    val total: Long = 0L,
    val status: DownloadStatus = DownloadStatus.RUNNING,
    val error: String? = null,
) {
    val id: String get() = "$songKey@$quality"
    val fraction: Float
        get() = when {
            status == DownloadStatus.DONE -> 1f
            total > 0 -> (received.toFloat() / total).coerceIn(0f, 1f)
            else -> 0f
        }
}

/**
 * 用户主动的「下载」：把一整首歌真正落到 filesDir/downloads 下，和播放缓存（cacheDir + LRU）分开。
 *
 * 与播放缓存的关系（优先级从高到低）：
 * 1. **已下载文件**（这里）—— 离线首选，直链失效也照样能播；
 * 2. 完整播放缓存（[com.am.music.playback.MediaCache]）—— 边播边存，会被 1GB LRU 淘汰；
 * 3. 网络直链。
 *
 * 离线时要兜住的几件事：
 * - 下载中断：`.part` 文件留着，下次点同一档位**断点续传**（Range 从已有长度接着要）；
 * - 下完校验：比对总长度，短一截就当失败，绝不让半截文件冒充「已下载」；
 * - 空间不足：开始前先看可用空间，不够就直接说，不写到一半把手机撑爆；
 * - 文件被外因删掉：启动时 prune 掉指向不存在文件的记录，本地页不会出现「点了没反应」的幽灵行；
 * - 重复点：同档位正在下就忽略，已下完就直接提示，不重复写盘。
 */
class DownloadsRepository(
    context: Context,
    private val dao: DownloadsDao,
) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 直链出口，由 AppContainer 注入（下载器不该认识网络层细节）。 */
    var urlProvider: (suspend (Song, String) -> String?)? = null

    /**
     * 兜底直链出口：按播放链路那条已经验证过的 `resolve()` 走一遍。
     *
     * 为什么需要两条：`urlProvider` 直接问「这一档」要地址，音质不存在、或签名刚被服务端
     * 拒掉时它只会回 null；而 `resolve()` 内部有「缓存命中 → 重新激活重签 → 降档」的全套
     * 逻辑，是播放能出声的那条路。下载拿不到地址时退到这条，才有机会救回来。
     */
    var resolver: (suspend (Song) -> Song?)? = null

    /**
     * 封面预热出口，由 AppContainer 注入。
     * 下完一首就把封面存进 filesDir，之后离线打开本地页也有图，不用等联网。
     */
    var coverPrefetch: (suspend (String?) -> Unit)? = null

    /**
     * 最近一次失败的现场。下载失败在手机上只闪一条提示，用户回头说不清「卡在哪一步」，
     * 所以把阶段、HTTP 码、异常名都记下来，设置页可以直接看。
     */
    private val ledger = appContext.getSharedPreferences("am_download", Context.MODE_PRIVATE)

    private val _tasks = MutableStateFlow<Map<String, DownloadTask>>(emptyMap())
    val tasks: StateFlow<Map<String, DownloadTask>> = _tasks.asStateFlow()

    private val jobs = ConcurrentHashMap<String, Job>()

    /** 同时下两首就够了；再多只是互相抢带宽，进度条反而都不动。 */
    private val gate = Semaphore(2)

    fun observeAll() = dao.observeAll()

    private fun dir(): File = File(appContext.filesDir, "downloads").apply {
        mkdirs()
        // 别让系统音乐库再把我们下的文件扫一遍，否则本地页会出现两份
        val noMedia = File(this, ".nomedia")
        if (!noMedia.exists()) runCatching { noMedia.createNewFile() }
    }

    private fun slug(songKey: String, quality: String): String =
        "${songKey.replace(':', '_').replace('/', '_')}@$quality"

    private fun pathOf(fileName: String): String = File(dir(), fileName).absolutePath

    /** 该曲目已落地的档位（按音质从高到低）。 */
    suspend fun recordsOf(songKey: String): List<DownloadEntity> = withContext(Dispatchers.IO) {
        runCatching { dao.bySong(songKey) }
            .getOrDefault(emptyList())
            .filter { File(pathOf(it.fileName)).exists() }
            .sortedByDescending { rank(it.quality) }
    }

    /** 记录 → 可直接入队播放的 Song（本地页、队列都用它）。 */
    fun songOf(row: DownloadEntity): Song = Song(
        id = row.songId,
        title = row.title,
        artist = row.artist,
        album = row.album,
        durationMs = row.durationMs,
        uri = Uri.fromFile(File(pathOf(row.fileName))),
        // 以前这里是 null：本地页 / 离线队列的封面就一直是空的。
        // 封面地址在下载时就记下来了，这里补回去，剩下的交给本地封面缓存（CoverCache）。
        albumArtUri = row.coverUrl?.let { runCatching { Uri.parse(it) }.getOrNull() },
        source = if (row.platform.isNullOrBlank()) SongSource.LOCAL else SongSource.ONLINE,
        localPath = pathOf(row.fileName),
        platform = OnlinePlatform.fromKey(row.platform),
        coverUrl = row.coverUrl,
        downloadedQuality = row.quality,
    )

    /**
     * 已下载版本的 Song：uri 指向本地文件，不带任何网络痕迹。
     * 给「优先走本地缓存」这条链路用——下载过的歌，离线也一定放得出来。
     */
    suspend fun offlineCopy(song: Song): Song? {
        val best = recordsOf(song.key).firstOrNull() ?: return null
        return song.copy(
            uri = Uri.fromFile(File(pathOf(best.fileName))),
            localPath = pathOf(best.fileName),
            streamUrl = null,
            cachedQuality = null,
            downloadedQuality = best.quality,
        )
    }

    fun download(song: Song, quality: String) {
        val id = "${song.key}@$quality"
        if (jobs[id]?.isActive == true) {
            PlaybackNotice.brief("「${song.title}」这一档正在下载中")
            return
        }
        val provider = urlProvider
        if (provider == null && resolver == null) {
            // 以前这里是静默 return——用户点了下载什么都没发生，等于「不能用」。
            fail(song, quality, "下载没准备好，重启 App 再试")
            return
        }

        scope.launch {
            val existing = runCatching { dao.find(song.key, quality) }.getOrNull()
            if (existing != null && File(pathOf(existing.fileName)).exists()) {
                PlaybackNotice.brief("「${song.title}」这一档已经在本地了")
                setTask(DownloadTask(song.key, quality, status = DownloadStatus.DONE))
                return@launch
            }
            setTask(DownloadTask(song.key, quality))
            val job = coroutineContext[Job]
            if (job != null) jobs[id] = job
            gate.withPermit { runDownload(song, quality, provider ?: { _, _ -> null }) }
            jobs.remove(id)
        }
    }

    fun cancel(songKey: String, quality: String) {
        val id = "$songKey@$quality"
        jobs[id]?.cancel()
        jobs.remove(id)
        _tasks.value = _tasks.value - id
        PlaybackNotice.brief("已取消，已下的留着，下次接着下")
    }

    private suspend fun runDownload(
        song: Song,
        quality: String,
        provider: suspend (Song, String) -> String?,
    ) {
        val url = resolveStreamUrl(song, quality, provider)
        if (url.isNullOrBlank()) {
            fail(song, quality, "拿不到地址：这档可能没这音质，或登录过期")
            return
        }

        val slug = slug(song.key, quality)
        val part = File(dir(), "$slug.part")
        var conn: HttpURLConnection? = null
        try {
            var offset = if (part.exists()) part.length() else 0L
            conn = openFollowingRedirects(url, offset)
            // 这里**不能**再设任何请求头：openFollowingRedirects 为了跟 302 已经读过
            // responseCode，请求早就发出去了。再 setRequestProperty 会抛
            // "Cannot set request property after connection is made"——0.6.15「无损必失败」就是这个。
            // 头与超时一律在 openFollowingRedirects 里设完，这里只读响应。
            var code = conn.responseCode
            // 残片比服务端认可区间还长（直链换了、文件变小）会回 416：别卡在这里，清掉重来
            if (code == 416 && offset > 0L) {
                runCatching { conn.disconnect() }
                part.delete()
                offset = 0L
                conn = openFollowingRedirects(url, 0L)
                code = conn.responseCode
            }
            if (code !in 200..299) {
                fail(song, quality, explainHttpError(code))
                return
            }
            // 不支持断点续传（回 200 而不是 206）就从 0 重来，别把新数据接在旧残片上
            if (offset > 0 && code == HttpURLConnection.HTTP_OK) {
                part.delete()
                offset = 0L
            }

            val contentLength = conn.contentLengthLong.takeIf { it > 0 } ?: 0L
            val total = if (code == HttpURLConnection.HTTP_PARTIAL) contentLength + offset else contentLength
            if (total > 0) {
                val free = runCatching {
                    StatFs(appContext.filesDir.absolutePath).availableBytes
                }.getOrDefault(Long.MAX_VALUE)
                val need = (total - offset) + 2L * 1024 * 1024
                if (free < need) {
                    fail(song, quality, "空间不够，还差 ${need / 1024 / 1024} MB")
                    return
                }
            }

            val selfJob = currentCoroutineContext()[Job]
            RandomAccessFile(part, "rw").use { raf ->
                raf.seek(offset)
                conn.inputStream.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var received = offset
                    var lastPush = 0L
                    while (selfJob?.isActive ?: true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        raf.write(buffer, 0, read)
                        received += read
                        if (received - lastPush > 256 * 1024) {
                            lastPush = received
                            setTask(
                                DownloadTask(
                                    song.key, quality,
                                    received = received, total = total,
                                ),
                            )
                        }
                    }
                    setTask(
                        DownloadTask(song.key, quality, received = received, total = total),
                    )
                }
            }

            val length = part.length()
            if (total > 0 && length != total) {
                fail(song, quality, "没下完（$length/$total），临时文件留着，可续传")
                return
            }

            val ext = extensionOf(conn.contentType, url)
            val fileName = "$slug.$ext"
            val target = File(dir(), fileName)
            // 同一档位换了扩展名时先清旧的，避免两个文件各占一份空间
            dir().listFiles { f ->
                f.name.startsWith("$slug.") && f.name != fileName && !f.name.endsWith(".part")
            }?.forEach { it.delete() }
            if (!part.renameTo(target)) {
                target.delete()
                if (!part.renameTo(target)) {
                    fail(song, quality, "文件落盘失败，请检查存储空间")
                    return
                }
            }

            val bytes = target.length()
            val kbps = if (song.durationMs > 0) {
                ((bytes * 8.0) / (song.durationMs / 1000.0) / 1000.0).toInt()
            } else {
                0
            }
            dao.upsert(
                DownloadEntity(
                    songKey = song.key,
                    quality = quality,
                    songId = song.id,
                    platform = song.platform?.key,
                    sourceId = song.sourceId,
                    title = song.title,
                    artist = song.artist,
                    album = song.album,
                    durationMs = song.durationMs,
                    coverUrl = song.coverUrl,
                    fileName = fileName,
                    bytes = bytes,
                    kbps = kbps,
                    createdAt = System.currentTimeMillis(),
                ),
            )
            setTask(
                DownloadTask(
                    song.key, quality,
                    received = bytes, total = bytes, status = DownloadStatus.DONE,
                ),
            )
            // 封面顺手落本地：离线时本地页、队列、播放页都能直接出图
            runCatching { coverPrefetch?.invoke(song.coverUrl ?: song.albumArtUri?.toString()) }
            PlaybackNotice.brief("已下载「${song.title}」· ${qualityLabel(quality)}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            fail(song, quality, "下载中断：${e.message ?: "网络异常"}，可重试续传")
        } catch (e: Exception) {
            fail(song, quality, "下载失败：${e.message ?: e.javaClass.simpleName}")
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    /**
     * 顺着 302 一路走到真正的文件地址，**所有请求头在这里设完**。
     *
     * 两条铁律，都是踩过的坑：
     * 1. `rangeOffset` 必须在 `responseCode` **之前**写进请求头——`responseCode` 一读，
     *    请求就已经发出去了，之后再 `setRequestProperty("Range", …)` 只会被忽略，
     *    续传就变成「又从 0 下一遍」；
     * 2. 返回出去的连接**已经连上了**，调用方不许再改任何请求属性（会抛
     *    `Cannot set request property after connection is made`），只能读响应。
     */
    private fun openFollowingRedirects(url: String, rangeOffset: Long = 0L): HttpURLConnection {
        var current = url
        var hops = 0
        while (hops <= MAX_REDIRECTS) {
            hops++
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 20_000
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("User-Agent", USER_AGENT)
                if (rangeOffset > 0) setRequestProperty("Range", "bytes=$rangeOffset-")
            }
            val code = conn.responseCode
            val location = conn.getHeaderField("Location")
            if (code in 300..399 && !location.isNullOrBlank()) {
                conn.disconnect()
                current = if (location.startsWith("http")) location
                else URL(URL(current), location).toString()
                continue
            }
            return conn
        }
        throw IOException("跳转太多次（> $MAX_REDIRECTS），链接可能失效")
    }

    /** HTTP 状态码翻译成人话：下载失败时用户至少知道该重试还是该换档。 */
    private fun explainHttpError(code: Int): String = when {
        code == 401 || code == 403 -> "链接过期了（HTTP $code），再点一次会重取"
        code == 404 -> "服务器没这文件（404），这档可能下架了"
        code == 429 -> "太频繁（429），等会儿再试"
        code in 500..599 -> "服务器挂了（$code），稍后再试"
        else -> "服务器拒绝下载（HTTP $code）"
    }

    /** 扩展名按响应类型/地址推断；后端会按可用性自动降档，所以同时用码率反算给界面显示。 */
    private fun extensionOf(contentType: String?, url: String): String {
        val byType = when (contentType?.substringBefore(';')?.trim()?.lowercase()) {
            "audio/mpeg", "audio/mp3" -> "mp3"
            "audio/flac", "audio/x-flac" -> "flac"
            "audio/mp4", "audio/x-m4a", "audio/aac" -> "m4a"
            "audio/ogg", "application/ogg" -> "ogg"
            else -> null
        }
        if (byType != null) return byType
        val path = url.substringBefore('?').lowercase()
        return when {
            path.endsWith(".flac") -> "flac"
            path.endsWith(".m4a") -> "m4a"
            path.endsWith(".ogg") -> "ogg"
            else -> "mp3"
        }
    }

    private fun setTask(task: DownloadTask) {
        _tasks.value = _tasks.value + (task.id to task)
    }

    private fun fail(song: Song, quality: String, message: String) {
        setTask(
            DownloadTask(song.key, quality, status = DownloadStatus.FAILED, error = message),
        )
        ledger.edit()
            .putString(KEY_LAST_FAIL, message)
            .putString(KEY_LAST_SONG, "${song.title} · ${qualityLabel(quality)}")
            .putLong(KEY_LAST_AT, System.currentTimeMillis())
            .apply()
        PlaybackNotice.brief(message)
    }

    /** 最近一次下载失败（设置页直接展示，用户能照着说清楚卡在哪一步）。 */
    fun lastFailure(): Pair<String, Long>? {
        val text = ledger.getString(KEY_LAST_FAIL, null) ?: return null
        return text to ledger.getLong(KEY_LAST_AT, 0L)
    }

    fun clearLastFailure() = ledger.edit().remove(KEY_LAST_FAIL).remove(KEY_LAST_SONG).remove(KEY_LAST_AT).apply()

    /**
     * 拿一条能下载的直链：先问「这一档」，不行再用播放链路兜底。
     * 两处都失败就返回 null，由调用方给出可读原因。
     */
    private suspend fun resolveStreamUrl(
        song: Song,
        quality: String,
        provider: suspend (Song, String) -> String?,
    ): String? {
        val direct = runCatching { provider(song, quality) }.getOrNull()
            ?.takeIf { it.startsWith("http") }
        if (direct != null) return direct

        val resolved = resolver?.let { runCatching { it(song) }.getOrNull() }
        return resolved?.streamUrl?.takeIf { it.startsWith("http") }
    }

    suspend fun delete(songKey: String, quality: String) = withContext(Dispatchers.IO) {
        val row = runCatching { dao.find(songKey, quality) }.getOrNull()
        if (row != null) runCatching { File(pathOf(row.fileName)).delete() }
        runCatching { File(dir(), "${slug(songKey, quality)}.part").delete() }
        runCatching { dao.deleteOne(songKey, quality) }
        _tasks.value = _tasks.value - "$songKey@$quality"
        Unit
    }

    /** 启动清理：记录还在、文件没了（系统清理、用户手动删、存储迁移）→ 记录一并丢掉。 */
    suspend fun prune() = withContext(Dispatchers.IO) {
        runCatching {
            dao.all().forEach { row ->
                val file = File(pathOf(row.fileName))
                if (!file.exists() || file.length() <= 0) {
                    dao.deleteOne(row.songKey, row.quality)
                    runCatching { File(dir(), "${slug(row.songKey, row.quality)}.part").delete() }
                }
            }
        }
        Unit
    }

    suspend fun totalBytes(): Long = withContext(Dispatchers.IO) {
        runCatching { dao.all().sumOf { it.bytes } }.getOrDefault(0L)
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        runCatching { dao.clear() }
        runCatching { dir().listFiles()?.forEach { it.delete() } }
        _tasks.value = emptyMap()
        Unit
    }

    companion object {
        /** 音质优先级，越大越好；已下载的曲目优先播最好的那一档。 */
        fun rank(quality: String): Int = when (quality) {
            "flac" -> 3
            "320" -> 2
            "128" -> 1
            else -> 0
        }

        fun qualityLabel(quality: String): String = when (quality) {
            "flac" -> "无损"
            "320" -> "320K"
            "128" -> "128K"
            "aac" -> "AAC"
            else -> quality
        }

        /** 下载可选的三档（auto 落什么文件不确定，故不进下载入口）。 */
        val QUALITIES = listOf("flac", "320", "128")

        const val USER_AGENT = "AMMusic/0.6.41 (Android)"

        /** 302 链最多跟几跳（QQ/网易直链一般 1–2 跳，留足余量但不无限跟）。 */
        private const val MAX_REDIRECTS = 6

        private const val KEY_LAST_FAIL = "last_fail"
        private const val KEY_LAST_SONG = "last_fail_song"
        private const val KEY_LAST_AT = "last_fail_at"
    }
}
