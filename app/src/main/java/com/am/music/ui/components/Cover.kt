package com.am.music.ui.components

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.am.music.AMMusicApp
import com.am.music.data.cover.CoverCache
import java.io.File

/**
 * 封面模型：**本地文件优先，拿不到才退回网络地址**。
 *
 * 非 http 的 uri（`file://`、`content://`）原样交给 Coil —— 本地音乐、相册封面
 * 都不经过这一层，避免把一个 Uri 拆成字符串再拼回去。
 *
 * **这里的 `remember(key)` + `LaunchedEffect` 不能换成 `produceState(initialValue, key)`**：
 * `produceState` 内部是 `remember { mutableStateOf(initialValue) }`，`initialValue`
 * 只在第一次组合时算一次；换歌时 state 里还留着上一首的文件，而旧实现写的是
 * `if (value == null) value = ensure(url)` —— 值非空就再也不会去取新封面，
 * 于是播放页切歌后封面一直停在上一首（0.6.21 及之前就是这个表现）。
 * 现在每次换 key 都新建一份状态，并用 `peek()` 的内存命中做首帧，不闪音符图标。
 */
@Composable
fun rememberUriModel(uri: Uri?, prefetch: Boolean = true): Any? {
    val context = LocalContext.current
    return rememberHttpCover(context, uri?.toString()?.takeIf { it.startsWith("http") }, prefetch) ?: uri
}

/** 字符串版本：给只有 URL 的地方（歌词页衬底、歌单封面）用。 */
@Composable
fun rememberCoverModel(url: String?): Any? {
    val context = LocalContext.current
    return rememberHttpCover(context, url?.takeIf { it.startsWith("http") }) ?: url
}

@Composable
private fun rememberHttpCover(context: Context, http: String?, prefetch: Boolean = true): File? {
    val state = remember(http) {
        mutableStateOf(http?.let { cache(context)?.peek(it) })
    }
    LaunchedEffect(http) {
        if (http == null) {
            state.value = null
            return@LaunchedEffect
        }
        // 已经命中内存/磁盘就直接用；没命中才去下载并落盘
        val cache = cache(context) ?: return@LaunchedEffect
        // prefetch=false：列表缩略图只认已缓存的，没缓存就交给 Coil 按需拉（懒加载）。
        // 否则一屏滚动过去会为每一行都先下一张原图，既费流量也卡手。
        state.value = if (prefetch) cache.peek(http) ?: cache.ensure(http) else cache.peek(http)
    }
    return state.value
}

private fun cache(context: Context): CoverCache? =
    (context.applicationContext as? AMMusicApp)?.container?.coverCache
