namespace LyricsMatch.Windows.Core;

/// <summary>
/// Right-to-left detection (Hebrew, Arabic, Persian, …). Decided per song, so a Hebrew song
/// with an English line or two keeps one consistent layout. Mirrors the Android logic.
/// </summary>
public static class Bidi
{
    public static bool IsRtl(string text) => text.Any(c =>
        c is >= '֐' and <= 'ࣿ' or >= 'יִ' and <= '﷿' or >= 'ﹰ' and <= '﻿');

    /// <summary>True when at least a third of the non-empty lines contain right-to-left script.</summary>
    public static bool IsRtlSong(IEnumerable<string> lines)
    {
        var real = lines.Where(l => !string.IsNullOrWhiteSpace(l)).ToList();
        return real.Count > 0 && real.Count(IsRtl) * 3 >= real.Count;
    }
}
