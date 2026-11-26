package com.am.music.data.playlist

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.File

/**
 * 歌单自定义封面的落盘管理。
 *
 * 为什么自己存一份：相册给的 `content://` 授权在重启或清理后不可靠，直接把 Uri 记进库，
 * 过几天封面就白了。这里把最终确定的图**写进应用私有目录**，缩到长边 1200px 的 JPEG
 * （实测手机照片 3~8MB，缩完 100~250KB），库里只存绝对路径，卸载 App 时随私有目录消失。
 *
 * 输入可以是「相册里的图」，也可以是「视频抽出来的一帧」——两种来源在 UI 层都先裁成 1:1
 * （海报墙是方版），最后都落到 [saveBitmap] 一条流水线上，不会出现「图片方版、视频 16:9 被压扁」。
 *
 * 文件名带时间戳：Coil 以文件路径为缓存键，同名覆盖会继续显示旧图，换封面必须是新路径。
 */
object PlaylistCovers {

    private const val DIR = "playlist_covers"
    private const val MAX_PX = 1200
    private const val JPEG_QUALITY = 88

    /** 解一张相册图片，长边不超过 [maxPx]（按采样率解，避免 12MP 原图直接进内存）。 */
    fun decode(context: Context, uri: Uri, maxPx: Int = MAX_PX): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxPx)
        }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }.getOrNull()

    /**
     * 视频封面：抽第 1 秒那一帧（多数视频开头是黑场或片头，0 秒经常是一团黑）。
     * 抽不到就退回「最能代表这个视频的一帧」，再不行给最近的关键帧。
     */
    fun videoFrame(context: Context, uri: Uri, maxPx: Int = MAX_PX): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val candidate = retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.frameAtTime
                ?: retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: return null
            shrink(candidate, maxPx)
        } catch (e: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** 选择器网格里的视频缩略图：尺寸小一点，抽帧便宜得多。 */
    fun videoThumb(context: Context, uri: Uri, maxPx: Int = 320): Bitmap? =
        videoFrame(context, uri, maxPx)

    /** 把已经裁好的方版写进私有目录；失败返回 null（调用方保持原封面，不动数据库）。 */
    fun saveBitmap(context: Context, playlistId: Long, bitmap: Bitmap): String? = runCatching {
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        val scaled = shrink(bitmap, MAX_PX)
        clear(dir, playlistId)
        val out = File(dir, "playlist-$playlistId-${System.currentTimeMillis()}.jpg")
        out.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        if (scaled !== bitmap) scaled.recycle()
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

    private fun sampleSize(width: Int, height: Int, maxPx: Int): Int {
        var sample = 1
        var longest = maxOf(width, height)
        while (longest / 2 >= maxPx) {
            sample *= 2
            longest /= 2
        }
        return sample
    }

    private fun shrink(bitmap: Bitmap, maxPx: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxPx) return bitmap
        val ratio = maxPx.toFloat() / longest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * ratio).toInt().coerceAtLeast(1),
            (bitmap.height * ratio).toInt().coerceAtLeast(1),
            true,
        )
    }
}
