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
    private static readonly Brush PastBrush = new SolidColorBrush(Color.FromArgb(0x4D, 0xFF, 0xFF, 0xFF));
    private static readonly Brush FutureBrush = new SolidColorBrush(Color.FromArgb(0x8C, 0xFF, 0xFF, 0xFF));

    private readonly MediaWatcher _media = new();
    private readonly LyricsClient _lyrics = new();
    private readonly DispatcherTimer _ticker = new(DispatcherPriority.Render) { Interval = TimeSpan.FromMilliseconds(50) };

    private NowPlaying? _track;
    private LyricsResult _result = LyricsResult.Idle;
    private CancellationTokenSource? _loadCts;
    private readonly List<TextBlock> _lineViews = new();
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
    }

    // ---------------------------------------------------------------- media

    private void OnNowPlaying(NowPlaying? np)
    {
        var newSong = np?.TrackKey != _track?.TrackKey;
        _track = np;

        TitleText.Text = np?.Title ?? "Nothing playing";
        ArtistText.Text = np?.Artist ?? "";

        if (newSong)
        {
            _offset = TimeSpan.Zero;
            UpdateOffsetText();
            StartLoad(forceRefresh: false);
        }

        if (np?.IsPlaying == true) _ticker.Start();
        else { _ticker.Stop(); Tick(); }
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
            var result = await _lyrics.LoadAsync(np, forceRefresh, cts.Token);
            if (!cts.IsCancellationRequested && _track?.TrackKey == np.TrackKey) Show(result);
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

        var (message, retry) = result.Kind switch
        {
            LyricsKind.Idle => ("Play a song in Spotify or any music app", false),
            LyricsKind.Loading => ("Finding lyrics…", false),
            LyricsKind.Instrumental => ("♪  Instrumental  ♪", false),
            LyricsKind.NotFound => ("No lyrics found for this song", true),
            LyricsKind.Error => (result.Text ?? "Something went wrong", true),
            _ => ((string?)null, false),
        };
        MessagePanel.Visibility = message != null ? Visibility.Visible : Visibility.Collapsed;
        MessageText.Text = message ?? "";
        RetryButton.Visibility = retry ? Visibility.Visible : Visibility.Collapsed;
        SyncBar.Visibility = result.Kind == LyricsKind.Synced ? Visibility.Visible : Visibility.Collapsed;

        if (result.Kind == LyricsKind.Synced)
        {
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
            LinesPanel.Children.Add(new TextBlock
            {
                Text = "Synced lyrics unavailable — showing full text",
                Foreground = (Brush)FindResource("Accent"),
                FontSize = 13,
                Margin = new Thickness(0, 0, 0, 14),
            });
            var tb = MakeLine(result.Text!.Trim(), new SolidColorBrush(Color.FromArgb(0xD9, 0xFF, 0xFF, 0xFF)));
            tb.FontWeight = FontWeights.Normal;
            tb.FontSize = Math.Max(16, LineFontSize() * 0.7);
            LinesPanel.Children.Add(tb);
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
        RenderTransform = new ScaleTransform(0.92, 0.92),
    };

    /// <summary>Text grows with the window, so a maximized window works as a karaoke screen.</summary>
    private double LineFontSize() => Math.Clamp(ActualWidth / 17, 22, 64);

    private void OnResized()
    {
        var size = LineFontSize();
        foreach (var tb in _lineViews) tb.FontSize = size;
        // Padding lets the first and last lines reach the "reading line" (upper third).
        var h = Scroller.ActualHeight;
        if (_result.Kind == LyricsKind.Synced)
            LinesPanel.Margin = new Thickness(28, h * 0.32, 28, h * 0.6);
        else
            LinesPanel.Margin = new Thickness(28, 8, 28, 24);
        _current = -2; // force re-scroll to the right spot after layout changes
    }

    private void Tick()
    {
        if (_result.Kind != LyricsKind.Synced || _track == null) return;
        var index = LrcParser.IndexAt(_result.Lines!, _track.PositionNow() + _offset);
        if (index == _current) return;
        _current = index;

        for (int i = 0; i < _lineViews.Count; i++)
        {
            var tb = _lineViews[i];
            var active = i == index;
            tb.Foreground = active ? ActiveBrush : i < index ? PastBrush : FutureBrush;
            var scale = (ScaleTransform)tb.RenderTransform;
            var target = active ? 1.0 : 0.92;
            var anim = new DoubleAnimation(target, TimeSpan.FromMilliseconds(220)) { EasingFunction = new QuadraticEase() };
            scale.BeginAnimation(ScaleTransform.ScaleXProperty, anim);
            scale.BeginAnimation(ScaleTransform.ScaleYProperty, anim);
        }

        Dispatcher.BeginInvoke(ScrollToCurrent, DispatcherPriority.Loaded);
    }

    private void ScrollToCurrent()
    {
        var tb = _current >= 0 && _current < _lineViews.Count ? _lineViews[_current] : _lineViews.FirstOrDefault();
        if (tb == null) return;
        var y = tb.TranslatePoint(new Point(0, 0), LinesPanel).Y + LinesPanel.Margin.Top;
        _scrollTarget = Math.Max(0, y - Scroller.ActualHeight * 0.32);
        if (!_animating)
        {
            _animating = true;
            CompositionTarget.Rendering += AnimateScroll;
        }
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

    private void Retry_Click(object sender, RoutedEventArgs e) => StartLoad(forceRefresh: true);

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
        int color = 0x00100A0B; // caption color, BGR of #0B0A10
        DwmSetWindowAttribute(hwnd, 35 /* DWMWA_CAPTION_COLOR */, ref color, sizeof(int));
    }
}
