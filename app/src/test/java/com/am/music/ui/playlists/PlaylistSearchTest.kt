package com.am.music.ui.playlists

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistSearchTest {

    /** 用 [标题, 歌手, 专辑] 三元组冒充 Song，走 UI 里同一个泛型过滤函数。 */
    private val songs = listOf(
        listOf("晴天", "周杰伦", "叶惠美"),
        listOf("七里香", "周杰伦", "七里香"),
        listOf("夜曲", "周杰倫", "十一月的萧邦"),
        listOf("Bohemian Rhapsody", "Queen", "A Night at the Opera"),
        listOf("Lose Yourself", "Eminem", "8 Mile"),
    )

    private fun filter(query: String) = filterSongs(songs, query) { it }

    @Test
    fun blankQueryKeepsEverything() {
        assertEquals(5, filter("").size)
        assertEquals(5, filter("   ").size)
        assertEquals(5, filter("\u3000").size)
    }

    @Test
    fun matchesTitlePartially() {
        assertEquals(listOf("晴天"), filter("晴").map { it[0] })
        assertEquals(listOf("七里香"), filter("里香").map { it[0] })
    }

    @Test
    fun matchesArtistAndAlbum() {
        assertEquals(listOf("晴天", "七里香"), filter("周杰伦").map { it[0] })
        assertEquals(listOf("夜曲"), filter("萧邦").map { it[0] })
        assertEquals(listOf("Bohemian Rhapsody"), filter("opera").map { it[0] })
    }

    @Test
    fun caseInsensitiveAndLatin() {
        assertEquals(listOf("Bohemian Rhapsody"), filter("QUEEN").map { it[0] })
        assertEquals(listOf("Lose Yourself"), filter("8 mile").map { it[0] })
    }

    @Test
    fun everyTokenMustHitAcrossFields() {
        // 一个词落在歌手、一个词落在歌名
        assertEquals(listOf("晴天"), filter("周杰伦 晴").map { it[0] })
        assertEquals(listOf("Lose Yourself"), filter("eminem lose").map { it[0] })
        // 两个词各自命中不同歌曲时不应有结果
        assertTrue(filter("晴天 夜曲").isEmpty())
    }

    @Test
    fun trimsExtraSpaces() {
        assertEquals(listOf("晴天"), filter("  周杰伦   晴  ").map { it[0] })
    }

    @Test
    fun noMatchReturnsEmpty() {
        assertTrue(filter("完全不存在的歌").isEmpty())
        assertTrue(filter("zzzz").isEmpty())
    }

    @Test
    fun tokensAndMatchHelpers() {
        assertEquals(listOf("周杰伦", "晴"), searchTokens("  周杰伦   晴 "))
        assertTrue(matchesTokens(listOf("标题", "歌手"), emptyList()))
        assertFalse(matchesTokens(listOf("标题"), listOf("歌手")))
    }
}
