package com.am.music.data.update

import android.content.Context
import com.am.music.data.online.MusicApi
import com.am.music.data.security.Secrets
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 一次云端版本检查的结果。
 *
 * 服务端只放一个静态 `version.json`（`{'latest':{code,name,url,notes},'force':{belowCode,message}}`），
 * 客户端启动拉一次，按下面三条判断：
 *
 * - `currentCode < force.belowCode` → **强制更新**（弹窗不可关闭，只有「去更新」一个出口）；
 * - `currentCode < latest.code`     → **普通更新**（可以「稍后」，24 小时内不再打扰）；
 * - 其余                             → 不提示。
 *
 * 为什么阈值和「最新版」分开写：发版时先把 `latest.code` 抬上去让愿意升级的人先升，
 * 观察一两天没问题再把 `force.belowCode` 抬到同一档——不用改客户端、也不用重新发包。
 */
data class UpdateInfo(
    val latestCode: Int,
    val latestName: String,
    val url: String,
    val notes: String,
    val forceBelowCode: Int,
    val forceMessage: String,
)

enum class UpdateVerdict { NONE, SOFT, FORCE }

class UpdateChecker(
    context: Context,
    private val baseUrl: String = MusicApi.BASE_URL,
) {

    private val prefs = context.getSharedPreferences("am_update", Context.MODE_PRIVATE)

    /** 拉取远端版本信息；网络不通/内容不合法都返回 null，绝不影响启动。 */
    suspend fun load(): UpdateInfo? = withContext(Dispatchers.IO) {
        val raw = fetch() ?: return@withContext null
        runCatching {
            val root = JSONObject(raw)
            val latest = root.optJSONObject("latest") ?: return@runCatching null
            val force = root.optJSONObject("force")
            val code = latest.optInt("code", 0)
            if (code <= 0) return@runCatching null
            val url = latest.optString("url").trim()
            // 只接受自家域名下的下载地址：version.json 万一被投毒，也不能把用户导去第三方
            if (!url.startsWith(origin())) return@runCatching null
            UpdateInfo(
                latestCode = code,
                latestName = latest.optString("name").trim().ifEmpty { "新版本" },
                url = url,
                notes = latest.optString("notes").trim(),
                forceBelowCode = (force?.optInt("belowCode", 0) ?: 0),
                forceMessage = force?.optString("message").orEmpty().trim(),
            )
        }.getOrNull()
    }

    fun verdict(info: UpdateInfo, currentCode: Int): UpdateVerdict = when {
        currentCode < info.forceBelowCode -> UpdateVerdict.FORCE
        currentCode < info.latestCode && !isSnoozed(info.latestCode) -> UpdateVerdict.SOFT
        else -> UpdateVerdict.NONE
    }

    /** 「稍后」= 这一版 24 小时内不再软提示；强制版不受影响。 */
    fun snooze(info: UpdateInfo) {
        prefs.edit()
            .putInt(KEY_SNOOZE_CODE, info.latestCode)
            .putLong(KEY_SNOOZE_UNTIL, System.currentTimeMillis() + SNOOZE_MS)
            .apply()
    }

    private fun isSnoozed(code: Int): Boolean {
        if (prefs.getInt(KEY_SNOOZE_CODE, 0) != code) return false
        return System.currentTimeMillis() < prefs.getLong(KEY_SNOOZE_UNTIL, 0L)
    }

    private fun origin(): String {
        val scheme = baseUrl.substringBefore("://", "https") + "://"
        return scheme + baseUrl.substringAfter("://", "").substringBefore('/')
    }

    private fun fetch(): String? {
        // 带一个时间戳查询串：CDN 会按 URL 缓存静态文件，只发 no-store 请求头它不一定理，
        // 发版后头几分钟里可能还拿到上一版 version.json（code 比当前还小，看着像没更新）。
        val url = baseUrl + Secrets.path("p24") + "?t=" + System.currentTimeMillis()
        val connection = (URL(url).openConnection() as HttpURLConnection)
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 8_000
            connection.readTimeout = 12_000
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
        const val KEY_SNOOZE_CODE = "snooze_code"
        const val KEY_SNOOZE_UNTIL = "snooze_until"
        const val SNOOZE_MS = 24L * 60 * 60 * 1000
        const val USER_AGENT = "AMMusic/0.6.41 (Android)"
    }
}
