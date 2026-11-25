package com.am.music.data.cover

/**
 * 封面地址「取最高分辨率」的唯一规则点。
 *
 * 放在这里而不是散在各处拼串，是因为两个平台的规则都是**实测出来的**，不是猜的：
 *
 * 1. 腾讯 `y.qq.com / y.gtimg.cn` 的专辑图地址形如
 *    `https://y.gtimg.cn/music/photo_new/T002R300x300M000<albummid>.jpg`。
 *    同一张专辑逐档探测（2026-11-21，albummid=0014Y1DT4406OO）：
 *    150→12,387 B、300→40,783 B、500→94,286 B、800→189,996 B、1200→269,690 B；
 *    `1500x1500` 返回的还是 1200 那份字节（说明源图就是 1200），`2000x2000` 直接 404。
 *    **所以 QQ 的最高档是 1200x1200**，低于它的地址一律抬到这一档。
 *
 * 2. 网易 `p*.music.126.net` 的地址靠 `?param=300y300` 这类查询参数降采样，
 *    服务端有的接口会主动拼 `param=300y300`（`getPicUrl($pic, 300)`）。
 *    **把 `param` 去掉就是原图**，不需要换域名也不需要额外接口。
 *
 * 只改「本来就是图片地址」的字符串，其它平台的地址原样返回，避免误伤。
 */
object CoverUrls {

    /** QQ 专辑图的最高档边长（源图实测就是 1200）。 */
    const val QQ_BEST = 1200

    private val QQ_SIZE = Regex("T002R(\\d+)x(\\d+)M000")

    /**
     * 返回同一张图的最大尺寸地址；拿不到更高清的就原样返回。
     *
     * 入参为空 → 返回 null（调用方本来就用可空）。
     */
    fun best(url: String?): String? {
        if (url.isNullOrBlank()) return null
        if (!url.startsWith("http")) return url

        // 网易：去掉尺寸参数即原图
        if (url.contains("music.126.net") && url.contains("param=")) {
            return dropSizeParam(url)
        }

        // 腾讯：把 T002R{n}x{n}M000 抬到 1200
        if (QQ_SIZE.containsMatchIn(url)) {
            return QQ_SIZE.replace(url) { match ->
                val side = match.groupValues[1].toIntOrNull() ?: 0
                if (side >= QQ_BEST) match.value else "T002R${QQ_BEST}x${QQ_BEST}M000"
            }
        }
        return url
    }

    /** 去掉 `param=` 这个查询参数，其它参数（如果有）保留。 */
    private fun dropSizeParam(url: String): String {
        val base = url.substringBefore('?')
        val query = url.substringAfter('?', "")
        if (query.isEmpty()) return base
        val keep = query.split('&')
            .filter { it.isNotEmpty() && !it.startsWith("param=") }
        return if (keep.isEmpty()) base else base + "?" + keep.joinToString("&")
    }
}
