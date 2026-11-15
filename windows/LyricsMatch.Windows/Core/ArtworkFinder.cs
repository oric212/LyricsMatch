using System.Net.Http;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace LyricsMatch.Windows.Core;

/// <summary>
/// Finds album art when the music app doesn't provide any, using the free iTunes Search API
/// (https://performance-partners.apple.com/search-api). Only the artist and title are sent.
/// </summary>
public static class ArtworkFinder
{
    private static readonly HttpClient Http = new() { Timeout = TimeSpan.FromSeconds(8) };
    private static readonly Dictionary<string, byte[]?> Cache = new();

    public static async Task<byte[]?> FindAsync(string artist, string title, CancellationToken ct)
    {
        // Local files often put everything in the title: "Artist - Song".
        if (string.IsNullOrWhiteSpace(artist) && title.Split(" - ", 2) is [var a, var t])
        {
            artist = a;
            title = t;
        }
        var song = Normalize(CleanTitle(title));
        var term = $"{PrimaryArtist(artist)} {CleanTitle(title)}".Trim();
        if (term.Length == 0) return null;
        lock (Cache) if (Cache.TryGetValue(term, out var hit)) return hit;

        byte[]? result = null;
        try
        {
            var url = $"https://itunes.apple.com/search?entity=song&limit=5&term={Uri.EscapeDataString(term)}";
            using var doc = JsonDocument.Parse(await Http.GetStringAsync(url, ct));
            // Only accept the same song (another track's cover would be misleading),
            // preferring one by the same artist.
            var primary = PrimaryArtist(artist);
            var sameSong = doc.RootElement.GetProperty("results").EnumerateArray()
                .Where(r => r.TryGetProperty("trackName", out var n) && n.GetString() is { } name &&
                            Normalize(CleanTitle(name)) is var other && other.Length > 0 && song.Length > 0 &&
                            (other.Contains(song) || song.Contains(other)))
                .ToList();
            var best = sameSong.FirstOrDefault(r =>
                           primary.Length > 0 && r.TryGetProperty("artistName", out var an) &&
                           an.GetString()!.Contains(primary, StringComparison.OrdinalIgnoreCase))
                       is { ValueKind: JsonValueKind.Object } match ? match : sameSong.FirstOrDefault();
            if (best.ValueKind == JsonValueKind.Object &&
                best.TryGetProperty("artworkUrl100", out var art) && art.GetString() is { } small)
            {
                var big = small.Replace("100x100bb", "600x600bb");
                result = await Http.GetByteArrayAsync(big, ct);
            }
        }
        catch (Exception) when (!ct.IsCancellationRequested)
        {
            result = null; // offline / nothing found: keep the placeholder
        }
        lock (Cache) Cache[term] = result;
        return result;
    }

    /// <summary>Lower-case letters and digits only, for loose title comparison.</summary>
    private static string Normalize(string s) =>
        new string(s.Where(char.IsLetterOrDigit).Select(char.ToLowerInvariant).ToArray());

    private static string PrimaryArtist(string artist) =>
        Regex.Split(artist ?? "", @",| & | feat\.| ft\.| x |;")[0].Trim();

    private static string CleanTitle(string title) =>
        Regex.Replace(title ?? "", @"\s*[(\[](feat\.|ft\.|with )[^)\]]*[)\]]", "", RegexOptions.IgnoreCase).Trim();
}
