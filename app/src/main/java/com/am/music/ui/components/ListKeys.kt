package com.am.music.ui.components

/**
 * 给 LazyColumn 用的「稳定且唯一」的行 key。
 *
 * 平时就是 [keyOf] 本身（顺序变化、增删都不变，animateItem 的位移动画才连得上）；
 * 只有当上游数据里真的塞进两条一模一样的 key 时，第二条之后才按出现序号加后缀。
 *
 * 为什么必须有这层：`IllegalArgumentException: Key "..." was already used` 是
 * LazyList 唯一的硬崩点，数据从网络来、从库里来、从磁盘快照来，任何一处漏了去重
 * 都会把整个页面炸掉。这里做最后一道兜底，宁可某一行失去位移动画，也不能崩。
 */
fun <T> List<T>.stableKeys(keyOf: (T) -> String): List<String> {
    val seen = HashMap<String, Int>(size)
    return map { item ->
        val base = keyOf(item)
        val count = seen.getOrPut(base) { 0 }
        seen[base] = count + 1
        if (count == 0) base else "$base#$count"
    }
}
