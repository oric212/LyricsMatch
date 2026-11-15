using System.Windows;
using System.Windows.Input;
using System.Windows.Media;
using LyricsMatch.Windows.Core;

namespace LyricsMatch.Windows;

/// <summary>
/// Song progress drawn as a row of equalizer bars. Bars before the playhead are bright,
/// the rest dim. Heights follow the live audio; with no audio signal they fall back to a
/// beat-style animation while playing, and settle into a flat line when paused.
/// Click anywhere to seek.
/// </summary>
public sealed class EqualizerBar : FrameworkElement
{
    private static readonly Brush Played = Frozen(Color.FromArgb(0xEB, 0xFF, 0xFF, 0xFF));
    private static readonly Brush Unplayed = Frozen(Color.FromArgb(0x38, 0xFF, 0xFF, 0xFF));

    private readonly float[] _target = new float[AudioSpectrum.Bands];
    private readonly float[] _shown = new float[AudioSpectrum.Bands];
    private TimeSpan _lastFrame;
    private bool _hooked;

    public AudioSpectrum? Spectrum { get; set; }

    /// <summary>Smoothed level at <paramref name="pos"/> (0..1 across low→high frequencies).</summary>
    public float At(double pos)
    {
        var p = Math.Clamp(pos, 0, 1) * (_shown.Length - 1);
        int a = (int)p, b = Math.Min(a + 1, _shown.Length - 1);
        return (float)(_shown[a] + (_shown[b] - _shown[a]) * (p - a));
    }

    public float Bass =>(_shown[0] + _shown[1] + _shown[2] + _shown[3] + _shown[4] + _shown[5]) / 6f;
    public double Progress { get; set; }
    public bool Playing { get; set; }


    /// <summary>Raised with the clicked fraction (0..1) of the song.</summary>
    public event Action<double>? SeekRequested;

    public EqualizerBar()
    {
        Cursor = Cursors.Hand;
        ToolTip = "Jump here";
        Loaded += (_, _) => Hook(true);
        Unloaded += (_, _) => Hook(false);
    }

    private void Hook(bool on)
    {
        if (on == _hooked) return;
        _hooked = on;
        if (on) CompositionTarget.Rendering += OnFrame;
        else CompositionTarget.Rendering -= OnFrame;
    }

    private void OnFrame(object? sender, EventArgs e)
    {
        var now = ((RenderingEventArgs)e).RenderingTime;
        var dt = (float)Math.Clamp((now - _lastFrame).TotalSeconds, 0, 0.1);
        if (dt == 0) return;
        _lastFrame = now;

        if (Playing && Spectrum is { HasSignal: true })
            Spectrum.CopyLevels(_target);
        else if (Playing)
            BeatStyle(now.TotalSeconds);
        else
            Array.Clear(_target);

        // Fast rise, slower fall: reads as "musical" rather than jittery.
        for (int i = 0; i < _shown.Length; i++)
        {
            var k = _target[i] > _shown[i] ? 18f : 6f;
            _shown[i] += (_target[i] - _shown[i]) * Math.Min(1f, k * dt);
        }
        InvalidateVisual();
    }

    /// <summary>Fallback when no audio can be read: smooth, beat-like motion.</summary>
    private void BeatStyle(double t)
    {
        var beat = 0.5 + 0.5 * Math.Pow(Math.Max(0, Math.Sin(t * Math.PI * 2 * 1.05)), 6);
        for (int i = 0; i < _target.Length; i++)
        {
            var wave = 0.5 + 0.5 * Math.Sin(t * (2.1 + i % 7 * 0.37) + i * 0.9);
            var shape = 1 - Math.Abs(i - _target.Length * 0.35) / _target.Length; // fuller lows/mids
            _target[i] = (float)Math.Clamp(0.15 + 0.6 * wave * shape * beat, 0, 1);
        }
    }

    protected override void OnRender(DrawingContext dc)
    {
        double w = ActualWidth, h = ActualHeight;
        if (w <= 0 || h <= 0) return;
        dc.DrawRectangle(Brushes.Transparent, null, new Rect(0, 0, w, h)); // whole area clickable

        // Thin bars, as many as fit; each samples the band levels by interpolation.
        const double barW = 4, gap = 3;
        int n = Math.Max(8, (int)((w + gap) / (barW + gap)));
        var step = (w - barW) / (n - 1);
        var minH = 3.0;
        var playedX = w * Math.Clamp(Progress, 0, 1);
        for (int i = 0; i < n; i++)
        {
            var x = i * step;
            var level = At((double)i / (n - 1));
            var bh = minH + level * (h - minH);
            var rect = new Rect(x, (h - bh) / 2, barW, bh);
            var brush = x + barW / 2 <= playedX ? Played : Unplayed;
            dc.DrawRoundedRectangle(brush, null, rect, barW / 2, barW / 2);
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
