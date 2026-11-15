using NAudio.CoreAudioApi;
using NAudio.Dsp;
using NAudio.Wave;

namespace LyricsMatch.Windows.Core;

/// <summary>
/// Live frequency levels of whatever the PC is playing (WASAPI loopback of the default
/// output device). Audio is analysed in memory only; nothing is recorded or stored.
/// </summary>
public sealed class AudioSpectrum : IDisposable
{
    public const int Bands = 48;
    private const int FftExp = 11;               // 2048-point FFT
    private const int FftSize = 1 << FftExp;
    private const int Hop = FftSize / 2;

    private readonly float[] _levels = new float[Bands];
    private readonly float[] _window = new float[FftSize];
    private readonly Complex[] _fft = new Complex[FftSize];
    private readonly object _gate = new();
    private WasapiLoopbackCapture? _capture;
    private int _channels, _sampleRate, _fill;
    private bool _isFloat;
    private bool _disposed;
    private long _lastDataTicks;

    /// <summary>True when sound data arrived recently (silence / exclusive-mode apps produce none).</summary>
    public bool HasSignal => Environment.TickCount64 - Interlocked.Read(ref _lastDataTicks) < 400;

    public void Start()
    {
        if (_disposed) return;
        try
        {
            _capture = new WasapiLoopbackCapture();
            _channels = _capture.WaveFormat.Channels;
            _sampleRate = _capture.WaveFormat.SampleRate;
            _isFloat = _capture.WaveFormat.Encoding == WaveFormatEncoding.IeeeFloat
                       || (_capture.WaveFormat.Encoding == WaveFormatEncoding.Extensible && _capture.WaveFormat.BitsPerSample == 32);
            _capture.DataAvailable += OnData;
            // Output device changed (e.g. headphones plugged in): restart on the new default.
            _capture.RecordingStopped += (_, _) =>
            {
                _capture?.Dispose();
                _capture = null;
                if (!_disposed) Task.Delay(1000).ContinueWith(_ => Start());
            };
            _capture.StartRecording();
        }
        catch (Exception)
        {
            _capture = null; // no output device: the UI falls back to the beat-style animation
        }
    }

    /// <summary>Copies the latest levels (0..1 per band, low to high frequencies).</summary>
    public void CopyLevels(float[] dest)
    {
        lock (_gate) Array.Copy(_levels, dest, Math.Min(dest.Length, Bands));
    }

    private void OnData(object? sender, WaveInEventArgs e)
    {
        var bytesPerFrame = (_isFloat ? 4 : 2) * _channels;
        if (bytesPerFrame == 0 || e.BytesRecorded == 0) return;

        bool any = false;
        for (int off = 0; off + bytesPerFrame <= e.BytesRecorded; off += bytesPerFrame)
        {
            float sum = 0;
            for (int ch = 0; ch < _channels; ch++)
            {
                var p = off + ch * (_isFloat ? 4 : 2);
                sum += _isFloat ? BitConverter.ToSingle(e.Buffer, p) : BitConverter.ToInt16(e.Buffer, p) / 32768f;
            }
            var s = sum / _channels;
            if (s != 0) any = true;
            _window[_fill++] = s;
            if (_fill == FftSize)
            {
                Analyze();
                Array.Copy(_window, Hop, _window, 0, FftSize - Hop);
                _fill = FftSize - Hop;
            }
        }
        if (any) Interlocked.Exchange(ref _lastDataTicks, Environment.TickCount64);
    }

    private void Analyze()
    {
        for (int i = 0; i < FftSize; i++)
        {
            _fft[i].X = (float)(_window[i] * FastFourierTransform.HannWindow(i, FftSize));
            _fft[i].Y = 0;
        }
        FastFourierTransform.FFT(true, FftExp, _fft);

        // Log-spaced bands from 50 Hz to 14 kHz, like a classic equalizer.
        const double fLo = 50, fHi = 14000;
        var binHz = (double)_sampleRate / FftSize;
        var db = new double[Bands];
        double frameMax = double.MinValue;
        for (int b = 0; b < Bands; b++)
        {
            var f0 = fLo * Math.Pow(fHi / fLo, (double)b / Bands);
            var f1 = fLo * Math.Pow(fHi / fLo, (double)(b + 1) / Bands);
            int i0 = Math.Max(1, (int)(f0 / binHz)), i1 = Math.Max(i0 + 1, (int)(f1 / binHz));
            double peak = 0;
            for (int i = i0; i < i1 && i < FftSize / 2; i++)
                peak = Math.Max(peak, Math.Sqrt(_fft[i].X * _fft[i].X + _fft[i].Y * _fft[i].Y));
            // Tilt up the highs: music naturally has less energy there.
            db[b] = 20 * Math.Log10(peak + 1e-12) + 4.5 * Math.Log2(1 + b / 6.0);
            frameMax = Math.Max(frameMax, db[b]);
        }

        // Automatic gain: measure against the music's own recent peak, so bars use their
        // full height whatever the volume. The reference falls slowly in quiet passages.
        _refDb = frameMax > _refDb ? frameMax : Math.Max(frameMax, _refDb - 0.08);
        var result = new float[Bands];
        for (int b = 0; b < Bands; b++)
            result[b] = (float)Math.Clamp((db[b] - (_refDb - Range)) / Range, 0, 1);
        lock (_gate) Array.Copy(result, _levels, Bands);
    }

    private const double Range = 42; // dB shown from the bottom to the top of a bar
    private double _refDb = -200;

    public void Dispose()
    {
        _disposed = true;
        try { _capture?.StopRecording(); } catch (Exception) { }
        _capture?.Dispose();
    }
}
