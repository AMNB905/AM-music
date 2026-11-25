package com.am.music.data.cover

import android.content.Context
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * 封面落盘：`filesDir/covers/<sha1(归一化 url)>.<ext>`，按 URL 做键（同一张图不重复存）。
 *
 * 为什么自己做一层，而不是只靠 Coil 的 diskCache：
 * 1. **离线要能出图**。Coil 的缓存在 `cacheDir`，系统低空间/清缓存时会连图一起收走；
 *    `filesDir` 下的文件是"用户的"，只有设置页的清理按钮会动它。
 * 2. **图床不一定对 Coil 友好**。部分封面 CDN 对 UA / Referer 敏感，用我们自己的 UA
 *    直取反而稳；拿不到就明确放弃，不让界面卡在一个永远转圈的请求上。
 * 3. **失败可感知**。拿到 403 或一张几百字节的错误页时，宁可当作"没图"，
 *    也绝不把坏数据写进缓存（否则离线时永远显示一张裂图）。
 *
 * 关于「每次打开都在重新下载」：两个原因都在这里，一并修掉了——
 * - 键原先直接用完整 URL，而图床爱在 query 里塞 `?t=时间戳`/`di`/`clientCacheKey` 这类
 *   一次性的缓存击穿参数，URL 每次都不一样，等于每张图都换了个文件名，永远命中不了；
 *   现在先归一化再取哈希（保留 `param=` 这类决定尺寸的参数）。
 * - 落盘前的校验只看「大于 8KB」，而不少正版封面（尤其 100×100 的小图）根本没这么大，
 *   于是每次都被判成坏图删掉、下次再下一遍。现在改成看**文件头魔数**，
 *   只要确实是 JPEG/PNG/WebP/GIF/AVIF 就留。
 */
class CoverCache(context: Context) {

    private val dir = File(context.filesDir, "covers")

    /** 同时在飞的封面最多 5 张：网格一次滚出十几张，全并发只会互相拖慢。 */
    private val gate = Semaphore(5)

    /** 进程内命中表：列表滚动时不要每次都去 stat 磁盘。 */
    private val memo = ConcurrentHashMap<String, File>()

    /** 同步查本地（组合阶段只允许调这个，不能做 IO）。 */
    fun peek(url: String?): File? {
        val key = url?.takeIf { it.startsWith("http") } ?: return null
        memo[key]?.takeIf { it.length() > 0 && it.exists() }?.let { return it }
        val file = fileFor(key)
        return if (file.exists() && file.length() > 0) {
            memo[key] = file
            file
        } else {
            null
        }
    }

    /**
     * 确保这张封面在本地。返回本地文件；拿不到（无网 / 4xx / 内容不是图片）返回 null。
     * 已经落盘的直接返回，不再碰网络。
     */
    suspend fun ensure(url: String?): File? {
        val key = url?.takeIf { it.startsWith("http") } ?: return null
        peek(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            gate.withPermit {
                peek(key) ?: download(key)
            }
        }
    }

    suspend fun bytes(): Long = withContext(Dispatchers.IO) {
        runCatching {
            dir.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
        }.getOrDefault(0L)
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        runCatching {
            dir.listFiles()?.forEach { it.delete() }
            memo.clear()
        }
        Unit
    }

    private fun download(url: String): File? {
        // 临时名带随机后缀：同一张图被两处同时请求时不会互相覆盖半截文件
        val tmp = File(dir, hash(url) + "-" + System.nanoTime() + ".tmp").apply { parentFile?.mkdirs() }
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "image/*")
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                tmp.delete()
                return null
            }
            conn.inputStream.use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
            // 看文件头判真伪：错误页是 HTML，占位图是 1×1 PNG，都不该被当成封面留下
            if (!isImage(tmp)) {
                tmp.delete()
                return null
            }
            val target = File(dir, hash(url) + "." + extensionOf(conn.contentType, url))
            val previous = fileFor(url)
            if (previous.exists() && previous != target) previous.delete()
            if (!tmp.renameTo(target)) {
                target.writeBytes(tmp.readBytes())
                tmp.delete()
            }
            memo[url] = target
            target
        } catch (e: IOException) {
            tmp.delete()
            null
        } catch (e: Exception) {
            tmp.delete()
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private fun fileFor(url: String): File {
        val dir = dir
        if (!dir.exists()) dir.mkdirs()
        // 扩展名可能换（jpg/webp），按 base 扫一遍现有文件
        val base = hash(url)
        dir.listFiles { f -> f.isFile && f.name.startsWith("$base.") }?.firstOrNull()?.let { return it }
        return File(dir, "$base.jpg")
    }

    /**
     * 归一化后再取哈希。
     *
     * 丢掉一次性的缓存击穿参数（`t` / `ts` / `di` / `bp` / `clientCacheKey` / `tt` / `sign`…），
     * 只保留会影响图片本身的（`param` 决定网易云图尺寸、`w`/`h`/`quality` 这类）。
     */
    private fun hash(url: String): String {
        val normalized = normalize(url)
        val digest = MessageDigest.getInstance("SHA-1").digest(normalized.toByteArray(Charsets.UTF_8))
        return buildString { digest.forEach { append(String.format("%02x", it.toInt() and 0xFF)) } }
    }

    private fun normalize(url: String): String {
        val cleaned = url.substringBefore('#')
        val query = cleaned.substringAfter('?', "")
        if (query.isEmpty()) return cleaned
        val base = cleaned.substringBefore('?')
        val keep = query.split('&').filter { pair ->
            val name = pair.substringBefore('=').lowercase()
            name in KEEP_PARAMS
        }
        return if (keep.isEmpty()) base else base + "?" + keep.joinToString("&")
    }

    /** 文件头魔数：JPEG / PNG / WebP / GIF / AVIF / HEIC。 */
    private fun isImage(file: File): Boolean {
        if (file.length() < 512) return false
        return runCatching {
            val head = ByteArray(32)
            file.inputStream().use { it.read(head) }
            val jpeg = head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte()
            val png = head[0] == 0x89.toByte() && head[1] == 0x50.toByte() &&
                head[2] == 0x4E.toByte() && head[3] == 0x47.toByte()
            val gif = head[0] == 0x47.toByte() && head[1] == 0x49.toByte() && head[2] == 0x46.toByte()
            val riff = head[0] == 0x52.toByte() && head[1] == 0x49.toByte() &&
                head[2] == 0x46.toByte() && head[3] == 0x46.toByte()
            // ISO-BMFF 家族（AVIF / HEIC）第 4..7 字节是 ftyp
            val bmff = head.copyOfRange(4, 8).contentEquals("ftyp".toByteArray())
            jpeg || png || gif || riff || bmff
        }.getOrDefault(false)
    }

    private fun extensionOf(contentType: String?, url: String): String =
        when (contentType?.substringBefore(';')?.trim()?.lowercase()) {
            "image/jpeg", "image/jpg" -> "jpg"
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            else -> when {
                url.contains(".png", true) -> "png"
                url.contains(".webp", true) -> "webp"
                else -> "jpg"
            }
        }

    private companion object {
        const val USER_AGENT = "AMMusic/0.6.41 (Android)"

        /** 会改变图片本体的参数：保留它们，同一张图不同尺寸才算两个文件。 */
        val KEEP_PARAMS = setOf("param", "w", "h", "width", "height", "quality", "type", "format")
    }
}
