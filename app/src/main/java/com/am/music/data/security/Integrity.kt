package com.am.music.data.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Debug
import java.io.File
import java.security.MessageDigest

/**
 * 运行环境体检。
 *
 * 0.6.14 的逆向报告写得很直白：护甲只有「调试器检测」两处，hook 掉
 * `Debug.isDebuggerConnected()` 就全绕过去了。这里补上四类真正有区分度的检查：
 *
 * 1. **签名校验**：APK 签名证书的 SHA-256 必须与自己签发的一致——重打包（改代码、
 *    塞模块、修 resources.arsc）必然换签名，这一条直接否掉；
 * 2. **调试器**：`Debug.isDebuggerConnected()`（保留原逻辑）；
 * 3. **root / Magisk**：常见 su 二进制、Magisk 目录、`test-keys`；
 * 4. **Frida / Xposed / 模拟器**：frida-server 常见落点、Xposed 类能否加载、典型模拟器指纹。
 *
 * 取舍：这些全是启发式，拿得到 root 的人总能继续对抗（改包内判断、hook 系统 API）。
 * 所以设计成「命中就不给签名」而不是「命中就崩」——服务端才是最终判决：
 * 没有签名就没有票据，接口一律 401。误判面收窄到只认明确的文件名/包名/属性。
 */
internal object Integrity {

    /**
     * 自己签发的那张证书的 SHA-256，换 keystore 时必须同步这里，否则全端自锁：
     * 签名对不上 → 判定不可信 → 请求不签名 → 服务端 401。
     *
     * 2026-11-14 起换为用户提供的 `New_Key_20260627_132202`（RSA 2048，自签名，
     * 有效期 2026-06-27 至 2027-06-27）。旧值 `a3b4926105c8674f…92858b` 已不再使用。
     */
    private const val EXPECTED_CERT_SHA256 =
        "33a8bef95a2c25ef003e49ba27a63fd79e136671d58bb405a80c917db301df9c"

    /** 由 AppContainer 在启动时注入，签名校验需要拿包信息。 */
    var appContext: Context? = null

    private var cached: Boolean? = null

    /** 环境是否可信；只算一次，别在每次请求里都遍历文件系统。 */
    fun isTrusted(): Boolean {
        cached?.let { return it }
        val value = !Debug.isDebuggerConnected() && signatureOk() && !rooted() && !instrumented()
        cached = value
        return value
    }

    /** 签名证书比对。 */
    private fun signatureOk(): Boolean = try {
        val context = appContext ?: return true
        val signatures = if (Build.VERSION.SDK_INT >= 28) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNING_CERTIFICATES,
            ).signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNATURES,
            ).signatures
        }
        val cert = signatures?.firstOrNull()?.toByteArray() ?: return false
        val digest = MessageDigest.getInstance("SHA-256").digest(cert)
        val hex = buildString { digest.forEach { append(String.format("%02x", it.toInt() and 0xFF)) } }
        hex == EXPECTED_CERT_SHA256
    } catch (e: Exception) {
        false
    }

    /** root / Magisk 的常见痕迹。只查固定路径，不主动执行 su。 */
    private fun rooted(): Boolean {
        val markers = listOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
            "/data/local/su", "/data/local/bin/su", "/data/adb/magisk",
            "/data/adb/modules", "/system/app/Superuser.apk",
        )
        if (markers.any { File(it).exists() }) return true
        return Build.TAGS?.contains("test-keys") == true
    }

    /** Frida / Xposed / 模拟器。 */
    private fun instrumented(): Boolean {
        val fridaMarkers = listOf(
            "/data/local/tmp/frida-server",
            "/data/local/tmp/re.frida.server",
            "/data/local/tmp/frida",
        )
        if (fridaMarkers.any { File(it).exists() }) return true

        // Xposed / LSPosed 把类挂进 bootclasspath，能直接 forName 到
        for (name in listOf("de.robv.android.xposed.XposedBridge", "de.robv.android.xposed.XC_MethodHook")) {
            if (runCatching { Class.forName(name) }.isSuccess) return true
        }

        val fingerprint = Build.FINGERPRINT.lowercase()
        val model = Build.MODEL.lowercase()
        return fingerprint.startsWith("generic") || fingerprint.contains("vbox") ||
            fingerprint.contains("emulator") || model.contains("sdk_gphone") ||
            model.contains("emulator")
    }
}
