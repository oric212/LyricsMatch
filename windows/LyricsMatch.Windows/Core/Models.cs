namespace LyricsMatch.Windows.Core;

/// <summary>Snapshot of the song a media app (Spotify, browser, ...) is playing.</summary>
public sealed record NowPlaying(
    string AppId,
    string Title,
    string Artist,
    string? Album,
    TimeSpan Duration,
    bool IsPlaying,
    TimeSpan Position,
    DateTimeOffset UpdatedAt,
    /// <summary>Album art image bytes from the player (same instance while unchanged).</summary>
    byte[]? Artwork = null)
{
    public string TrackKey => Artist + "\0" + Title;

    /// <summary>Players only report position occasionally, so extrapolate to now.</summary>
    public TimeSpan PositionNow()
    {
        if (!IsPlaying) return Position;
        var p = Position + (DateTimeOffset.Now - UpdatedAt);
        return Duration > TimeSpan.Zero && p > Duration ? Duration : p;
    }
}

public sealed record LyricLine(TimeSpan Time, string Text);

public enum LyricsKind { Idle, Loading, Synced, Plain, Instrumental, NotFound, Error }

public sealed record LyricsResult(LyricsKind Kind, IReadOnlyList<LyricLine>? Lines = null, string? Text = null)
{
    public static readonly LyricsResult Idle = new(LyricsKind.Idle);
    public static readonly LyricsResult Loading = new(LyricsKind.Loading);
    public static readonly LyricsResult NotFound = new(LyricsKind.NotFound);
    public static readonly LyricsResult Instrumental = new(LyricsKind.Instrumental);
    public static LyricsResult Error(string message) => new(LyricsKind.Error, Text: message);
}
