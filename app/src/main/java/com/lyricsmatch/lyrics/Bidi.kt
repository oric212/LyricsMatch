package com.lyricsmatch.lyrics

/**
 * Right-to-left detection (Hebrew, Arabic, Persian, …). Decided per song, so a Hebrew song
 * with an English line or two keeps one consistent layout.
 */
object Bidi {
    fun isRtl(text: String): Boolean = text.any {
        it in '֐'..'ࣿ' || it in 'יִ'..'﷿' || it in 'ﹰ'..'﻿'
    }

    /** True when at least a third of the non-empty lines contain right-to-left script. */
    fun isRtlSong(lines: List<String>): Boolean {
        val real = lines.filter { it.isNotBlank() }
        return real.isNotEmpty() && real.count(::isRtl) * 3 >= real.size
    }
}
