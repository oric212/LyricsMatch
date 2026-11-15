using System.IO;
using Windows.Media.Control;
using Windows.Storage.Streams;

namespace LyricsMatch.Windows.Core;

/// <summary>
/// Watches Windows' system media sessions (the same ones the volume flyout shows).
/// Spotify desktop, browsers, Apple Music, etc. all publish here. Prefers Spotify when
/// several apps are playing. Events are raised on a background thread.
/// </summary>
public sealed class MediaWatcher
{
    private GlobalSystemMediaTransportControlsSessionManager? _manager;
    private readonly List<GlobalSystemMediaTransportControlsSession> _watched = new();
    private GlobalSystemMediaTransportControlsSession? _session;
    private string? _sessionId;
    private readonly SemaphoreSlim _refreshLock = new(1, 1);

    public event Action<NowPlaying?>? Changed;
    public NowPlaying? Current { get; private set; }

    private Timer? _poll;
    private byte[]? _art;

    private static async Task<byte[]?> ReadThumbnailAsync(IRandomAccessStreamReference? thumb)
    {
        if (thumb == null) return null;
        try
        {
            using var winStream = await thumb.OpenReadAsync();
            using var stream = winStream.AsStreamForRead();
            using var ms = new MemoryStream();
            await stream.CopyToAsync(ms);
            return ms.Length > 0 ? ms.ToArray() : null;
        }
        catch (Exception)
        {
            return null;
        }
    }

    public async Task StartAsync()
    {
        _manager = await GlobalSystemMediaTransportControlsSessionManager.RequestAsync();
        _manager.SessionsChanged += (_, _) => Resubscribe();
        _manager.CurrentSessionChanged += (_, _) => Pick();
        Resubscribe();
        // Safety net: some players occasionally skip change events, so re-check every second.
        // Unchanged snapshots compare equal and are ignored by the UI.
        _poll = new Timer(_ => Pick(), null, TimeSpan.FromSeconds(1), TimeSpan.FromSeconds(1));
    }

    public async Task SeekAsync(TimeSpan position)
    {
        if (_session == null) return;
        try { await _session.TryChangePlaybackPositionAsync(Math.Max(0, position.Ticks)); }
        catch (Exception) { /* app doesn't support seeking */ }
    }

    private void Resubscribe()
    {
        if (_manager == null) return;
        lock (_watched)
        {
            foreach (var s in _watched)
            {
                s.PlaybackInfoChanged -= OnPlaybackChanged;
                s.MediaPropertiesChanged -= OnSessionDataChanged;
                s.TimelinePropertiesChanged -= OnSessionDataChanged;
            }
            _watched.Clear();
            foreach (var s in _manager.GetSessions())
            {
                s.PlaybackInfoChanged += OnPlaybackChanged;
                s.MediaPropertiesChanged += OnSessionDataChanged;
                s.TimelinePropertiesChanged += OnSessionDataChanged;
                _watched.Add(s);
            }
        }
        Pick();
    }

    // Another app starting/stopping can change which session we follow.
    private void OnPlaybackChanged(GlobalSystemMediaTransportControlsSession s, PlaybackInfoChangedEventArgs e) => Pick();

    private void OnSessionDataChanged(GlobalSystemMediaTransportControlsSession s, object e)
    {
        if (s.SourceAppUserModelId == _sessionId) _ = RefreshAsync();
    }

    /// <summary>Playing Spotify &gt; anything playing &gt; the one we already follow &gt; Windows' current &gt; first.</summary>
    private void Pick()
    {
        if (_manager == null) return;
        var sessions = _manager.GetSessions();
        static bool Playing(GlobalSystemMediaTransportControlsSession s)
        {
            try { return s.GetPlaybackInfo().PlaybackStatus == GlobalSystemMediaTransportControlsSessionPlaybackStatus.Playing; }
            catch (Exception) { return false; }
        }
        static bool IsSpotify(GlobalSystemMediaTransportControlsSession s) =>
            s.SourceAppUserModelId.Contains("spotify", StringComparison.OrdinalIgnoreCase);

        var chosen = sessions.FirstOrDefault(s => Playing(s) && IsSpotify(s))
                     ?? sessions.FirstOrDefault(Playing)
                     ?? sessions.FirstOrDefault(s => s.SourceAppUserModelId == _sessionId)
                     ?? _manager.GetCurrentSession()
                     ?? sessions.FirstOrDefault();

        _session = chosen;
        _sessionId = chosen?.SourceAppUserModelId;
        _ = RefreshAsync();
    }

    private async Task RefreshAsync()
    {
        await _refreshLock.WaitAsync();
        try
        {
            var s = _session;
            NowPlaying? np = null;
            if (s != null)
            {
                try
                {
                    var props = await s.TryGetMediaPropertiesAsync();
                    var timeline = s.GetTimelineProperties();
                    var playback = s.GetPlaybackInfo();
                    if (props != null && !string.IsNullOrWhiteSpace(props.Title))
                    {
                        var updated = timeline.LastUpdatedTime;
                        // Some apps leave this unset; treat the report as "just now".
                        if (updated.Year < 2000 || updated > DateTimeOffset.Now) updated = DateTimeOffset.Now;
                        // Re-read the art each time: players can update it a moment after the title.
                        var art = await ReadThumbnailAsync(props.Thumbnail);
                        if (art == null || _art == null || !art.AsSpan().SequenceEqual(_art)) _art = art;
                        np = new NowPlaying(
                            s.SourceAppUserModelId,
                            props.Title,
                            string.IsNullOrWhiteSpace(props.Artist) ? props.AlbumArtist ?? "" : props.Artist,
                            string.IsNullOrWhiteSpace(props.AlbumTitle) ? null : props.AlbumTitle,
                            timeline.EndTime - timeline.StartTime,
                            playback.PlaybackStatus == GlobalSystemMediaTransportControlsSessionPlaybackStatus.Playing,
                            timeline.Position,
                            updated,
                            _art);
                    }
                }
                catch (Exception)
                {
                    // Session vanished mid-read; the next event will refresh.
                }
            }
            Current = np;
            Changed?.Invoke(np);
        }
        finally
        {
            _refreshLock.Release();
        }
    }
}
