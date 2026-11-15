package com.lyricsmatch.media

import android.media.audiofx.Visualizer
import android.os.SystemClock
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Live frequency levels of the phone's audio output (Android's Visualizer on the global
 * mix). Optional: needs the "record audio" permission. Audio is analysed in memory only;
 * nothing is recorded or stored. Some phones/apps provide no data (e.g. offloaded playback);
 * then [hasSignal] stays false and the UI uses its beat-style fallback.
 */
class AudioSpectrum {
    private var visualizer: Visualizer? = null
    private val levels = FloatArray(BANDS)
    @Volatile private var lastSignal = 0L
    private var refDb = -200.0

    val hasSignal: Boolean get() = SystemClock.elapsedRealtime() - lastSignal < 400

    fun start(): Boolean {
        if (visualizer != null) return true
        return try {
            val v = Visualizer(0)
            v.captureSize = Visualizer.getCaptureSizeRange()[1]
            val rateHz = v.samplingRate / 1000.0 // samplingRate is in milliHertz
            v.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(vis: Visualizer, waveform: ByteArray, samplingRate: Int) = Unit
                override fun onFftDataCapture(vis: Visualizer, fft: ByteArray, samplingRate: Int) =
                    analyze(fft, rateHz)
            }, Visualizer.getMaxCaptureRate(), false, true)
            v.enabled = true
            visualizer = v
            true
        } catch (_: Exception) {
            false // not permitted / not supported: fallback animation
        }
    }

    fun stop() {
        visualizer?.run { runCatching { enabled = false }; release() }
        visualizer = null
    }

    fun copyLevels(dest: FloatArray) = synchronized(levels) { levels.copyInto(dest, endIndex = min(dest.size, BANDS)) }

    private fun analyze(fft: ByteArray, rateHz: Double) {
        val n = fft.size // bytes: [DC, Nyquist, re1, im1, re2, im2, ...]
        val bins = n / 2
        val binHz = rateHz / n
        val db = DoubleArray(BANDS)
        var frameMax = -200.0
        var any = false
        for (b in 0 until BANDS) {
            val f0 = F_LO * (F_HI / F_LO).pow(b.toDouble() / BANDS)
            val f1 = F_LO * (F_HI / F_LO).pow((b + 1).toDouble() / BANDS)
            val i0 = max(1, (f0 / binHz).toInt())
            val i1 = max(i0 + 1, (f1 / binHz).toInt())
            var peak = 0.0
            for (i in i0 until min(i1, bins)) {
                peak = max(peak, hypot(fft[2 * i].toDouble(), fft[2 * i + 1].toDouble()))
            }
            if (peak > 0) any = true
            // Tilt up the highs: music naturally has less energy there.
            db[b] = 20 * log10(peak + 1e-9) + 4.5 * ln(1 + b / 6.0) / ln(2.0)
            frameMax = max(frameMax, db[b])
        }
        if (!any) return
        lastSignal = SystemClock.elapsedRealtime()
        // Automatic gain against the music's own recent peak.
        refDb = if (frameMax > refDb) frameMax else max(frameMax, refDb - 0.15)
        synchronized(levels) {
            for (b in 0 until BANDS) levels[b] = ((db[b] - (refDb - RANGE)) / RANGE).coerceIn(0.0, 1.0).toFloat()
        }
    }

    companion object {
        const val BANDS = 48
        private const val F_LO = 50.0
        private const val F_HI = 14000.0
        private const val RANGE = 36.0
    }
}
