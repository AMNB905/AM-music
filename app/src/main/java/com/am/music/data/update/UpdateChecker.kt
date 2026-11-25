package com.am.music.data.update

import android.content.Context
import com.am.music.BuildConfig
import com.am.music.data.online.MusicApi
import com.am.music.data.security.Secrets
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 一次远端版本检查的结果。
 *
 * 现在有两条来源，字段完全兼容：
 *
 * - **GitHub Releases（主路线）**：读 `repos/{owner}/{repo}/releases/latest`，
 *   取 `tag_name`、正文里的 `versionCode`、以及那个 `.apk` 资产。资产下载要经过
 *   API（私有仓库匿名拉不到），所以带上 [assetId] 交给 [UpdateInstaller] 走鉴权下载。
 * - **老 version.json（兜底）**：自家域名下一个静态 JSON，GitHub 不通时还能提示更新。
 *
 * 判断规则不变：
 * - `currentCode < force.belowCode` → **强制更新**（弹窗不可关闭，只有「去更新」一个出口）；
 * - `currentCode < latest.code`     → **普通更新**（可以「稍后」，24 小时内不再打扰）；
 * - 其余                             → 不提示。
 */
data class UpdateInfo(
    val latestCode: Int,
    val latestName: String,
    val url: String,
    val notes: String,
    val forceBelowCode: Int,
    val forceMessage: String,
    /** GitHub 资产的 id；0 表示不是 GitHub 来源，直接用 [url] 下载。 */
    val assetId: Long = 0L,
    /** 资产字节数（服务端给的），用来在 content-length 缺失时算进度。 */
    val assetSize: Long = 0L,
    val assetName: String = "",
    val fromGitHub: Boolean = false,
)

enum class UpdateVerdict { NONE, SOFT, FORCE }

class UpdateChecker(
    context: Context,
    private val baseUrl: String = MusicApi.BASE_URL,
) {

    private val prefs = context.applicationContext
        .getSharedPreferences("am_update", Context.MODE_PRIVATE)

    /**
     * 拉取远端版本信息；GitHub 与兜底都失败就返回 null，绝不影响启动。
     *
     * 缓存 30 分钟：匿名调用 GitHub API 每 IP 每小时只有 60 次，启动就查一次很容易撞上，
     * 命中限额后连「有没有新版」都问不出来。同一版的判定结果短时间内不会变，缓存没有副作用。
     */
    suspend fun load(): UpdateInfo? = withContext(Dispatchers.IO) {
        github() ?: cloud()
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

    // ---------------------------------------------------------------- GitHub

    private fun github(): UpdateInfo? {
        if (BuildConfig.UPDATE_REPO.isBlank()) return null
        val cached = prefs.getString(KEY_GH_JSON, null)
        val fresh = cached != null &&
            System.currentTimeMillis() - prefs.getLong(KEY_GH_TS, 0L) < GH_CACHE_MS
        if (fresh) return parseGitHub(cached!!)
        val raw = fetchGitHub() ?: return null
        prefs.edit()
            .putString(KEY_GH_JSON, raw)
            .putLong(KEY_GH_TS, System.currentTimeMillis())
            .apply()
        return parseGitHub(raw)
    }

    private fun fetchGitHub(): String? {
        val url = "https://api.github.com/repos/" + BuildConfig.UPDATE_REPO + "/releases/latest"
        val connection = (URL(url).openConnection() as HttpURLConnection)
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 8_000
            connection.readTimeout = 12_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            connection.setRequestProperty("User-Agent", USER_AGENT)
            if (BuildConfig.UPDATE_TOKEN.isNotBlank()) {
                connection.setRequestProperty("Authorization", "Bearer " + BuildConfig.UPDATE_TOKEN)
            }
            if (connection.responseCode !in 200..299) return null
            connection.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    /**
     * 解析 release：版本号来自正文里的 `versionCode: NN` 标记（由发版脚本写入，不靠 tag 猜），
     * 下载地址来自 assets 里那个 `.apk`。
     *
     * 只认自家仓库的资产：这条链路里唯一可被投毒的是 API 返回，而下载前
     * [UpdateInstaller] 还会再校验包名与签名指纹，两道都过才落到系统安装器。
     */
    private fun parseGitHub(raw: String): UpdateInfo? {
        return try {
            val root = JSONObject(raw)
            if (root.optBoolean("draft")) return null
            val body = root.optString("body").orEmpty()
            val tag = root.optString("tag_name").trim()
            val code = MARKER_CODE.find(body)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            if (code <= 0) return null
            val assets = root.optJSONArray("assets") ?: return null
            val asset = (0 until assets.length())
                .mapNotNull { assets.optJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) }
                ?: return null
            UpdateInfo(
                latestCode = code,
                latestName = root.optString("name").trim().ifEmpty {
                    tag.ifEmpty { "新版本" }
                },
                url = asset.optString("browser_download_url"),
                notes = body
                    .replace(MARKER_CODE, "")
                    .replace(MARKER_FORCE, "")
                    .trim(),
                forceBelowCode = MARKER_FORCE.find(body)?.groupValues?.get(1)?.toIntOrNull() ?: 0,
                forceMessage = "",
                assetId = asset.optLong("id"),
                assetSize = asset.optLong("size"),
                assetName = asset.optString("name"),
                fromGitHub = true,
            )
        } catch (e: Exception) {
            null
        }
    }

    // ------------------------------------------------------- 老 version.json

    /** 兜底：GitHub 不可达时，仍按自家域名下的 version.json 判断。 */
    private fun cloud(): UpdateInfo? {
        val raw = fetchCloud() ?: return null
        return runCatching {
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

    private fun origin(): String {
        val scheme = baseUrl.substringBefore("://", "https") + "://"
        return scheme + baseUrl.substringAfter("://", "").substringBefore('/')
    }

    private fun fetchCloud(): String? {
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
        const val KEY_GH_JSON = "gh_json"
        const val KEY_GH_TS = "gh_ts"
        const val SNOOZE_MS = 24L * 60 * 60 * 1000
        const val GH_CACHE_MS = 30L * 60 * 1000
        const val USER_AGENT = "AMMusic/0.6.61 (Android)"

        /** 发版脚本写进 release 正文的两行机器可读标记。 */
        val MARKER_CODE = Regex("(?m)^\\s*versionCode\\s*[:=]\\s*(\\d+)\\s*$")
        val MARKER_FORCE = Regex("(?m)^\\s*forceBelow\\s*[:=]\\s*(\\d+)\\s*$")
    }
}
