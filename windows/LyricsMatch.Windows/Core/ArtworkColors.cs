using System.IO;
using System.Windows.Media;
using System.Windows.Media.Imaging;

namespace LyricsMatch.Windows.Core;

/// <summary>Turns album-art bytes into an image plus a background tint. Mirrors the Android logic.</summary>
public static class ArtworkColors
{
    public static readonly Color Fallback = Color.FromRgb(0x1C, 0x18, 0x28);

    public static BitmapImage? Decode(byte[]? bytes, int? decodeWidth = null)
    {
        if (bytes == null) return null;
        try
        {
            var img = new BitmapImage();
            img.BeginInit();
            img.CacheOption = BitmapCacheOption.OnLoad;
            if (decodeWidth is { } w) img.DecodePixelWidth = w;
            img.StreamSource = new MemoryStream(bytes);
            img.EndInit();
            img.Freeze();
            return img;
        }
        catch (Exception)
        {
            return null;
        }
    }

    private static byte[]? _lastChecked;
    private static bool _lastWasPlaceholder;

    /// <summary>
    /// True for generic "no cover" images (e.g. Spotify's logo for local files): a logo made of
    /// a handful of flat colours, which real album covers almost never are.
    /// </summary>
    public static bool IsPlaceholder(byte[] bytes)
    {
        if (ReferenceEquals(bytes, _lastChecked)) return _lastWasPlaceholder;
        _lastChecked = bytes;
        _lastWasPlaceholder = false;
        var small = Decode(bytes, 24);
        if (small == null) return false;
        var bgra = new FormatConvertedBitmap(small, PixelFormats.Bgra32, null, 0);
        var px = new byte[bgra.PixelWidth * bgra.PixelHeight * 4];
        bgra.CopyPixels(px, bgra.PixelWidth * 4, 0);
        var colours = new HashSet<int>();
        for (int i = 0; i < px.Length; i += 4)
            colours.Add((px[i] >> 5) | (px[i + 1] >> 5 << 3) | (px[i + 2] >> 5 << 6)); // 3 bits per channel
        _lastWasPlaceholder = colours.Count <= 6;
        return _lastWasPlaceholder;
    }

    /// <summary>Saturation-weighted average colour, pushed to a dark, rich shade that keeps white text readable.</summary>
    public static Color Tint(byte[]? bytes)
    {
        var small = Decode(bytes, 12);
        if (small == null) return Fallback;

        var bgra = new FormatConvertedBitmap(small, PixelFormats.Bgra32, null, 0);
        int w = bgra.PixelWidth, h = bgra.PixelHeight;
        var px = new byte[w * h * 4];
        bgra.CopyPixels(px, w * 4, 0);

        double r = 0, g = 0, b = 0, total = 0;
        for (int i = 0; i < px.Length; i += 4)
        {
            var (_, s, v) = ToHsv(px[i + 2], px[i + 1], px[i]);
            var weight = s * v + 0.05;
            b += px[i] * weight; g += px[i + 1] * weight; r += px[i + 2] * weight; total += weight;
        }
        var (hue, sat, _) = ToHsv(r / total, g / total, b / total);
        return FromHsv(hue, Math.Min(sat * 1.25, 0.85), 0.38);
    }

    private static (double h, double s, double v) ToHsv(double r, double g, double b)
    {
        r /= 255; g /= 255; b /= 255;
        double max = Math.Max(r, Math.Max(g, b)), min = Math.Min(r, Math.Min(g, b)), d = max - min;
        double h = d == 0 ? 0
            : max == r ? 60 * (((g - b) / d) % 6)
            : max == g ? 60 * ((b - r) / d + 2)
            : 60 * ((r - g) / d + 4);
        if (h < 0) h += 360;
        return (h, max == 0 ? 0 : d / max, max);
    }

    private static Color FromHsv(double h, double s, double v)
    {
        double c = v * s, x = c * (1 - Math.Abs(h / 60 % 2 - 1)), m = v - c;
        var (r, g, b) = h switch
        {
            < 60 => (c, x, 0d),
            < 120 => (x, c, 0d),
            < 180 => (0d, c, x),
            < 240 => (0d, x, c),
            < 300 => (x, 0d, c),
            _ => (c, 0d, x),
        };
        return Color.FromRgb((byte)((r + m) * 255), (byte)((g + m) * 255), (byte)((b + m) * 255));
    }
}
