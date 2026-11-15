using System.IO;
using System.Net.Http;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace LyricsMatch.Windows.Core;

/// <summary>Fetches time-synced lyrics from LRCLIB (https://lrclib.net), with a memory + disk cache.</summary>
public sealed class LyricsClient
{
    private static readonly HttpClient Http = CreateHttp();
    private readonly Dictionary<string, LyricsResult> _memory = new();
    private readonly string _cacheDir;

    public LyricsClient()
    {
        // Portable: cache lives next to the app, nothing is written elsewhere.
        _cacheDir = Path.Combine(AppContext.BaseDirectory, "lyrics-cache");
    }

    private static HttpClient CreateHttp()
    {
        var http = new HttpClient { BaseAddress = new Uri("https://lrclib.net/api/"), Timeout = TimeSpan.FromSeconds(10) };
        http.DefaultRequestHeaders.UserAgent.ParseAdd("LyricsMatch/1.0 (personal Windows app)");
        return http;
    }

    public async Task<LyricsResult> LoadAsync(NowPlaying np, bool forceRefresh, CancellationToken ct)
    {
        var key = np.TrackKey;
        var file = Path.Combine(_cacheDir, Hash(key) + ".txt");
        if (forceRefresh)
        {
            lock (_memory) _memory.Remove(key);
            TryDelete(file);
        }

        lock (_memory)
            if (_memory.TryGetValue(key, out var hit)) return hit;

        if (ReadCache(file) is { } cached)
        {
            lock (_memory) _memory[key] = cached;
            return cached;
        }

        try
        {
            var (result, blob) = await QueryAsync(np, ct);
            lock (_memory) _memory[key] = result;
            if (blob != null)
            {
                try { Directory.CreateDirectory(_cacheDir); await File.WriteAllTextAsync(file, blob, ct); }
                catch (IOException) { /* cache is best-effort */ }
                catch (UnauthorizedAccessException) { }
            }
            return result;
        }
        catch (OperationCanceledException) when (!ct.IsCancellationRequested)
        {
            return LyricsResult.Error("The lyrics service took too long to answer");
        }
        catch (HttpRequestException)
        {
            return LyricsResult.Error("Couldn't reach the lyrics service");
        }
        catch (JsonException)
        {
            return LyricsResult.Error("Lyrics service sent a bad response");
        }
    }

    // ---- network ----

    private static async Task<(LyricsResult, string?)> QueryAsync(NowPlaying np, CancellationToken ct)
    {
        var durationSec = (long)np.Duration.TotalSeconds;
        var primary = PrimaryArtist(np.Artist);
        var candidates = new List<JsonElement>();

        // 1) Exact signature match.
        if (!string.IsNullOrWhiteSpace(np.Album) && durationSec > 0)
        {
            var exact = await GetAsync("get", ct,
                ("track_name", np.Title), ("artist_name", np.Artist),
                ("album_name", np.Album!), ("duration", durationSec.ToString()));
            if (exact is { ValueKind: JsonValueKind.Object } o) candidates.Add(o);
        }

        // 2) Fuzzy search with progressively cleaned names.
        if (!candidates.Any(HasSynced))
        {
            var tries = new List<(string, string)> { (np.Title, np.Artist), (np.Title, primary), (CleanTitle(np.Title), primary) }
                .Distinct();
            foreach (var (title, artist) in tries)
            {
                var arr = await GetAsync("search", ct, ("track_name", title), ("artist_name", artist));
                if (arr is { ValueKind: JsonValueKind.Array } a) candidates.AddRange(a.EnumerateArray());
                if (candidates.Any(HasSynced)) break;
            }
        }

        return Pick(candidates, durationSec);
    }

    private static (LyricsResult, string?) Pick(List<JsonElement> candidates, long durationSec)
    {
        // Skip versions whose length is far off (live / extended edits would be out of sync).
        var fitting = durationSec > 0
            ? candidates.Where(c => !c.TryGetProperty("duration", out var d) || d.ValueKind != JsonValueKind.Number
                                    || Math.Abs(d.GetDouble() - durationSec) <= 5).ToList()
            : candidates;
        if (fitting.Count == 0) fitting = candidates;

        foreach (var c in fitting)
        {
            var lrc = Str(c, "syncedLyrics");
            if (lrc == null) continue;
            var lines = LrcParser.Parse(lrc);
            if (lines.Count > 0) return (new LyricsResult(LyricsKind.Synced, lines), "S\n" + lrc);
        }
        foreach (var c in fitting)
            if (Str(c, "plainLyrics") is { } plain) return (new LyricsResult(LyricsKind.Plain, Text: plain), "P\n" + plain);
        if (fitting.Any(c => c.TryGetProperty("instrumental", out var i) && i.ValueKind == JsonValueKind.True))
            return (LyricsResult.Instrumental, "I\n");
        return (LyricsResult.NotFound, null);
    }

    private static async Task<JsonElement?> GetAsync(string path, CancellationToken ct, params (string k, string v)[] query)
    {
        var qs = string.Join("&", query.Select(p => $"{p.k}={Uri.EscapeDataString(p.v)}"));
        using var resp = await Http.GetAsync($"{path}?{qs}", ct);
        if (resp.StatusCode == System.Net.HttpStatusCode.NotFound) return null;
        resp.EnsureSuccessStatusCode();
        var body = await resp.Content.ReadAsStringAsync(ct);
        using var doc = JsonDocument.Parse(body);
        return doc.RootElement.Clone();
    }

    // ---- helpers ----

    private static bool HasSynced(JsonElement c) => Str(c, "syncedLyrics") != null;

    private static string? Str(JsonElement c, string name) =>
        c.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.String && !string.IsNullOrWhiteSpace(v.GetString())
            ? v.GetString()
            : null;

    private static string PrimaryArtist(string artist) =>
        Regex.Split(artist, @",| & | feat\.| ft\.| x |;")[0].Trim();

    /// <summary>"Song (feat. X) - Remastered 2011" -> "Song"</summary>
    private static string CleanTitle(string title)
    {
        var t = Regex.Replace(title, @"\s*[(\[](feat\.|ft\.|with |from |remaster|live|radio edit|version)[^)\]]*[)\]]", "", RegexOptions.IgnoreCase);
        t = Regex.Replace(t, @"\s+-\s+.*(remaster|version|edit|live|mix).*$", "", RegexOptions.IgnoreCase);
        return t.Trim();
    }

    private static string Hash(string key) =>
        Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(key)))[..24];

    private static LyricsResult? ReadCache(string file)
    {
        try
        {
            if (!File.Exists(file)) return null;
            var text = File.ReadAllText(file);
            var nl = text.IndexOf('\n');
            var body = nl >= 0 ? text[(nl + 1)..] : "";
            return text.FirstOrDefault() switch
            {
                'S' when LrcParser.Parse(body) is { Count: > 0 } lines => new LyricsResult(LyricsKind.Synced, lines),
                'P' => new LyricsResult(LyricsKind.Plain, Text: body),
                'I' => LyricsResult.Instrumental,
                _ => null,
            };
        }
        catch (IOException) { return null; }
    }

    private static void TryDelete(string file)
    {
        try { File.Delete(file); } catch (IOException) { }
    }
}
