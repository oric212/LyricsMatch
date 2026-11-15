using System.Windows;
using System.Windows.Input;
using System.Windows.Media;

namespace LyricsMatch.Windows;

/// <summary>
/// The no-lyrics screen's main bar: grows up and down from a centre line (bass in the middle,
/// treble towards the edges), bright before the playhead and dim after it. Click to seek.
/// Levels come from the <see cref="EqualizerBar"/>.
/// </summary>
public sealed class MirrorSpectrum : FrameworkElement
{
    private static readonly Brush Played = Frozen(Color.FromArgb(0xF2, 0xFF, 0xFF, 0xFF));
    private static readonly Brush Unplayed = Frozen(Color.FromArgb(0x47, 0xFF, 0xFF, 0xFF));
    public EqualizerBar? Source { get; set; }
    public double Progress { get; set; }

    /// <summary>Raised with the clicked fraction (0..1) of the song.</summary>
    public event Action<double>? SeekRequested;

    public MirrorSpectrum()
    {
        Cursor = Cursors.Hand;
        ToolTip = "Jump here";
        IsVisibleChanged += (_, _) =>
        {
            if (IsVisible) CompositionTarget.Rendering += OnFrame;
            else CompositionTarget.Rendering -= OnFrame;
        };
    }

    private void OnFrame(object? sender, EventArgs e)
    {
        InvalidateVisual();
    }

    protected override void OnRender(DrawingContext dc)
    {
        double w = ActualWidth, h = ActualHeight;
        if (w <= 0 || h <= 0 || Source == null) return;
        dc.DrawRectangle(Brushes.Transparent, null, new Rect(0, 0, w, h)); // whole area clickable

        const double barW = 4, gap = 3.5, minH = 4;
        int n = (int)((w + gap) / (barW + gap));
        if (n % 2 == 0) n--;
        if (n < 3) return;
        var step = (w - barW) / (n - 1);
        var c = (n - 1) / 2.0;
        var playedX = w * Math.Clamp(Progress, 0, 1);
        for (int i = 0; i < n; i++)
        {
            var x = i * step;
            var d = Math.Abs(i - c) / c; // 0 centre … 1 edge
            var level = Source.At(d * 0.9) * (1 - 0.3 * d);
            var bh = minH + level * (h - minH);
            dc.DrawRoundedRectangle(x + barW / 2 <= playedX ? Played : Unplayed, null,
                new Rect(x, (h - bh) / 2, barW, bh), barW / 2, barW / 2);
        }
    }

    protected override void OnMouseLeftButtonUp(MouseButtonEventArgs e)
    {
        base.OnMouseLeftButtonUp(e);
        if (ActualWidth > 0) SeekRequested?.Invoke(Math.Clamp(e.GetPosition(this).X / ActualWidth, 0, 1));
    }

    private static Brush Frozen(Color c)
    {
        var b = new SolidColorBrush(c);
        b.Freeze();
        return b;
    }
}
