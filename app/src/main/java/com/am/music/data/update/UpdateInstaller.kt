package com.am.music.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * 应用内更新：**自己下载 APK，再交给系统安装器**，全程不出 App、不跳浏览器。
 *
 * 之前是 `Intent(ACTION_VIEW, url)`，等于把用户丢给浏览器：下载落进浏览器目录、
 * 有些浏览器还会给 apk 改名或拦一道「不安全」的提示，安装完还得自己回 App。
 * 这里换成两步：
 *
 * 1. [download] 用 HttpURLConnection 把 apk 下到 `cacheDir/update/AMMusic-update.apk`，
 *    边下边回调百分比（界面据此画进度条），下完校验总长度与文件大小——半截包直接判失败，
 *    不会被安装器报「解析包出错」；
 * 2. [install] 通过 FileProvider 把 `content://` 交给系统 PackageInstaller。
 *    Android 8+ 需要「允许安装未知应用」，没授权时返回 [Install.NeedPermission]，
 *    由界面把用户送到对应的系统开关（[openInstallPermission]），回来再点一次即可。
 *
 * 每次都重下：`version.json` 指向的包是不可变的，但缓存里的旧包可能是上一版，
 * 装上去等于降级，不如直接删掉重来（2.7MB，几秒）。
 */
object UpdateInstaller {

    private const val DIR = "update"
    private const val FILE = "AMMusic-update.apk"
    private const val UA = "AMMusic/0.6.41 (Android)"
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
            connection = (URL(info.url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept", "application/vnd.android.package-archive,*/*")
                setRequestProperty("Cache-Control", "no-store")
            }
            if (connection.responseCode !in 200..299) {
                return@withContext Download.Failed("服务器返回 ${connection.responseCode}，稍后再试")
            }
            val total = connection.contentLengthLong
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
            Download.Done(target)
        } catch (e: Exception) {
            runCatching { target.delete() }
            Download.Failed(e.message?.takeIf { it.isNotBlank() } ?: "下载失败，检查网络后再试")
        } finally {
            runCatching { connection?.disconnect() }
        }
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

    /** 兜底：系统安装器实在打不开时，才让用户去浏览器下。 */
    fun openInBrowser(context: Context, info: UpdateInfo) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(info.url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
