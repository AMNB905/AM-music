package com.am.music.data.security

import android.util.Base64
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * 接口地址、keyId、主密钥的「不明文」存放处。
 *
 * 现实前提：客户端代码只要被完整逆向，任何**随包分发**的密钥最终都能被取出，
 * 所以这里不假装能做到「不可提取」，只做两件有实际效果的事：
 *
 * 1. dex 里搜不到 `xiaorou.top`、那串 64 位密钥、`k2` 这类特征串——
 *    `strings`、jadx 全文搜索、崩溃日志里都拿不到现成答案；
 * 2. 解出来要跑一遍「按索引重排种子 → 逐条加盐派生密钥 → AES 解包」，
 *    必须读懂代码并动态跟进，成本远高于 grep。
 *
 * 真正的防线在服务端：票据绑定设备、激活有配额、可一键吊销、有审计日志。
 * 即密钥被挖出来也不等于能刷量——能不能用是服务端说了算。
 *
 * 每个 blob = base64(salt8 || AES-256-ECB/PKCS7(sha256(seed + salt)))
 */
internal object Secrets {

    private val SEED_PARTS = arrayOf(
        "b8e4", "3c7a", "d1f9", "56ab", "0e2d", "9f4c", "7a15", "e30b",
    )
    private val SEED_ORDER = intArrayOf(5, 2, 7, 0, 4, 1, 6, 3)

    private const val BASE_URL_BLOB = "YTdjM2UxNTm1t8hHRW1GRdX7fYDdi0zLfs9NwB7vfMl5ilqUovVzIg=="
    private const val KEY_ID_BLOB = "XYH3CivkkTdU+bk4XSvGyOJbZpFAXKX2"
    private const val PATHS_BLOB = "nwJMGb3hlWZR2C0EN+lsgFQ41OIG2H5OQZAJy4YYZrDUgtiH8hwe67khWanV6tuh7rkAlMKs4g/eXw4ZfCxjczy683f6dE4c4H21i/hqU83olyiEva007rcNFQim8EMf9jl4VcHihfx3+QKswX248eTn7X5UEnKwCDKJi+lMud7oGEPaXx4YIVhz9/Zh9w5qVOGJWdzEk+MM574afBNCeRqkPHySJaitdIEBC69OU5uqEqoWX097cXJg9YAYGPhjLMLaYqHFBN9qcs5wFz05m2ZC0BIO0eJzykRT4MH08tYnKV6iEaXB5Od6fcXsqfaU4EBTwLZymtWX1/VLCCM7NPQ5m6M/IbvU3oYoOrga89md6ancvPvjgQL6IM/u5UQAXVGhkIcTSwZ9n+54U+KB1saK1i5qZ8xQDpjMiixbVKDpyUwvmeYyMBWmHTeUUjuRVbbVrtT9QdGkUHVli4yxYKTr8MWd9I5Q8+3oTrqsS5MlmfBIyzeOaC5U9ekVK//2+9hn2XsSTxJHsxWlkKH3wulBzxDuQNK8WEibMGzYJCO1MSL5Z9on10foRdfp4ntMGtj8uV85JaThubLYovRPKKcPnvMPqiIyj8ZIMTJaN9OdWg3sbeauI/IaWfRr3XXT+q4I3lo5W35mBEPec43mO3vJ7BrlsB30x5BbGYhsXFZ+FLm/tqn3hAvr6Moj+RNZfIQ4A8mtb/HqFQsntwuDc0vWbLWHXDyW"
    private const val APP_SECRET_BLOB = "MeC3TYoSxW/GoiR7AVNOLWwbBv07YqeMTewjwpTCssd/vXRkRuubQ6LSUiGNfHJ/w+yTatl40/uw7NEPBX/YaSYPgGgQ40E/9LBqLJA+//pOiJ+GtTblnQ=="

    private val seed: ByteArray by lazy {
        val joined = buildString {
            for (index in SEED_ORDER) append(SEED_PARTS[index])
            append("|am|core")
        }
        MessageDigest.getInstance("SHA-256").digest(joined.toByteArray(Charsets.UTF_8))
    }

    val baseUrl: String by lazy { open(BASE_URL_BLOB) }
    val keyId: String by lazy { open(KEY_ID_BLOB) }
    val appSecret: String by lazy { open(APP_SECRET_BLOB) }

    /**
     * 接口路径表：`p01=qq/api/search` 这种「短 ID → 真实路径」的整表密文。
     *
     * 为什么连路径也藏：域名藏住了，但 `qq/api/search` 这种串留在 dex 里，
     * 逆向的人只要拿到域名就能把整套接口拼出来。改成表之后，dex 里只剩 `p01` 这类
     * 无语义短键，真实路径只在运行期内存里出现（可读进程内存的人仍然能拿到，
     * 但那已经需要 ptrace / 内存 dump，不再是 `strings` 级别的成本）。
     *
     * **加新接口时**：往表里追加 `pNN=真实路径`，重新加密整表替换 [PATHS_BLOB]，
     * 再在调用处用 `Secrets.path("pNN")`。
     */
    private val paths: Map<String, String> by lazy {
        open(PATHS_BLOB)
            .lineSequence()
            .mapNotNull { line ->
                val at = line.indexOf('=')
                if (at <= 0) null else line.substring(0, at).trim() to line.substring(at + 1).trim()
            }
            .toMap()
    }

    fun path(id: String): String = paths[id] ?: id

    private fun open(blob: String): String {
        val raw = Base64.decode(blob, Base64.NO_WRAP)
        val salt = raw.copyOfRange(0, 8)
        val body = raw.copyOfRange(8, raw.size)
        val key = MessageDigest.getInstance("SHA-256").digest(seed + salt)
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
        return String(cipher.doFinal(body), Charsets.UTF_8)
    }
}
