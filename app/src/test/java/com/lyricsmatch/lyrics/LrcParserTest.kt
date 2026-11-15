package com.lyricsmatch.lyrics

import org.junit.Assert.assertEquals
import org.junit.Test

class LrcParserTest {

    @Test
    fun parsesTimestampsAndSorts() {
        val lines = LrcParser.parse(
            """
            [ar:Someone]
            [00:12.50]Second
            [00:05.00]First
            [01:02.123]Third
            """.trimIndent()
        )
        assertEquals(listOf(5_000L, 12_500L, 62_123L), lines.map { it.timeMs })
        assertEquals(listOf("First", "Second", "Third"), lines.map { it.text })
    }

    @Test
    fun expandsRepeatedTimestamps() {
        val lines = LrcParser.parse("[00:10.00][00:30.00]Chorus")
        assertEquals(listOf(10_000L, 30_000L), lines.map { it.timeMs })
        assertEquals("Chorus", lines[1].text)
    }

    @Test
    fun appliesOffsetTag() {
        val lines = LrcParser.parse("[offset:500]\n[00:10.00]Hi")
        assertEquals(9_500L, lines.single().timeMs)
    }

    @Test
    fun keepsEmptyLinesAsGaps() {
        val lines = LrcParser.parse("[00:01.00]Hello\n[00:04.00]")
        assertEquals("", lines[1].text)
    }

    @Test
    fun indexAtFindsCurrentLine() {
        val lines = listOf(LyricLine(1_000, "a"), LyricLine(2_000, "b"), LyricLine(3_000, "c"))
        assertEquals(-1, LrcParser.indexAt(lines, 500))
        assertEquals(0, LrcParser.indexAt(lines, 1_000))
        assertEquals(1, LrcParser.indexAt(lines, 2_999))
        assertEquals(2, LrcParser.indexAt(lines, 99_000))
    }
}
