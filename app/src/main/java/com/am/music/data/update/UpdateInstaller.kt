package com.am.music.data.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.am.music.BuildConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * 应用内更新：**自己下载 APK，再交给系统安装器**，全程不出 App、不跳浏览器。
 *
 * 下载地址有两种形态：
 * - **GitHub Releases**（[UpdateInfo.assetId] > 0）：先带 token 请求
 *   `api.github.com/.../releases/assets/{id}`，拿到 302 后再去预签名地址取包。
 *   **重定向那一步必须去掉 Authorization**——S3 预签名只认 query 里的签名，
 *   同时带 Bearer 会被判「两种鉴权机制冲突」直接 400。
 * - **普通直链**：老的 version.json 那条路，直接 GET。
 *
 * 下完不直接装，先验三件事：包名、版本比当前大、**签名指纹等于官方那份**。
 * 前两条防下错包，第三条防投毒——更新链路是唯一能把任意代码落到用户机器上的入口，
 * 光靠 HTTPS 不够（仓库 token 泄露、release 被改都会走到这里）。
 */
object UpdateInstaller {

    private const val DIR = "update"
    private const val FILE = "AMMusic-update.apk"
    private const val UA = "AMMusic/0.6.61 (Android)"
    private const val MIN_APK_BYTES = 1_000_000L

    sealed interface Download {
        data class Done(val file: File) : Download
        data class Failed(val message: String) : Download
    }

    sealed interface Install {
        /** 已拉起系统安装器。 */
        data object Launched : Install

        /** 系统里没开「允许安装未知应用」。 */
        data object NeedPermission : Install

        data class Failed(val message: String) : Install
    }

    /** 下载并写出进度；[onProgress] 的 percent 在长度未知时为 -1。 */
    suspend fun download(
        context: Context,
        info: UpdateInfo,
        onProgress: (percent: Int, downloaded: Long, total: Long) -> Unit,
    ): Download = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, DIR).apply { if (!exists()) mkdirs() }
        val target = File(dir, FILE)
        runCatching { if (target.exists()) target.delete() }

        var connection: HttpURLConnection? = null
        try {
            connection = open(info)
            if (connection.responseCode !in 200..299) {
                return@withContext Download.Failed("更新源返回 ${connection.responseCode}，稍后再试")
            }
            var total = connection.contentLengthLong
            if (total <= 0) total = info.assetSize
            var received = 0L
            var lastPercent = -2
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        // 用户取消（关掉弹窗）时协程会被 cancel，这里主动收尾，别留半截文件
                        if (!coroutineContext.isActive) {
                            runCatching { target.delete() }
                            return@withContext Download.Failed("已取消下载")
                        }
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        received += read
                        val percent = if (total > 0) ((received * 100) / total).toInt() else -1
                        if (percent != lastPercent) {
                            lastPercent = percent
                            onProgress(percent, received, total)
                        }
                    }
                }
            }
            if (total > 0 && received != total) {
                runCatching { target.delete() }
                return@withContext Download.Failed("下载不完整（${received}/${total} 字节）")
            }
            if (target.length() < MIN_APK_BYTES) {
                runCatching { target.delete() }
                return@withContext Download.Failed("下载到的不是完整安装包")
            }
            val problem = verify(context, target, info)
            if (problem != null) {
                runCatching { target.delete() }
                return@withContext Download.Failed(problem)
            }
            Download.Done(target)
        } catch (e: Exception) {
            runCatching { target.delete() }
            Download.Failed(e.message?.takeIf { it.isNotBlank() } ?: "下载失败，检查网络后再试")
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    /** 按来源打开一条「已经走到 200 响应体入口」的连接。 */
    private fun open(info: UpdateInfo): HttpURLConnection {
        if (info.fromGitHub && info.assetId > 0) {
            val api = "https://api.github.com/repos/" + BuildConfig.UPDATE_REPO +
                "/releases/assets/" + info.assetId
            val first = (URL(api).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("Accept", "application/octet-stream")
                setRequestProperty("User-Agent", UA)
                if (BuildConfig.UPDATE_TOKEN.isNotBlank()) {
                    setRequestProperty("Authorization", "Bearer " + BuildConfig.UPDATE_TOKEN)
                }
            }
            val code = first.responseCode
            if (code in 300..399) {
                val location = first.getHeaderField("Location")
                first.disconnect()
                if (!location.isNullOrBlank()) {
                    return (URL(location).openConnection() as HttpURLConnection).apply {
                        instanceFollowRedirects = true
                        connectTimeout = 15_000
                        readTimeout = 30_000
                        setRequestProperty("User-Agent", UA)
                        setRequestProperty("Accept", "application/vnd.android.package-archive,*/*")
                    }
                }
            }
            return first
        }
        return (URL(info.url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept", "application/vnd.android.package-archive,*/*")
            setRequestProperty("Cache-Control", "no-store")
        }
    }

    /**
     * 落装前的最后一道闸：包名、版本、签名指纹三项全对才返回 null。
     * 任何一项不对都把文件删掉，宁可不更新，也不让来路不明的包进安装器。
     */
    private fun verify(context: Context, file: File, info: UpdateInfo): String? {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        val archive = pm.getPackageArchiveInfo(file.absolutePath, flags)
            ?: return "下载到的不是可安装的包"
        if (archive.packageName != context.packageName) {
            return "包名对不上（${archive.packageName}）"
        }
        if (archive.longVersionCode <= BuildConfig.VERSION_CODE.toLong()) {
            return "下载到的不是更新版本（${archive.longVersionCode}）"
        }
        val certs: List<ByteArray> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            archive.signingInfo?.apkContentsSigners?.map { it.toByteArray() } ?: emptyList()
        } else {
            @Suppress("DEPRECATION")
            archive.signatures?.map { it.toByteArray() } ?: emptyList()
        }
        if (certs.isEmpty()) return "安装包没有签名"
        val digest = MessageDigest.getInstance("SHA-256").digest(certs[0])
        val hex = digest.joinToString("") { "%02x".format(it) }
        if (!hex.equals(BuildConfig.UPDATE_SIGN_SHA256, ignoreCase = true)) {
            return "签名不是官方那份，已拦下"
        }
        return null
    }

    /** 拉起系统安装器；没授权时返回 [Install.NeedPermission]，不抛异常。 */
    fun install(context: Context, file: File): Install {
        if (!file.exists() || file.length() < MIN_APK_BYTES) {
            return Install.Failed("安装包不在了，请重新下载")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            return Install.NeedPermission
        }
        return try {
            val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            Install.Launched
        } catch (e: Exception) {
            Install.Failed("打不开系统安装器：${e.message ?: "未知原因"}")
        }
    }

    /** 跳到「允许安装未知应用」开关页。 */
    fun openInstallPermission(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + context.packageName),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /** 兜底：系统安装器实在打不开时，才让用户去浏览器下（私有仓库这条会 404，仅对直链有意义）。 */
    fun openInBrowser(context: Context, info: UpdateInfo) {
        val link = if (info.fromGitHub) {
            "https://github.com/" + BuildConfig.UPDATE_REPO + "/releases"
        } else {
            info.url
        }
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(link))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
