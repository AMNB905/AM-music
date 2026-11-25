package com.am.music.data.lyrics

import org.junit.Assert.assertEquals
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
    fun keepsEmptyLinesAsBreaks() {
        val lines = LrcParser.parse("[00:01.00]a\n[00:05.00]\n[00:10.00]b")
        assertEquals(3, lines.size)
        assertEquals("", lines[1].text)
    }
}