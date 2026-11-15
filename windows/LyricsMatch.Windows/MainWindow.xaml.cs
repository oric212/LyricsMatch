using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Threading;
using LyricsMatch.Windows.Core;

namespace LyricsMatch.Windows;

public partial class MainWindow : Window
{
    private static readonly Brush ActiveBrush = Brushes.White;
    // High contrast for the line being sung; the rest steps back.
    private static readonly Brush PastBrush = new SolidColorBrush(Color.FromArgb(0x38, 0xFF, 0xFF, 0xFF));
    private static readonly Brush FutureBrush = new SolidColorBrush(Color.FromArgb(0x66, 0xFF, 0xFF, 0xFF));
    private static readonly System.Windows.Media.Effects.DropShadowEffect ActiveGlow = new()
    {
        Color = Colors.White, ShadowDepth = 0, BlurRadius = 28, Opacity = 0.22,
    };
    private const double InactiveScale = 0.86;
    /// <summary>Where the sung line sits, as a fraction of the lyrics area (just below the top fade).</summary>
    private const double ReadingLine = 0.16;

    private readonly MediaWatcher _media = new();
    private readonly LyricsClient _lyrics = new();
    private readonly DispatcherTimer _ticker = new(DispatcherPriority.Render) { Interval = TimeSpan.FromMilliseconds(50) };

    private NowPlaying? _track;
    private LyricsResult _result = LyricsResult.Idle;
    private CancellationTokenSource? _loadCts;
    private readonly List<TextBlock> _lineViews = new();
    private TextBlock? _introView;
    private readonly List<TextBlock> _plainViews = new();
    private DateTime _userScrollAt = DateTime.MinValue;
    private DateTime _plainScrollAt = DateTime.MinValue;
    private int _current = -2;
    private TimeSpan _offset = TimeSpan.Zero;

    // Smooth scrolling state
    private double _scrollTarget;
    private bool _animating;

    public MainWindow()
    {
        InitializeComponent();
        _ticker.Tick += (_, _) => Tick();
        SizeChanged += (_, _) => OnResized();
        SourceInitialized += (_, _) => UseDarkTitleBar();
        Loaded += async (_, _) =>
        {
            _media.Changed += np => Dispatcher.BeginInvoke(() => OnNowPlaying(np));
            await _media.StartAsync();
        };

        // Live equalizer from the PC's audio output; clicking the bar seeks.
        _spectrum.Start();
        Eq.Spectrum = _spectrum;
        Spectrum.Source = Eq;
        Spectrum.SeekRequested += async f =>
        {
            if (_track is { Duration.Ticks: > 0 } t) await _media.SeekAsync(t.Duration * f);
        };
        Eq.SeekRequested += async f =>
        {
            if (_track is { Duration.Ticks: > 0 } t) await _media.SeekAsync(t.Duration * f);
        };
        Closed += (_, _) => _spectrum.Dispose();

        // Manual scrolling pauses auto-follow for a few seconds.
        Scroller.PreviewMouseWheel += (_, _) => { _userScrollAt = DateTime.UtcNow; StopScrollAnimation(); };
        Scroller.PreviewTouchMove += (_, _) => { _userScrollAt = DateTime.UtcNow; StopScrollAnimation(); };
    }

    private readonly AudioSpectrum _spectrum = new();

    // ---------------------------------------------------------------- media

    private void OnNowPlaying(NowPlaying? np)
    {
        if (np == _track) return; // poll found nothing new
        var newSong = np?.TrackKey != _track?.TrackKey;
        _track = np;

        TitleText.Text = np?.Title ?? "Nothing playing";
        TitleText.FlowDirection = ArtistText.FlowDirection = FlowDirection.LeftToRight;
        ArtistText.Text = np?.Artist ?? "";
        UpdateArtwork();
        var noWordsNow = _result.Kind is LyricsKind.NotFound or LyricsKind.Instrumental;
        Eq.Visibility = np?.Duration > TimeSpan.Zero && !noWordsNow ? Visibility.Visible : Visibility.Collapsed;
        Eq.Playing = np?.IsPlaying == true;
        UpdateProgress();

        if (newSong)
        {
            _offset = TimeSpan.Zero;
            UpdateOffsetText();
            StartLoad(forceRefresh: false);
        }

        if (np?.IsPlaying == true) _ticker.Start();
        else { _ticker.Stop(); Tick(); }
    }

    private byte[]? _shownArt;
    private (string Key, byte[]? Bytes) _fetchedArt = ("", null);
    private string? _fetchingKey;

    /// <summary>Uses the player's art; if it has none (or only a generic logo), fetches a cover online.</summary>
    private void UpdateArtwork()
    {
        var np = _track;
        var art = np?.Artwork;
        if (art != null && ArtworkColors.IsPlaceholder(art)) art = null;
        if (art == null && np != null)
        {
            if (_fetchedArt.Key == np.TrackKey) art = _fetchedArt.Bytes;
            else if (_fetchingKey != np.TrackKey) _ = FetchArtworkAsync(np);
        }
        if (!ReferenceEquals(art, _shownArt)) ShowArtwork(art);
    }

    private async Task FetchArtworkAsync(NowPlaying np)
    {
        _fetchingKey = np.TrackKey;
        await Task.Delay(1500); // players sometimes send the art a moment after the title
        if (_track?.TrackKey != np.TrackKey) return;
        if (_track.Artwork is { } a && !ArtworkColors.IsPlaceholder(a)) return;
        var bytes = await ArtworkFinder.FindAsync(np.Artist, np.Title, CancellationToken.None);
        _fetchedArt = (np.TrackKey, bytes);
        if (_track?.TrackKey == np.TrackKey) UpdateArtwork();
    }

    private void ShowArtwork(byte[]? art)
    {
        _shownArt = art;
        var img = ArtworkColors.Decode(art, 180);
        ArtBorder.Background = img != null ? new ImageBrush(img) { Stretch = Stretch.UniformToFill } : null;
        var big = ArtworkColors.Decode(art, 400);
        BigArt.Background = big != null
            ? new ImageBrush(big) { Stretch = Stretch.UniformToFill }
            : new SolidColorBrush(Color.FromArgb(0x14, 0xFF, 0xFF, 0xFF));
        var tint = img != null ? ArtworkColors.Tint(art) : ArtworkColors.Fallback;
        TintStop.BeginAnimation(GradientStop.ColorProperty,
            new ColorAnimation(tint, TimeSpan.FromMilliseconds(900)) { EasingFunction = new QuadraticEase() });
        SetCaptionColor(tint);
    }

    private void UpdateProgress()
    {
        if (_track == null || _track.Duration <= TimeSpan.Zero) return;
        Eq.Progress = Math.Clamp(_track.PositionNow() / _track.Duration, 0, 1);
        Spectrum.Progress = Eq.Progress;
    }

    private async void StartLoad(bool forceRefresh)
    {
        _loadCts?.Cancel();
        var np = _track;
        if (np == null)
        {
            Show(LyricsResult.Idle);
            return;
        }

        var cts = _loadCts = new CancellationTokenSource();
        Show(LyricsResult.Loading);
        try
        {
            // Network trouble: keep retrying quietly while the same song plays.
            while (true)
            {
                var result = await _lyrics.LoadAsync(np, forceRefresh, cts.Token);
                if (cts.IsCancellationRequested || _track?.TrackKey != np.TrackKey) return;
                Show(result);
                if (result.Kind != LyricsKind.Error) return;
                await Task.Delay(TimeSpan.FromSeconds(8), cts.Token);
                forceRefresh = true;
            }
        }
        catch (OperationCanceledException) { }
    }

    // ---------------------------------------------------------------- rendering

    private void Show(LyricsResult result)
    {
        _result = result;
        _current = -2;
        LinesPanel.Children.Clear();
        _lineViews.Clear();

        var (message, hint) = result.Kind switch
        {
            LyricsKind.Idle => ("Play a song in Spotify or any music app", ""),
            LyricsKind.Loading => ("Finding lyrics…", ""),
            LyricsKind.Error => (result.Text ?? "Something went wrong", "Retrying automatically…"),
            _ => ((string?)null, ""),
        };
        MessagePanel.Visibility = message != null ? Visibility.Visible : Visibility.Collapsed;

        // No words to show: album art, song info and a live spectrum; the caption fades away.
        var noWords = result.Kind is LyricsKind.NotFound or LyricsKind.Instrumental;
        NoLyricsPanel.Visibility = noWords ? Visibility.Visible : Visibility.Collapsed;
        HeaderInfo.Visibility = noWords ? Visibility.Collapsed : Visibility.Visible;
        Eq.Visibility = noWords || _track is not { Duration.Ticks: > 0 } ? Visibility.Collapsed : Visibility.Visible;
        BigTitle.Text = _track?.Title ?? "";
        BigArtist.Text = _track?.Artist ?? "";
        NoLyricsCaption.Text = result.Kind == LyricsKind.Instrumental ? "Instrumental · enjoy the music" : "No lyrics for this song";
        NoLyricsCaption.BeginAnimation(OpacityProperty, noWords
            ? new DoubleAnimation(1, 0, TimeSpan.FromMilliseconds(1200)) { BeginTime = TimeSpan.FromSeconds(4) }
            : null);
        MessageText.Text = message ?? "";
        HintText.Text = hint;
        SyncBar.Visibility = result.Kind == LyricsKind.Synced ? Visibility.Visible : Visibility.Collapsed;

        _introView = null;
        _plainViews.Clear();
        var rtl = result.Kind switch
        {
            LyricsKind.Synced => Bidi.IsRtlSong(result.Lines!.Select(l => l.Text)),
            LyricsKind.Plain => Bidi.IsRtlSong(result.Text!.Split('\n')),
            _ => false,
        };
        // Mirrors the panel: lines align right and the grow animation anchors on the right.
        LinesPanel.FlowDirection = rtl ? FlowDirection.RightToLeft : FlowDirection.LeftToRight;
        if (result.Kind == LyricsKind.Synced)
        {
            // Intro marker: holds the reading position until the first line is sung.
            _introView = MakeLine("•  •  •", FutureBrush);
            LinesPanel.Children.Add(_introView);

            foreach (var line in result.Lines!)
            {
                var tb = MakeLine(string.IsNullOrWhiteSpace(line.Text) ? "♪" : line.Text, FutureBrush);
                tb.Cursor = Cursors.Hand;
                tb.ToolTip = "Jump here";
                var captured = line;
                tb.MouseLeftButtonUp += async (_, _) => await _media.SeekAsync(captured.Time - _offset);
                LinesPanel.Children.Add(tb);
                _lineViews.Add(tb);
            }
        }
        else if (result.Kind == LyricsKind.Plain)
        {
            // Lyrics without timing: clear label, line-by-line layout, verse gaps, auto-scroll.
            LinesPanel.Children.Add(new Border
            {
                Background = new SolidColorBrush(Color.FromArgb(0x1A, 0xFF, 0xFF, 0xFF)),
                CornerRadius = new CornerRadius(12),
                Padding = new Thickness(12, 5, 12, 5),
                HorizontalAlignment = HorizontalAlignment.Left,
                Margin = new Thickness(0, 0, 0, 22),
                Child = new TextBlock
                {
                    Text = "NOT SYNCED · SCROLLS WITH THE SONG",
                    Foreground = new SolidColorBrush(Color.FromArgb(0x99, 0xFF, 0xFF, 0xFF)),
                    FontSize = 11,
                    FontWeight = FontWeights.SemiBold,
                },
            });
            var plainBrush = new SolidColorBrush(Color.FromArgb(0xD1, 0xFF, 0xFF, 0xFF));
            foreach (var raw in result.Text!.Trim().Replace("\r", "").Split('\n'))
            {
                if (string.IsNullOrWhiteSpace(raw))
                {
                    LinesPanel.Children.Add(new Border { Height = 18 });
                    continue;
                }
                var tb = MakeLine(raw.Trim(), plainBrush);
                tb.FontWeight = FontWeights.SemiBold;
                tb.RenderTransform = Transform.Identity;
                tb.Margin = new Thickness(0, 3, 0, 3);
                _plainViews.Add(tb);
                LinesPanel.Children.Add(tb);
            }
        }

        OnResized();
        Scroller.ScrollToTop();
        _scrollTarget = 0;
        Tick();
    }

    private TextBlock MakeLine(string text, Brush brush) => new()
    {
        Text = text,
        Foreground = brush,
        FontSize = LineFontSize(),
        FontWeight = FontWeights.Bold,
        TextWrapping = TextWrapping.Wrap,
        Margin = new Thickness(0, 0, 0, 16),
        RenderTransformOrigin = new Point(0, 0.5),
        RenderTransform = new ScaleTransform(InactiveScale, InactiveScale),
    };

    /// <summary>Text grows with the window, so a maximized window works as a karaoke screen.</summary>
    private double LineFontSize() => Math.Clamp(ActualWidth / 17, 22, 64);

    private void OnResized()
    {
        var size = LineFontSize();
        foreach (var tb in _lineViews) tb.FontSize = size;
        if (_introView != null) _introView.FontSize = size;
        foreach (var tb in _plainViews) tb.FontSize = Math.Max(17, size * 0.78);
        // Padding lets the first and last lines reach the "reading line" (upper third).
        var h = Scroller.ActualHeight;
        if (_result.Kind == LyricsKind.Synced)
            LinesPanel.Margin = new Thickness(28, h * ReadingLine, 28, h * 0.7);
        else
            LinesPanel.Margin = new Thickness(28, h * 0.12, 28, h * 0.15); // clear of the edge fades
        _current = -2; // force re-scroll to the right spot after layout changes
    }

    private void Tick()
    {
        UpdateProgress();
        if (_result.Kind == LyricsKind.Plain) { FollowPlain(); return; }
        if (_result.Kind != LyricsKind.Synced || _track == null) return;
        var index = LrcParser.IndexAt(_result.Lines!, _track.PositionNow() + _offset);
        if (index == _current) return;
        _current = index;

        for (int i = 0; i < _lineViews.Count; i++)
            StyleLine(_lineViews[i], active: i == index, past: i < index);

        if (_introView != null)
        {
            var introActive = index < 0;
            StyleLine(_introView, introActive, past: !introActive);
            // Gentle breathing while waiting for the first line.
            _introView.BeginAnimation(OpacityProperty, introActive
                ? new DoubleAnimation(0.35, 1, TimeSpan.FromMilliseconds(900))
                {
                    AutoReverse = true, RepeatBehavior = RepeatBehavior.Forever, EasingFunction = new SineEase(),
                }
                : null);
        }

        Dispatcher.BeginInvoke(ScrollToCurrent, DispatcherPriority.Loaded);
    }

    /// <summary>Plain lyrics: scroll in proportion to song progress, unless the user scrolled recently.</summary>
    private void FollowPlain()
    {
        if (_track == null || _track.Duration <= TimeSpan.Zero) return;
        var now = DateTime.UtcNow;
        if (now - _userScrollAt < TimeSpan.FromSeconds(4) || now - _plainScrollAt < TimeSpan.FromSeconds(1)) return;
        _plainScrollAt = now;
        var max = Scroller.ExtentHeight - Scroller.ViewportHeight;
        if (max <= 0) return;
        // Lag slightly behind the song so the line being sung stays in view.
        var f = Math.Clamp((_track.PositionNow() - TimeSpan.FromSeconds(8)) / _track.Duration, 0, 1);
        _scrollTarget = f * max;
        if (!_animating)
        {
            _animating = true;
            CompositionTarget.Rendering += AnimateScroll;
        }
    }

    private static void StyleLine(TextBlock tb, bool active, bool past)
    {
        tb.Foreground = active ? ActiveBrush : past ? PastBrush : FutureBrush;
        tb.Effect = active ? ActiveGlow : null;
        var scale = (ScaleTransform)tb.RenderTransform;
        var anim = new DoubleAnimation(active ? 1.0 : InactiveScale, TimeSpan.FromMilliseconds(220)) { EasingFunction = new QuadraticEase() };
        scale.BeginAnimation(ScaleTransform.ScaleXProperty, anim);
        scale.BeginAnimation(ScaleTransform.ScaleYProperty, anim);
    }

    private void ScrollToCurrent()
    {
        var tb = _current >= 0 && _current < _lineViews.Count ? _lineViews[_current] : _introView ?? _lineViews.FirstOrDefault();
        if (tb == null) return;
        var y = tb.TranslatePoint(new Point(0, 0), LinesPanel).Y + LinesPanel.Margin.Top;
        _scrollTarget = Math.Max(0, y - Scroller.ActualHeight * ReadingLine);
        if (!_animating)
        {
            _animating = true;
            CompositionTarget.Rendering += AnimateScroll;
        }
    }

    private void StopScrollAnimation()
    {
        if (!_animating) return;
        CompositionTarget.Rendering -= AnimateScroll;
        _animating = false;
    }

    private void AnimateScroll(object? sender, EventArgs e)
    {
        var cur = Scroller.VerticalOffset;
        var delta = _scrollTarget - cur;
        if (Math.Abs(delta) < 0.5)
        {
            Scroller.ScrollToVerticalOffset(_scrollTarget);
            CompositionTarget.Rendering -= AnimateScroll;
            _animating = false;
            return;
        }
        Scroller.ScrollToVerticalOffset(cur + delta * 0.14);
    }

    // ---------------------------------------------------------------- controls

    private void Earlier_Click(object sender, RoutedEventArgs e) => Nudge(+250);

    private void Later_Click(object sender, RoutedEventArgs e) => Nudge(-250);

    private void Nudge(int ms)
    {
        var next = _offset + TimeSpan.FromMilliseconds(ms);
        _offset = TimeSpan.FromMilliseconds(Math.Clamp(next.TotalMilliseconds, -10_000, 10_000));
        UpdateOffsetText();
        _current = -2;
        Tick();
    }

    private void UpdateOffsetText() =>
        OffsetText.Text = _offset == TimeSpan.Zero ? "In sync" : $"{_offset.TotalSeconds:+0.00;-0.00}s";

    protected override void OnKeyDown(KeyEventArgs e)
    {
        // Small keyboard helpers: ← later, → earlier, F11 full screen.
        switch (e.Key)
        {
            case Key.Left: Nudge(-250); break;
            case Key.Right: Nudge(+250); break;
            case Key.F11: ToggleFullScreen(); break;
            case Key.Escape when WindowStyle == WindowStyle.None: ToggleFullScreen(); break;
        }
        base.OnKeyDown(e);
    }

    private void ToggleFullScreen()
    {
        if (WindowStyle == WindowStyle.None)
        {
            WindowStyle = WindowStyle.SingleBorderWindow;
            WindowState = WindowState.Normal;
        }
        else
        {
            WindowStyle = WindowStyle.None;
            WindowState = WindowState.Maximized;
        }
    }

    // ---------------------------------------------------------------- dark title bar

    [DllImport("dwmapi.dll")]
    private static extern int DwmSetWindowAttribute(IntPtr hwnd, int attr, ref int value, int size);

    private void UseDarkTitleBar()
    {
        var hwnd = new WindowInteropHelper(this).Handle;
        int on = 1;
        DwmSetWindowAttribute(hwnd, 20 /* DWMWA_USE_IMMERSIVE_DARK_MODE */, ref on, sizeof(int));
        SetCaptionColor(ArtworkColors.Fallback);
    }

    /// <summary>Colours the title bar to match the top of the background (Windows 11; ignored on 10).</summary>
    private void SetCaptionColor(Color c)
    {
        var hwnd = new WindowInteropHelper(this).Handle;
        if (hwnd == IntPtr.Zero) return;
        int bgr = c.R | (c.G << 8) | (c.B << 16);
        DwmSetWindowAttribute(hwnd, 35 /* DWMWA_CAPTION_COLOR */, ref bgr, sizeof(int));
    }
}
