package com.am.music.data.online

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 把音乐平台的短链展开成最终地址。
 *
 * 为什么必须有这一步：QQ 音乐分享卡片的二维码里是
 * `https://c6.y.qq.com/base/fcgi-bin/u?__=WR4InQeTqa5f` 这种短链，
 * 302 之后才变成带 `id=` 的播放列表地址（实测跳到的就是用户手里那条链接的形状）。
 * 没有展开，扫码/选图识别出来的内容根本认不出歌单 id。
 *
 * 只跟音乐平台的域名：这是个「跟着跳转走一次」的工具，不该变成任意 URL 的代理。
 * 允许的域名写死在 [allowed]，跳转过程中跳出白名单就停。
 */
object LinkResolver {

    private const val MAX_HOPS = 4
    private const val TIMEOUT_MS = 10_000

    private val allowed = listOf(
        "qq.com", "y.qq.com", "c6.y.qq.com", "i2.y.qq.com",
        "163.com", "music.163.com", "163cn.tv",
    )

    private val userAgent =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"

    suspend fun resolve(url: String): String? = withContext(Dispatchers.IO) {
        var current = url
        repeat(MAX_HOPS) {
            if (!isAllowed(current)) return@withContext null
            val result = runCatching { head(current) }.getOrNull() ?: return@withContext null
            val location = result
            if (location == null || location == current) {
                return@withContext current
            }
            current = location
        }
        current
    }

    /** 返回跳转目标；没有跳转（200 直接给内容）返回 null。 */
    private fun head(url: String): String? {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("User-Agent", userAgent)
            connection.setRequestProperty("Accept", "text/html,*/*")
            val code = connection.responseCode
            if (code in 300..399) {
                val raw = connection.getHeaderField("Location") ?: return null
                return URL(URL(url), raw).toString()
            }
            return null
        } finally {
            connection.disconnect()
        }
    }

    private fun isAllowed(url: String): Boolean {
        val host = runCatching { URL(url).host.lowercase() }.getOrNull() ?: return false
        return allowed.any { host == it || host.endsWith(".$it") }
    }
}
