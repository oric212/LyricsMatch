using System.Text.RegularExpressions;

namespace LyricsMatch.Windows.Core;

/// <summary>Parses LRC text like "[01:23.45]Hello" into time-sorted lines. Mirrors the Android parser.</summary>
public static partial class LrcParser
{
    [GeneratedRegex(@"^\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?\]")]
    private static partial Regex TimeTag();

    [GeneratedRegex(@"\[offset:\s*([+-]?\d+)\s*\]", RegexOptions.IgnoreCase)]
    private static partial Regex OffsetTag();

    public static List<LyricLine> Parse(string lrc)
    {
        long offset = 0;
        var result = new List<LyricLine>();
        foreach (var raw in lrc.Split('\n'))
        {
            var line = raw.Trim();
            var off = OffsetTag().Match(line);
            if (off.Success)
            {
                offset = long.Parse(off.Groups[1].Value);
                continue;
            }

            var rest = line;
            var times = new List<long>();
            while (TimeTag().Match(rest) is { Success: true } m)
            {
                var frac = m.Groups[3].Value;
                long fracMs = frac.Length switch
                {
                    0 => 0,
                    1 => long.Parse(frac) * 100,
                    2 => long.Parse(frac) * 10,
                    _ => long.Parse(frac),
                };
                times.Add(long.Parse(m.Groups[1].Value) * 60_000 + long.Parse(m.Groups[2].Value) * 1_000 + fracMs);
                rest = rest[m.Length..];
            }

            var text = rest.Trim();
            foreach (var t in times)
                result.Add(new LyricLine(TimeSpan.FromMilliseconds(Math.Max(0, t - offset)), text));
        }
        return result.OrderBy(l => l.Time).ToList();
    }

    /// <summary>Index of the line being sung at <paramref name="position"/>, or -1 before the first line.</summary>
    public static int IndexAt(IReadOnlyList<LyricLine> lines, TimeSpan position)
    {
        int lo = 0, hi = lines.Count - 1, ans = -1;
        while (lo <= hi)
        {
            int mid = (lo + hi) >>> 1;
            if (lines[mid].Time <= position) { ans = mid; lo = mid + 1; }
            else hi = mid - 1;
        }
        return ans;
    }
}
