package com.lyricsmatch.lyrics

/** Parses LRC text like "[01:23.45]Hello" into time-sorted lines. */
object LrcParser {
    private val timeTag = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val offsetTag = Regex("""\[offset:\s*([+-]?\d+)\s*]""", RegexOption.IGNORE_CASE)

    fun parse(lrc: String): List<LyricLine> {
        var offset = 0L
        val out = mutableListOf<LyricLine>()
        for (raw in lrc.lineSequence()) {
            val line = raw.trim()
            val offsetMatch = offsetTag.find(line)
            if (offsetMatch != null) {
                offset = offsetMatch.groupValues[1].toLong()
                continue
            }
            // A line may carry several timestamps: "[00:10.00][01:20.00]Chorus"
            var rest = line
            val times = mutableListOf<Long>()
            while (true) {
                val m = timeTag.find(rest)?.takeIf { it.range.first == 0 } ?: break
                val (min, sec, frac) = m.destructured
                val fracMs = when (frac.length) {
                    0 -> 0L
                    1 -> frac.toLong() * 100
                    2 -> frac.toLong() * 10
                    else -> frac.toLong()
                }
                times += min.toLong() * 60_000 + sec.toLong() * 1_000 + fracMs
                rest = rest.substring(m.range.last + 1)
            }
            val text = rest.trim()
            // Positive LRC offset means lyrics should show earlier.
            times.forEach { out += LyricLine((it - offset).coerceAtLeast(0), text) }
        }
        return out.sortedBy { it.timeMs }
    }

    /** Index of the line being sung at [positionMs], or -1 before the first line. */
    fun indexAt(lines: List<LyricLine>, positionMs: Long): Int {
        var lo = 0
        var hi = lines.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].timeMs <= positionMs) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }
}
