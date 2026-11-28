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
    /** 清单里给的 git blob sha：有它就走 `api.github.com/.../git/blobs/{sha}` 取包。 */
    val blobSha: String = "",
)

enum class UpdateVerdict { NONE, SOFT, FORCE }

class UpdateChecker(
    context: Context,
    private val baseUrl: String = MusicApi.BASE_URL,
) {

    private val prefs = context.applicationContext
        .getSharedPreferences("am_update", Context.MODE_PRIVATE)

    /** 最近一次检查失败的原因（限流 / 连不上 / 结构不认识），给设置页一句人话。 */
    @Volatile
    var lastFailure: String? = null
        private set

    /**
     * 拉取远端版本信息；GitHub 与兜底都失败就返回 null，绝不影响启动。
     *
     * 缓存 30 分钟：匿名调用 GitHub API 每 IP 每小时只有 60 次，启动就查一次很容易撞上，
     * 命中限额后连「有没有新版」都问不出来。同一版的判定结果短时间内不会变，缓存没有副作用。
     *
     * forceRefresh = true 是给「设置里手动点检查更新」用的：人已经主动问了，就不能把
     * 20 分钟前的旧答案再喂回去——否则刚发完版，用户点检查更新反而被告知「已是最新」。
     */
    suspend fun load(forceRefresh: Boolean = false): UpdateInfo? = withContext(Dispatchers.IO) {
        github(forceRefresh) ?: repoManifest() ?: cloud()
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

    private fun github(forceRefresh: Boolean = false): UpdateInfo? {
        if (BuildConfig.UPDATE_REPO.isBlank()) return null
        val cached = prefs.getString(KEY_GH_JSON, null)
        val fresh = !forceRefresh && cached != null &&
            System.currentTimeMillis() - prefs.getLong(KEY_GH_TS, 0L) < GH_CACHE_MS
        if (fresh) return parseGitHub(cached!!)
        val raw = fetchGitHub()
        if (raw != null) {
            // 只有认得出的 JSON 才进缓存：以前把 403 / 空壳也存 30 分钟，
            // 结果「刚发完版点检查更新」反而被告知已是最新
            val parsed = parseGitHub(raw)
            if (parsed != null) {
                prefs.edit()
                    .putString(KEY_GH_JSON, raw)
                    .putLong(KEY_GH_TS, System.currentTimeMillis())
                    .apply()
                lastFailure = null
                return parsed
            }
            lastFailure = "更新数据格式不认识"
        }
        // 网络断了 / 被限流：手里还有上一次的答案就先给它，别让人对着「已是最新」空欢喜
        cached?.let { old -> parseGitHub(old)?.let { return it } }
        return null
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
            val code = connection.responseCode
            if (code !in 200..299) {
                lastFailure = when (code) {
                    403, 429 -> "GitHub 接口限流了，过一小时再试"
                    404 -> "仓库里没有这个文件"
                    else -> "GitHub 返回 $code"
                }
                null
            } else {
                connection.inputStream.bufferedReader().use { it.readText() }
            }
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
            val assets = root.optJSONArray("assets") ?: return null
            val asset = (0 until assets.length())
                .mapNotNull { assets.optJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) }
                ?: return null
            // versionCode 认三条：正文里的标记 → 资产名末尾的 -NNN → tag 末尾的 -NNN。
            // 只靠第一条太脆：发版时正文少写一行标记，客户端就会当成「没有新版本」，
            // 而且这种失败没有任何提示，最难查。
            val assetName = asset.optString("name")
            val code = MARKER_CODE.find(body)?.groupValues?.get(1)?.toIntOrNull()
                ?: ASSET_CODE.find(assetName)?.groupValues?.get(1)?.toIntOrNull()
                ?: TAG_CODE.find(tag)?.groupValues?.get(1)?.toIntOrNull()
                ?: 0
            if (code <= 0) {
                lastFailure = "Release 里找不到版本号（正文缺 versionCode，资产名也没带）"
                return null
            }
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
                assetName = assetName,
                fromGitHub = true,
            )
        } catch (e: Exception) {
            null
        }
    }

    // ------------------------------------------- 公开仓库里提交的 latest.json

    /**
     * 第二来源：公开仓库里提交的 `releases/latest.json`（走 raw 直链，匿名可读，不吃 API 配额）。
     *
     * 为什么留这条：GitHub Release 必须用带 `Contents: write` 的 token 通过 API 创建，
     * 而 token 既不能打进包里、也不该每次发版都让人现给一把。改成「发版脚本把 APK 和这份
     * 清单一起 git push 上去」之后，客户端只要匿名 GET，发版只要仓库写权限。
     * 清单格式与老的 version.json 完全一致，只是 url 指向 GitHub。
     */
    private fun repoManifest(): UpdateInfo? {
        if (BuildConfig.UPDATE_REPO.isBlank()) return null
        // 先走 api.github.com 的 contents 直读（raw media type）：国内网络里 github.com 与
        // raw.githubusercontent.com 经常连不上，而 api.github.com 一直是通的。
        val api = "https://api.github.com/repos/" + BuildConfig.UPDATE_REPO +
            "/contents/releases/latest.json?ref=main&t=" + System.currentTimeMillis()
        val raw = fetchJson(api, rawMediaType = true)
            ?: fetchJson(
                "https://raw.githubusercontent.com/" + BuildConfig.UPDATE_REPO +
                    "/main/releases/latest.json?t=" + System.currentTimeMillis(),
            )
            ?: return null
        return parseManifest(raw, allowGithub = true)
    }

    // ------------------------------------------------------- 老 version.json

    /** 兜底：GitHub 不可达时，仍按自家域名下的 version.json 判断。 */
    private fun cloud(): UpdateInfo? {
        val raw = fetchCloud() ?: return null
        return parseManifest(raw, allowGithub = false)
    }

    private fun parseManifest(raw: String, allowGithub: Boolean): UpdateInfo? {
        return runCatching {
            val root = JSONObject(raw)
            val latest = root.optJSONObject("latest") ?: return@runCatching null
            val force = root.optJSONObject("force")
            val code = latest.optInt("code", 0)
            if (code <= 0) return@runCatching null
            val url = latest.optString("url").trim()
            // 只接受自家域名（或自家仓库）下的下载地址：清单万一被投毒，也不能把用户导去第三方
            if (!hostAllowed(url, allowGithub)) return@runCatching null
            UpdateInfo(
                latestCode = code,
                latestName = latest.optString("name").trim().ifEmpty { "新版本" },
                url = url,
                notes = latest.optString("notes").trim(),
                forceBelowCode = (force?.optInt("belowCode", 0) ?: 0),
                forceMessage = force?.optString("message").orEmpty().trim(),
                assetSize = latest.optLong("size"),
                assetName = latest.optString("asset").trim(),
                blobSha = latest.optString("blobSha").trim(),
            )
        }.getOrNull()
    }

    private fun hostAllowed(url: String, allowGithub: Boolean): Boolean {
        if (url.startsWith(origin())) return true
        if (!allowGithub) return false
        val repo = BuildConfig.UPDATE_REPO
        return url.startsWith("https://github.com/$repo/") ||
            url.startsWith("https://raw.githubusercontent.com/$repo/")
    }

    private fun origin(): String {
        val scheme = baseUrl.substringBefore("://", "https") + "://"
        return scheme + baseUrl.substringAfter("://", "").substringBefore('/')
    }

    private fun fetchJson(url: String, rawMediaType: Boolean = false): String? {
        val connection = (URL(url).openConnection() as HttpURLConnection)
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 8_000
            connection.readTimeout = 12_000
            // contents 接口默认返回 base64 JSON，要原始文件得声明 raw media type
            connection.setRequestProperty(
                "Accept",
                if (rawMediaType) "application/vnd.github.raw" else "application/json",
            )
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Cache-Control", "no-store")
            val code = connection.responseCode
            if (code !in 200..299) {
                lastFailure = when (code) {
                    403, 429 -> "GitHub 接口限流了，过一小时再试"
                    404 -> "仓库里没有这个文件"
                    else -> "GitHub 返回 $code"
                }
                null
            } else {
                connection.inputStream.bufferedReader().use { it.readText() }
            }
        } catch (e: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun fetchCloud(): String? {
        // 带一个时间戳查询串：CDN 会按 URL 缓存静态文件，只发 no-store 请求头它不一定理，
        // 发版后头几分钟里可能还拿到上一版 version.json（code 比当前还小，看着像没更新）。
        return fetchJson(baseUrl + Secrets.path("p24") + "?t=" + System.currentTimeMillis())
    }

    private companion object {
        const val KEY_SNOOZE_CODE = "snooze_code"
        const val KEY_SNOOZE_UNTIL = "snooze_until"
        const val KEY_GH_JSON = "gh_json"
        const val KEY_GH_TS = "gh_ts"
        const val SNOOZE_MS = 24L * 60 * 60 * 1000
        const val GH_CACHE_MS = 30L * 60 * 1000
        val USER_AGENT get() = "AMMusic/" + com.am.music.BuildConfig.VERSION_NAME + " (Android)"

        /** 发版脚本写进 release 正文的两行机器可读标记。 */
        val MARKER_CODE = Regex("(?m)^\\s*versionCode\\s*[:=]\\s*(\\d+)\\s*$")
        val MARKER_FORCE = Regex("(?m)^\\s*forceBelow\\s*[:=]\\s*(\\d+)\\s*$")

        /** 资产名 `AMMusic-0.7.0-113.apk` 尾部的版本号。 */
        val ASSET_CODE = Regex("""-(\d+)\.apk$""", RegexOption.IGNORE_CASE)

        /** tag 形如 `v0.7.0-113` 时尾部的版本号。 */
        val TAG_CODE = Regex("""-(\d+)$""")
    }
}
