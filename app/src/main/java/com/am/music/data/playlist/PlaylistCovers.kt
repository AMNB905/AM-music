package com.am.music.data.playlist

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File

/**
 * 歌单自定义封面的落盘管理。
 *
 * 为什么要自己存一份：系统相册给的 `content://` 授权在重启/清理后就不可靠，直接把 Uri 记进库，
 * 过几天封面就白了。这里把选中的图**拷进应用私有目录**并缩到长边 1200px 的 JPEG
 * （实测手机相片 3~8MB，缩完 100~250KB），库里只存绝对路径，卸载 App 时随私有目录一起消失。
 *
 * 文件名带时间戳：Coil 的内存/磁盘缓存以 uri（这里是文件路径）为键，
 * 同名覆盖会继续显示旧图——换封面时必须是新路径，旧文件顺手删掉。
 */
object PlaylistCovers {

    private const val DIR = "playlist_covers"
    private const val MAX_PX = 1200
    private const val JPEG_QUALITY = 88

    /** 拷入并缩放；失败返回 null（调用方保持原封面，不动数据库）。 */
    fun save(context: Context, playlistId: Long, uri: Uri): String? = runCatching {
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        val tmp = File(dir, "tmp-$playlistId")
        val copied = context.contentResolver.openInputStream(uri)?.use { input ->
            tmp.outputStream().use { out -> input.copyTo(out) }
            true
        } ?: false
        if (!copied || tmp.length() == 0L) {
            tmp.delete()
            return null
        }

        // 先只读尺寸，按采样率解一次，避免 12MP 原图直接进内存
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(tmp.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            tmp.delete()
            return null
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
        }
        val decoded = BitmapFactory.decodeFile(tmp.absolutePath, options)
        tmp.delete()
        if (decoded == null) return null

        val scaled = shrink(decoded)
        clear(dir, playlistId)
        val out = File(dir, "playlist-$playlistId-${System.currentTimeMillis()}.jpg")
        out.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        if (scaled !== decoded) decoded.recycle()
        scaled.recycle()
        out.absolutePath
    }.getOrNull()

    /** 恢复默认：库里置空 + 删掉那张图。 */
    fun remove(path: String?) {
        if (path.isNullOrBlank()) return
        runCatching { File(path).delete() }
    }

    /** 删除某歌单名下所有封面文件（换封面、删歌单时清场）。 */
    fun clear(dir: File?, playlistId: Long) {
        dir?.listFiles()
            ?.filter { it.name.startsWith("playlist-$playlistId-") }
            ?.forEach { runCatching { it.delete() } }
    }

    private fun sampleSize(width: Int, height: Int): Int {
        var sample = 1
        var longest = maxOf(width, height)
        while (longest / 2 >= MAX_PX) {
            sample *= 2
            longest /= 2
        }
        return sample
    }

    private fun shrink(bitmap: Bitmap): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= MAX_PX) return bitmap
        val ratio = MAX_PX.toFloat() / longest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * ratio).toInt().coerceAtLeast(1),
            (bitmap.height * ratio).toInt().coerceAtLeast(1),
            true,
        )
    }
}
