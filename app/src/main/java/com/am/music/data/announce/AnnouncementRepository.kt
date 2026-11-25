package com.am.music.data.announce

import android.content.Context
import com.am.music.data.online.MusicApi
import com.am.music.data.security.Secrets
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** 一条公告（对应后端 `System/api.php?action=list_public` 的 items 元素）。 */
data class Announcement(
    val id: Int,
    val title: String,
    val content: String,
    val link: String,
    val type: Int,        // 1 普通 / 2 重要 / 3 紧急
    val updatedAt: String,
)

/** 一次拉取的结果：`version` 是后端每次保存公告时自增的版本号，用来判断「内容有没有变」。 */
data class AnnouncementFeed(
    val version: Int,
    val items: List<Announcement>,
)

/**
 * 启动公告。
 *
 * 口径（与需求对齐）：
 * - **每次启动都拉一次**，有启用中的公告就弹；
 * - 点「不再显示」把这个**内容指纹**记下来，之后启动只要指纹没变就不再弹；
 * - 后端每次保存公告都会把 `version` 自增，因此**改了内容 / 加了新公告**指纹就会变，弹窗会重新出现。
 *
 * 接口是后端刻意公开的（`System/api.php?action=list_public`，不含任何用户数据），
 * 所以这里走明文 GET，不走 `SecuritySession` 签名——公告本身不是受保护资源，
 * 也让弹窗不依赖票据是否已激活。
 */
class AnnouncementRepository(
    context: Context,
    private val baseUrl: String = MusicApi.BASE_URL,
) {

    private val prefs = context.getSharedPreferences("am_announce", Context.MODE_PRIVATE)

    suspend fun load(): AnnouncementFeed? = withContext(Dispatchers.IO) {
        val raw = fetch() ?: return@withContext null
        runCatching {
            val json = JSONObject(raw)
            if (json.optInt("code", -1) != 0) return@runCatching null
            val version = json.optInt("version", 0)
            val array = json.optJSONArray("items") ?: return@runCatching null
            val items = (0 until array.length()).mapNotNull { index ->
                val obj = array.optJSONObject(index) ?: return@mapNotNull null
                val title = obj.optString("title").trim()
                val content = obj.optString("content").trim()
                if (title.isEmpty() && content.isEmpty()) return@mapNotNull null
                Announcement(
                    id = obj.optInt("id", 0),
                    title = title,
                    content = content,
                    link = obj.optString("link").trim(),
                    type = obj.optInt("type", 1),
                    updatedAt = obj.optString("updatedAt").trim(),
                )
            }
            AnnouncementFeed(version = version, items = items)
        }.getOrNull()
    }

    /** 内容指纹：version + 每条公告的 id/标题/正文/更新时间，任意一处变动都会换指纹。 */
    fun signatureOf(feed: AnnouncementFeed): String {
        val raw = buildString {
            append(feed.version)
            feed.items.forEach { item ->
                append('\u001F').append(item.id)
                append('\u001F').append(item.title)
                append('\u001F').append(item.content)
                append('\u001F').append(item.updatedAt)
            }
        }
        val digest = MessageDigest.getInstance("SHA-1").digest(raw.toByteArray(Charsets.UTF_8))
        return buildString { digest.forEach { append(String.format("%02x", it.toInt() and 0xFF)) } }
    }

    /** 这份内容是否已被用户「不再显示」过。 */
    fun isDismissed(signature: String): Boolean =
        prefs.getString(KEY_DISMISSED_SIGNATURE, null) == signature

    fun dismiss(signature: String) {
        prefs.edit().putString(KEY_DISMISSED_SIGNATURE, signature).apply()
    }

    private fun fetch(): String? {
        val connection = (URL(baseUrl + Secrets.path("p23") + "?action=list_public").openConnection() as HttpURLConnection)
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Cache-Control", "no-store")
            if (connection.responseCode !in 200..299) return null
            connection.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val KEY_DISMISSED_SIGNATURE = "dismissed_signature"
        const val CONNECT_TIMEOUT_MS = 8_000
        const val READ_TIMEOUT_MS = 12_000
        const val USER_AGENT = "AMMusic/0.6.41 (Android)"
    }
}
