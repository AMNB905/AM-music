package com.am.music.data.security

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.am.music.data.online.MusicApi
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * 设备票据 + 请求签名（与后端 `security/Security.php` 的口径逐字节一致）。
 *
 * 后端校验原文（canonical）：
 *   METHOD \n PATH \n SORTED_QUERY \n SHA256(BODY) \n TS \n NONCE
 * - PATH 是 REQUEST_URI 的 path 部分，**含部署前缀**（/AMmusic/qq/api/search）；
 * - SORTED_QUERY 是把原始 query string 按 `&` 分段后整段升序拼回（不解码）；
 * - 使用 HMAC-SHA256，密钥是激活时下发的 sessionKey（激活本身用内置 appSecret）。
 *
 * 激活：`POST {base}/security/activate.php`，body `{"keyId","fp","ts","nonce"}`，
 * header `X-Am-App` / `X-Am-Sign`（用 appSecret 签）。票据到期或被吊销时，
 * 业务请求会收到 401，[MusicApi] 会调 [invalidate] 后重试一次。
 */
class SecuritySession(
    context: Context,
    private val baseUrl: String = MusicApi.BASE_URL,
) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("am_security", Context.MODE_PRIVATE)
    private val lock = Mutex()

    @Volatile
    private var ticket: String? = prefs.getString(KEY_TICKET, null)

    @Volatile
    private var sessionKey: String? = prefs.getString(KEY_SESSION_KEY, null)

    /** 部署前缀（含首尾斜杠），由 baseUrl 推导：https://host/AMmusic/ → /AMmusic/ */
    private val pathPrefix: String = run {
        val afterScheme = baseUrl.substringAfter("://", "")
        val slash = afterScheme.indexOf('/')
        if (slash < 0) "/" else afterScheme.substring(slash)
    }

    /**
     * 为一次业务请求生成签名头。票据缺失或已失效时先自动激活。
     * 返回空 Map 表示无法签名（网络差 / 服务端拒绝），调用方按失败处理。
     */
    suspend fun headersFor(
        method: String,
        path: String,
        query: String,
        body: ByteArray?,
    ): Map<String, String> = lock.withLock {
        // 被调试器附着时一个字都不签：抓包改签名、下断点抠票在这一层就断了
        if (!Integrity.isTrusted()) return@withLock emptyMap()
        if (ticket.isNullOrEmpty() || sessionKey.isNullOrEmpty()) {
            if (!activate()) return@withLock emptyMap()
        }
        val ts = nowSeconds()
        val nonce = newNonce()
        val canonical = canonical(method, path, query, body ?: EMPTY_BODY, ts, nonce)
        mapOf(
            "X-Am-App" to APP_ID,
            "X-Am-Ticket" to ticket.orEmpty(),
            "X-Am-Ts" to ts,
            "X-Am-Nonce" to nonce,
            "X-Am-Sign" to hmac(sessionKey.orEmpty(), canonical),
        )
    }

    /** 票据被服务端判无效时调用：清空本地凭证，下一次请求重新激活。 */
    fun invalidate() {
        ticket = null
        sessionKey = null
        prefs.edit().remove(KEY_TICKET).remove(KEY_SESSION_KEY).apply()
    }

    // ---------------------------------------------------------------- 激活

    private suspend fun activate(): Boolean {
        if (!Integrity.isTrusted()) return false
        val fp = deviceFingerprint()
        val ts = nowSeconds()
        val nonce = newNonce()
        val body = JSONObject()
            .put("keyId", KEY_ID)
            .put("fp", JSONObject(fp))
            .put("ts", ts)
            .put("nonce", nonce)
            .toString()
        val raw = body.toByteArray(Charsets.UTF_8)
        val path = Secrets.path("p22")
        val canonical = canonical("POST", path, "", raw, ts, nonce)

        val response = runCatching {
            send(
                url = baseUrl + path,
                body = raw,
                headers = mapOf(
                    "X-Am-App" to APP_ID,
                    "X-Am-Sign" to hmac(APP_SECRET, canonical),
                ),
                contentType = "application/json; charset=utf-8",
            )
        }.getOrNull() ?: return false

        if (response.first !in 200..299) return false
        val json = runCatching { JSONObject(response.second.orEmpty()) }.getOrNull() ?: return false
        if (json.optInt("code", -1) != 0) return false
        val data = json.optJSONObject("data") ?: return false
        val newTicket = data.optString("ticket")
        val newKey = data.optString("sessionKey")
        if (newTicket.isEmpty() || newKey.isEmpty()) return false

        ticket = newTicket
        sessionKey = newKey
        prefs.edit().putString(KEY_TICKET, newTicket).putString(KEY_SESSION_KEY, newKey).apply()
        return true
    }

    /** 设备指纹：与后端 sec_device_hash 取的键保持一致，缺项直接不发。 */
    private fun deviceFingerprint(): Map<String, String> {
        val androidId = runCatching {
            Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull().orEmpty()
        return buildMap {
            if (androidId.isNotEmpty()) put("androidId", androidId)
            put("model", Build.MODEL.orEmpty())
            put("brand", Build.BRAND.orEmpty())
            put("manufacturer", Build.MANUFACTURER.orEmpty())
            put("device", Build.DEVICE.orEmpty())
            put("board", Build.BOARD.orEmpty())
            put("package", appContext.packageName)
            put("appId", APP_ID)
        }
    }

    // ---------------------------------------------------------------- 签名原语

    private fun canonical(
        method: String,
        path: String,
        query: String,
        body: ByteArray,
        ts: String,
        nonce: String,
    ): String = listOf(
        method.uppercase(),
        pathPrefix + path,
        sortedQuery(query),
        sha256Hex(body),
        ts,
        nonce,
    ).joinToString("\n")

    private fun sortedQuery(query: String): String {
        if (query.isEmpty()) return ""
        return query.split("&").sorted().joinToString("&")
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun hmac(secret: String, message: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(message.toByteArray(Charsets.UTF_8)).toHex()
    }

    private fun ByteArray.toHex(): String = buildString {
        for (byte in this@toHex) append(String.format("%02x", byte.toInt() and 0xFF))
    }

    private fun newNonce(): String {
        val bytes = ByteArray(8)
        SecureRandom().nextBytes(bytes)
        return bytes.toHex()
    }

    private fun nowSeconds(): String = (System.currentTimeMillis() / 1000L).toString()

    /** 只在真有调试器附着时为 true；开发者选项/USB 调试本身不会触发。 */
    /** 环境体检的别名（历史调用点沿用这个名字）：调试器 / 签名 / root / Frida 任一命中都返回 true。 */
    private fun isDebugged(): Boolean = !Integrity.isTrusted()

    private fun send(
        url: String,
        body: ByteArray,
        headers: Map<String, String>,
        contentType: String,
    ): Pair<Int, String?> {
        val connection = (URL(url).openConnection() as HttpURLConnection)
        return try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Content-Type", contentType)
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            code to stream?.bufferedReader()?.use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        /** keyId 与主密钥都在 [Secrets] 里加盐加密存放；服务端删掉该 keyId 即等于吊销这批客户端。 */
        val KEY_ID: String = Secrets.keyId
        val APP_SECRET: String = Secrets.appSecret
        const val APP_ID = "am"

        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 15_000
        const val USER_AGENT = "AMMusic/0.6.41 (Android)"
        const val KEY_TICKET = "ticket"
        const val KEY_SESSION_KEY = "session_key"
        val EMPTY_BODY = ByteArray(0)
    }
}
