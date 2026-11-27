package com.am.music.data.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcParserTest {

    @Test
    fun parsesBasicLines() {
        val lines = LrcParser.parse("[00:01.00]hello\n[00:02.50]world")
        assertEquals(2, lines.size)
        assertEquals(1000L, lines[0].timeMs)
        assertEquals("hello", lines[0].text)
        assertEquals(2500L, lines[1].timeMs)
    }

    @Test
    fun expandsMultipleTimestamps() {
        val lines = LrcParser.parse("[00:20.00][00:50.00]chorus")
        assertEquals(listOf(20_000L, 50_000L), lines.map { it.timeMs })
        assertEquals(listOf("chorus", "chorus"), lines.map { it.text })
        // 「一句挂两个时间」不是逐字歌词，不能误判
        assertFalse(lines[0].hasWords)
        assertFalse(lines[1].hasWords)
    }

    @Test
    fun appliesOffset() {
        val lines = LrcParser.parse("[offset:+500]\n[00:10.00]line")
        assertEquals(9500L, lines[0].timeMs)
    }

    @Test
    fun handlesFractionDigits() {
        val lines = LrcParser.parse("[00:01.5]a\n[00:02.25]b\n[00:03.125]c\n[00:04]d")
        assertEquals(listOf(1500L, 2250L, 3125L, 4000L), lines.map { it.timeMs })
    }

    @Test
    fun skipsMetadataAndSorts() {
        val lines = LrcParser.parse("[ti:Title]\n[ar:Artist]\n[00:30.00]second\n[00:10.00]first")
        assertEquals(listOf("first", "second"), lines.map { it.text })
    }

    @Test
    fun clampsNegativeTime() {
        val lines = LrcParser.parse("[offset:1000]\n[00:00.50]early")
        assertEquals(0L, lines[0].timeMs)
    }

    @Test
    fun dropsEmptyLines() {
        val lines = LrcParser.parse("[00:01.00]a\n[00:05.00]\n[00:10.00]b")
        assertEquals(listOf("a", "b"), lines.map { it.text })
    }

    /** QQ 音源的逐字 LRC：每个字后面跟着自己的时间。 */
    @Test
    fun parsesWordTimings() {
        val lines = LrcParser.parse(
            "[00:28.95]故[00:29.40]事[00:29.65]的\n" +
                "[00:32.38]从[00:32.90]出[00:33.30]生\n" +
                "[00:35.87]童[00:36.30]年[00:36.70]的"
        )
        assertEquals(3, lines.size)

        val first = lines[0]
        assertEquals(28_950L, first.timeMs)
        assertEquals("故事的", first.text)
        assertTrue(first.hasWords)
        assertEquals(listOf(28_950L, 29_400L, 29_650L), first.words.map { it.timeMs })
        assertEquals(listOf("故", "事", "的"), first.words.map { it.text })
    }

    @Test
    fun wordTimingsSurviveSerialization() {
        val source = "[00:28.95]故[00:29.40]事[00:30.10]的\n[00:32.38]从[00:33.00]出"
        val parsed = LrcParser.parse(source)
        val again = LrcParser.parse(LrcParser.serialize(parsed))

        assertEquals(parsed.size, again.size)
        assertEquals(parsed[0].text, again[0].text)
        assertEquals(parsed[0].words.map { it.timeMs }, again[0].words.map { it.timeMs })
        assertEquals(parsed[0].words.map { it.text }, again[0].words.map { it.text })
        assertTrue(again[0].hasWords)
    }

    @Test
    fun plainLineHasNoWords() {
        val lines = LrcParser.parse("[00:33.00]一段只有整句时间的歌词")
        assertFalse(lines[0].hasWords)
        assertEquals(33_000L, lines[0].timeMs)
    }

    /** 网易云把一句话按呼吸切成两行，拼成一行后两段各自的时间必须留下。 */
    @Test
    fun mergedFragmentsKeepTheirTimes() {
        val lines = LrcParser.parse("[00:34.57]我们重复\n[00:37.64]沉默")
        assertEquals(1, lines.size)
        assertEquals("我们重复沉默", lines[0].text)
        assertEquals(34_570L, lines[0].timeMs)
        assertFalse(lines[0].hasWords)
        assertTrue(lines[0].hasTimeline)
        assertEquals(listOf(34_570L, 37_640L), lines[0].parts.map { it.timeMs })
        assertEquals(listOf("我们重复", "沉默"), lines[0].parts.map { it.text })
    }

    @Test
    fun mergedPartsSurviveSerialization() {
        val parsed = LrcParser.parse("[00:34.57]我们重复\n[00:37.64]沉默")
        val again = LrcParser.parse(LrcParser.serialize(parsed))
        assertEquals(parsed[0].text, again[0].text)
        assertEquals(parsed[0].parts.map { it.timeMs }, again[0].parts.map { it.timeMs })
    }
}
